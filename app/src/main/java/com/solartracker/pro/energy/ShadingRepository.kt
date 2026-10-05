package com.solartracker.pro.energy

import android.content.Context
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.shading.GeocodeResult
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.NominatimLocationProvider
import com.solartracker.pro.core.shading.Obstacle
import com.solartracker.pro.core.shading.ObstacleChange
import com.solartracker.pro.core.shading.ObstacleCodec
import com.solartracker.pro.core.shading.OpenMeteoGeocodingProvider
import com.solartracker.pro.core.shading.OpenMeteoTerrainProvider
import com.solartracker.pro.core.shading.OverpassBuildingProvider
import com.solartracker.pro.core.shading.TerrainSamples
import com.solartracker.pro.core.shading.TerrainSampler
import com.solartracker.pro.core.update.UrlConnectionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Properties

/** Map data that was downloaded for a location (offline cache). */
data class MapDataCache(
    val center: LatLon?,
    val fetchedAt: Instant?,
    val automatic: List<Obstacle>,
    val terrain: TerrainSamples?,
    val user: List<Obstacle>,
) {
    val loaded: Boolean get() = fetchedAt != null
}

/**
 * Geocoding, building/terrain download and offline storage of map data and the user's obstacles.
 * Data is only downloaded on explicit user action (after the location is confirmed).
 */
class ShadingRepository(context: Context) {
    private val dir = File(context.filesDir, "shading").apply { mkdirs() }
    private val http = UrlConnectionHttpClient(connectTimeoutMs = 15_000, readTimeoutMs = 40_000)
    private val userAgent = "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android; github.com/RzKa1337/PV)"

    private val autoFile = File(dir, "osm_obstacles.json")
    private val userFile = File(dir, "user_obstacles.json")
    private val terrainFile = File(dir, "terrain.json")
    private val metaFile = File(dir, "meta.properties")

    suspend fun search(query: String): List<GeocodeResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val nominatim = runCatching { NominatimLocationProvider(http, userAgent).search(q, 5) }
        val results = nominatim.getOrNull().orEmpty()
        if (results.isNotEmpty()) return@withContext results
        val city = runCatching { OpenMeteoGeocodingProvider(http, userAgent).search(q, 5) }
        city.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: nominatim.exceptionOrNull()?.let { throw it }
            ?: city.exceptionOrNull()?.let { throw it }
            ?: emptyList()
    }

    /** Downloads buildings/trees around [center] and the terrain; keeps the previous cache on failure. */
    suspend fun download(center: LatLon, radiusM: Int): MapDataCache = withContext(Dispatchers.IO) {
        val obstacles = OverpassBuildingProvider(http, userAgent).obstaclesAround(center, radiusM)
        val terrain = runCatching { TerrainSampler.sample(OpenMeteoTerrainProvider(http, userAgent), center) }.getOrNull()
        autoFile.writeText(ObstacleCodec.encode(obstacles))
        if (terrain != null) terrainFile.writeText(ObstacleCodec.encodeTerrain(terrain)) else terrainFile.delete()
        Properties().apply {
            setProperty("lat", center.lat.toString()); setProperty("lon", center.lon.toString())
            setProperty("fetched", Instant.now().toString())
        }.let { p -> metaFile.outputStream().use { p.store(it, null) } }
        load()
    }

    suspend fun load(): MapDataCache = withContext(Dispatchers.IO) {
        val meta = runCatching { Properties().apply { metaFile.inputStream().use(::load) } }.getOrNull()
        val center = meta?.let { m -> runCatching { LatLon(m.getProperty("lat").toDouble(), m.getProperty("lon").toDouble()) }.getOrNull() }
        MapDataCache(
            center = center,
            fetchedAt = meta?.getProperty("fetched")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            automatic = if (autoFile.isFile) ObstacleCodec.decode(autoFile.readText()) else emptyList(),
            terrain = if (terrainFile.isFile) ObstacleCodec.decodeTerrain(terrainFile.readText()) else null,
            user = if (userFile.isFile) ObstacleCodec.decode(userFile.readText()) else emptyList(),
        )
    }

    /** Adds or replaces a user obstacle, recording the change in its history. */
    suspend fun saveUserObstacle(o: Obstacle, change: String): MapDataCache = withContext(Dispatchers.IO) {
        val current = load().user
        val previous = current.firstOrNull { it.id == o.id }
        val updated = o.copy(userDefined = true, history = (previous?.history ?: o.history) + ObstacleChange(Instant.now(), change))
        userFile.writeText(ObstacleCodec.encode(current.filter { it.id != o.id } + updated))
        load()
    }

    suspend fun deleteUserObstacle(id: String): MapDataCache = withContext(Dispatchers.IO) {
        userFile.writeText(ObstacleCodec.encode(load().user.filter { it.id != id }))
        load()
    }

    /** Removes downloaded map data (privacy): keeps the user's own obstacles. */
    suspend fun clearDownloaded(): MapDataCache = withContext(Dispatchers.IO) {
        autoFile.delete(); terrainFile.delete(); metaFile.delete()
        load()
    }
}

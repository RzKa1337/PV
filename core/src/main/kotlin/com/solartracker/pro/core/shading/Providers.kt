package com.solartracker.pro.core.shading

import com.solartracker.pro.core.update.HttpClient
import com.solartracker.pro.core.update.UpdateException
import com.solartracker.pro.core.update.getFollowingRedirects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Result of turning a city / address / postcode into coordinates. */
data class GeocodeResult(
    val name: String,
    val point: LatLon,
    val accuracy: LocationAccuracy,
    val source: String,
    val elevationM: Double? = null,
    val timezone: String? = null,
)

interface MapLocationProvider {
    /** @throws java.io.IOException on network errors */
    fun search(query: String, limit: Int = 5): List<GeocodeResult>
}

interface TerrainDataProvider {
    val sourceName: String
    val resolutionM: Double?

    /** Terrain elevations [m ASL] for the points (null where unknown). */
    fun elevations(points: List<LatLon>): List<Double?>
}

interface BuildingDataProvider {
    val sourceName: String

    /** Buildings and other obstacles (with heights where known) within [radiusM]. */
    fun obstaclesAround(center: LatLon, radiusM: Int): List<Obstacle>
}

private val json = Json { ignoreUnknownKeys = true }
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun HttpClient.getText(url: String, userAgent: String): String {
    val r = getFollowingRedirects(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
    r.use {
        val body = it.body.bufferedReader().readText()
        if (it.code != 200) throw UpdateException("Serwer danych mapowych: HTTP ${it.code}")
        return body
    }
}

/**
 * Geocoding with OpenStreetMap Nominatim (addresses, postcodes) – usage policy: max 1 request/s,
 * identifying User-Agent; called only on explicit user search.
 */
class NominatimLocationProvider(
    private val http: HttpClient,
    private val userAgent: String,
    private val base: String = "https://nominatim.openstreetmap.org",
    private val language: String = "pl",
) : MapLocationProvider {
    override fun search(query: String, limit: Int): List<GeocodeResult> {
        val q = query.trim()
        require(q.length in 2..200) { "query length" }
        val url = "$base/search?format=jsonv2&addressdetails=0&limit=$limit&accept-language=${enc(language)}&q=${enc(q)}"
        return parse(http.getText(url, userAgent))
    }

    companion object {
        fun parse(body: String): List<GeocodeResult> {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: throw IllegalArgumentException("Nieprawidłowa odpowiedź geokodowania")
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val lat = o.num("lat") ?: return@mapNotNull null
                val lon = o.num("lon") ?: return@mapNotNull null
                val type = o.str("addresstype") ?: o.str("type") ?: ""
                val accuracy = when (type) {
                    "house", "building", "house_number" -> LocationAccuracy.ADDRESS
                    "postcode" -> LocationAccuracy.POSTCODE
                    "road", "street" -> LocationAccuracy.POSTCODE
                    else -> LocationAccuracy.CITY
                }
                GeocodeResult(o.str("display_name") ?: "?", LatLon(lat, lon), accuracy, "OpenStreetMap Nominatim")
            }
        }
    }
}

/** City search with the Open-Meteo geocoding API (fast, includes elevation and time zone). */
class OpenMeteoGeocodingProvider(
    private val http: HttpClient,
    private val userAgent: String,
    private val base: String = "https://geocoding-api.open-meteo.com",
) : MapLocationProvider {
    override fun search(query: String, limit: Int): List<GeocodeResult> {
        val url = "$base/v1/search?count=$limit&language=pl&format=json&name=${enc(query.trim())}"
        return parse(http.getText(url, userAgent))
    }

    companion object {
        fun parse(body: String): List<GeocodeResult> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("Nieprawidłowa odpowiedź")
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val lat = o.num("latitude") ?: return@mapNotNull null
                val lon = o.num("longitude") ?: return@mapNotNull null
                val label = listOfNotNull(o.str("name"), o.str("admin1"), o.str("country")).joinToString(", ")
                GeocodeResult(label, LatLon(lat, lon), LocationAccuracy.CITY, "Open-Meteo Geocoding", o.num("elevation"), o.str("timezone"))
            }
        }
    }
}

/** Terrain from the Open-Meteo elevation API (Copernicus DEM GLO-90, ~90 m), max 100 points per call. */
class OpenMeteoTerrainProvider(
    private val http: HttpClient,
    private val userAgent: String,
    private val base: String = "https://api.open-meteo.com",
) : TerrainDataProvider {
    override val sourceName = "Copernicus DEM GLO-90 (Open-Meteo)"
    override val resolutionM = 90.0

    override fun elevations(points: List<LatLon>): List<Double?> = points.chunked(100).flatMap { chunk ->
        val lats = chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lat) }
        val lons = chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lon) }
        parse(http.getText("$base/v1/elevation?latitude=$lats&longitude=$lons", userAgent), chunk.size)
    }

    companion object {
        fun parse(body: String, expected: Int): List<Double?> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("Nieprawidłowa odpowiedź")
            val arr = root["elevation"] as? JsonArray ?: throw IllegalArgumentException("Brak wysokości w odpowiedzi")
            if (arr.size != expected) throw IllegalArgumentException("Oczekiwano $expected wysokości, jest ${arr.size}")
            return arr.map { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { v -> v.isFinite() && v > -500 } }
        }
    }
}

/**
 * Buildings, trees, chimneys, masts and walls from OpenStreetMap via the Overpass API. Heights come
 * only from tags: height / roof:height (MAP_TAG), building:levels (FROM_LEVELS, estimated) –
 * everything else is UNKNOWN and must be completed by the user.
 */
class OverpassBuildingProvider(
    private val http: HttpClient,
    private val userAgent: String,
    private val base: String = "https://overpass-api.de/api/interpreter",
    private val clock: () -> Instant = { Instant.now() },
) : BuildingDataProvider {
    override val sourceName = "OpenStreetMap (Overpass)"

    override fun obstaclesAround(center: LatLon, radiusM: Int): List<Obstacle> {
        require(radiusM in 10..1000)
        val c = String.format(Locale.ROOT, "%.6f,%.6f", center.lat, center.lon)
        val query = "[out:json][timeout:25];(way[building](around:$radiusM,$c);way[\"building:part\"](around:$radiusM,$c);" +
            "node[natural=tree](around:$radiusM,$c);way[natural=tree_row](around:$radiusM,$c);" +
            "node[man_made~\"^(chimney|mast|tower)$\"](around:$radiusM,$c);node[power~\"^(pole|tower)$\"](around:$radiusM,$c);" +
            "way[barrier~\"^(wall|fence)$\"](around:$radiusM,$c););out geom tags;"
        return parse(http.getText("$base?data=${enc(query)}", userAgent), clock())
    }

    companion object {
        fun parse(body: String, fetchedAt: Instant): List<Obstacle> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("Nieprawidłowa odpowiedź Overpass")
            val elements = root["elements"] as? JsonArray ?: return emptyList()
            return elements.mapNotNull { e -> (e as? JsonObject)?.let { element(it, fetchedAt) } }
        }

        private fun element(o: JsonObject, at: Instant): Obstacle? {
            val type = o.str("type") ?: return null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return null
            val tags = (o["tags"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
            val obstacleType = when {
                tags.containsKey("building") || tags.containsKey("building:part") -> ObstacleType.BUILDING
                tags["natural"] == "tree" || tags["natural"] == "tree_row" -> ObstacleType.TREE
                tags["man_made"] == "chimney" -> ObstacleType.CHIMNEY
                tags["man_made"] == "mast" || tags["man_made"] == "tower" || tags["power"] == "tower" -> ObstacleType.MAST
                tags["power"] == "pole" -> ObstacleType.POLE
                tags["barrier"] == "wall" || tags["barrier"] == "fence" -> ObstacleType.FENCE
                else -> return null
            }
            val shape: ObstacleShape = when (type) {
                "node" -> {
                    val lat = o.num("lat") ?: return null
                    val lon = o.num("lon") ?: return null
                    val radius = tags["diameter_crown"]?.let(::parseMeters)?.div(2) ?: obstacleType.defaultRadiusM
                    ObstacleShape.Point(LatLon(lat, lon), radius)
                }
                "way" -> {
                    val geom = (o["geometry"] as? JsonArray)?.mapNotNull { g ->
                        (g as? JsonObject)?.let { p -> val la = p.num("lat"); val lo = p.num("lon"); if (la != null && lo != null) LatLon(la, lo) else null }
                    } ?: return null
                    if (geom.size < 2) return null
                    val closed = geom.size >= 4 && geom.first() == geom.last()
                    if (closed && obstacleType != ObstacleType.FENCE) ObstacleShape.Polygon(geom.dropLast(1))
                    else ObstacleShape.Line(geom, if (obstacleType == ObstacleType.TREE) 6.0 else 0.3)
                }
                else -> return null
            }
            return Obstacle(
                id = "osm-$type-$id",
                type = obstacleType,
                shape = shape,
                height = heightFromTags(tags),
                name = tags["name"] ?: tags["addr:housenumber"]?.let { "${tags["addr:street"] ?: ""} $it".trim() },
                foliage = if (obstacleType == ObstacleType.TREE) Foliage(
                    transmittanceLeafOn = if (tags["leaf_type"] == "needleleaved") 0.15 else 0.2,
                    transmittanceLeafOff = if (tags["leaf_cycle"] == "evergreen" || tags["leaf_type"] == "needleleaved") 0.15 else 0.7,
                ) else null,
                source = "OpenStreetMap",
                sourceUpdated = at,
            )
        }

        /** Height from OSM tags; never invented – UNKNOWN when no tag is present. */
        fun heightFromTags(tags: Map<String, String>): HeightValue {
            tags["height"]?.let(::parseMeters)?.let { return HeightValue(it, HeightSource.MAP_TAG, uncertaintyM = 1.0, note = "OSM height=${tags["height"]}") }
            val levels = tags["building:levels"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it < 200 }
            if (levels != null) {
                val roofLevels = tags["roof:levels"]?.toDoubleOrNull() ?: 0.0
                val roofHeight = tags["roof:height"]?.let(::parseMeters) ?: roofLevels * 2.5
                return HeightValue.fromLevels(levels, roofM = roofHeight)
            }
            return HeightValue.UNKNOWN
        }

        /** "12", "12 m", "12.5m", "40'" (feet). */
        fun parseMeters(text: String): Double? {
            val t = text.trim().lowercase(Locale.ROOT).replace(',', '.')
            Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(m)?$").matchEntire(t)?.let { return it.groupValues[1].toDouble().takeIf { v -> v in 0.0..1000.0 } }
            Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(ft|')$").matchEntire(t)?.let { return it.groupValues[1].toDouble() * 0.3048 }
            return null
        }
    }
}

/** Terrain samples on a polar grid for the horizon profile. */
object TerrainSampler {
    val DEFAULT_DISTANCES_M = listOf(100.0, 200.0, 400.0, 700.0, 1000.0, 1500.0, 2500.0, 4000.0, 6000.0)

    fun sample(provider: TerrainDataProvider, center: LatLon, azimuthStepDeg: Int = 10, distances: List<Double> = DEFAULT_DISTANCES_M): TerrainSamples {
        val frame = LocalFrame(center)
        val azimuths = (0 until 360 step azimuthStepDeg).map { it.toDouble() }
        val points = listOf(center) + azimuths.flatMap { az ->
            distances.map { d -> frame.toLatLon(Local(d * kotlin.math.sin(Math.toRadians(az)), d * kotlin.math.cos(Math.toRadians(az)))) }
        }
        val elev = provider.elevations(points)
        val origin = elev.firstOrNull() ?: throw IllegalStateException("Brak wysokości terenu w punkcie instalacji")
        val grid = azimuths.indices.map { ai -> distances.indices.map { di -> elev[1 + ai * distances.size + di] } }
        return TerrainSamples(origin, azimuths, distances, grid, provider.sourceName, provider.resolutionM)
    }
}


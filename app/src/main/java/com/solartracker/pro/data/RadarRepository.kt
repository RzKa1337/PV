package com.solartracker.pro.data

import android.content.Context
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.radar.RadarProvider
import com.solartracker.pro.core.radar.RadarSnapshot
import com.solartracker.pro.core.radar.RainViewer
import com.solartracker.pro.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/** RainViewer public radar index (no key). Tiles themselves are loaded by the map view. */
class RainViewerRadarProvider(private val clock: () -> Instant = { Instant.now() }) : RadarProvider {
    override val name = "RainViewer"

    override suspend fun load(): RadarSnapshot = loadRaw().second

    /** Raw JSON (for the offline copy) and the parsed snapshot. */
    suspend fun loadRaw(): Pair<String, RadarSnapshot> = withContext(Dispatchers.IO) {
        val json = download(RainViewer.INDEX_URL)
        json to RainViewer.parse(json, clock())
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android)")
            val code = c.responseCode
            if (code == 429) throw IOException(tr("limit zapytań (429)", "rate limit (429)"))
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

data class RadarResult(val snapshot: RadarSnapshot?, val fromCache: Boolean, val error: String?)

/**
 * Radar index with an on-disk copy: re-read at most every [MIN_INTERVAL] (provider rate limits); the last good index
 * is kept for offline use and marked as such – its age decides LIVE/STALE, it is never shown as current.
 */
class RadarRepository(context: Context, private val provider: RadarProvider = RainViewerRadarProvider(), private val clock: () -> Instant = { Instant.now() }) {
    private val dir = File(context.applicationContext.filesDir, "radar")
    private var memory: RadarSnapshot? = null

    suspend fun load(force: Boolean): RadarResult = withContext(Dispatchers.IO) {
        val cached = memory ?: readCache()
        if (!force && cached != null && Duration.between(cached.fetchedAt, clock()) < MIN_INTERVAL) return@withContext RadarResult(cached, false, null)
        try {
            val fresh = if (provider is RainViewerRadarProvider) {
                val (json, snap) = provider.loadRaw()
                runCatching {
                    dir.mkdirs()
                    File(dir, "index.json").writeText(json)
                    File(dir, "index.meta").writeText(snap.fetchedAt.epochSecond.toString())
                }
                snap
            } else provider.load()
            memory = fresh
            RadarResult(fresh, false, null)
        } catch (e: Exception) {
            val reason = when (e) {
                is java.net.UnknownHostException -> tr("brak internetu", "no internet")
                is java.net.SocketTimeoutException -> tr("przekroczono czas", "timed out")
                is IllegalArgumentException -> tr("nieprawidłowe dane radaru", "malformed radar data")
                else -> e.message ?: e.javaClass.simpleName
            }
            RadarResult(cached, cached != null, tr("Radar niedostępny ($reason)", "Radar unavailable ($reason)") +
                if (cached != null) tr(" – pokazuję zapisane klatki", " – showing saved frames") else "")
        }
    }

    private fun readCache(): RadarSnapshot? = runCatching {
        val fetched = Instant.ofEpochSecond(File(dir, "index.meta").readText().trim().toLong())
        RainViewer.parse(File(dir, "index.json").readText(), fetched)
    }.getOrNull()

    companion object {
        val MIN_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}

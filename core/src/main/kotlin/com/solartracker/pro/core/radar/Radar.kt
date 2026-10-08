package com.solartracker.pro.core.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

/** Past = observed radar mosaic; NOWCAST = provider's extrapolation (only when the provider supplies one). */
enum class RadarFrameKind { PAST, NOWCAST }

/** One radar image set (map tiles) valid at [time]. */
data class RadarFrame(val time: Instant, val kind: RadarFrameKind, val tileUrlTemplate: String)

/**
 * What a radar provider returned: frames (oldest first) plus provenance.
 * @property maxZoom highest zoom level the provider serves tiles for (higher zooms are not requested)
 */
data class RadarSnapshot(
    val provider: String,
    val attribution: String,
    val generatedAt: Instant,
    val fetchedAt: Instant,
    val frames: List<RadarFrame>,
    val maxZoom: Int,
) {
    val latestObserved: RadarFrame? get() = frames.lastOrNull { it.kind == RadarFrameKind.PAST }
}

enum class RadarStatus(val label: String) { LIVE("NA ŻYWO"), STALE("DANE NIEAKTUALNE"), CACHED("Z PAMIĘCI"), UNAVAILABLE("NIEDOSTĘPNY") }

data class RadarFreshness(val status: RadarStatus, val age: Duration?, val latest: Instant?)

/** Radar source; implementations do the networking (app module). Must throw on failure, never invent frames. */
interface RadarProvider {
    val name: String
    suspend fun load(): RadarSnapshot
}

object RadarFreshnessEvaluator {
    /**
     * RainViewer publishes a new mosaic every 10 minutes and frames are typically 5–15 minutes old when fetched; a
     * frame older than [STALE_AFTER] (two missed updates + latency) is marked STALE instead of being shown as current.
     */
    val STALE_AFTER: Duration = Duration.ofMinutes(30)

    fun evaluate(snapshot: RadarSnapshot?, now: Instant, fromCache: Boolean = false): RadarFreshness {
        val latest = snapshot?.latestObserved?.time ?: return RadarFreshness(RadarStatus.UNAVAILABLE, null, null)
        val age = Duration.between(latest, now).coerceAtLeast(Duration.ZERO)
        val status = when {
            age > STALE_AFTER -> RadarStatus.STALE
            fromCache -> RadarStatus.CACHED
            else -> RadarStatus.LIVE
        }
        return RadarFreshness(status, age, latest)
    }

    private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this
}

/**
 * RainViewer public Weather Maps API (https://www.rainviewer.com/api.html): an index of radar frames
 * (`radar.past`, optionally `radar.nowcast`) with a tile host. No key; tiles are requested as
 * `{host}{path}/256/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`. The free tier is limited (zoom, colour schemes, request
 * rate) – the app requests at most zoom [DEFAULT_MAX_ZOOM] and re-reads the index at most every few minutes.
 */
object RainViewer {
    const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val ATTRIBUTION = "Radar: RainViewer.com"
    const val DEFAULT_MAX_ZOOM = 7
    /** Colour scheme 2 ("Universal Blue"), smoothing on, snow colours on. */
    private const val COLOR = 2
    private const val OPTIONS = "1_1"

    /** @throws IllegalArgumentException for malformed data (never returns invented frames). */
    fun parse(json: String, fetchedAt: Instant, maxZoom: Int = DEFAULT_MAX_ZOOM): RadarSnapshot {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: throw IllegalArgumentException("Invalid JSON")
        val host = (root["host"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://") }
            ?: throw IllegalArgumentException("Missing or insecure 'host'")
        val generated = (root["generated"] as? JsonPrimitive)?.longOrNull?.let { Instant.ofEpochSecond(it) }
            ?: throw IllegalArgumentException("Missing 'generated'")
        val radar = root["radar"] as? JsonObject ?: throw IllegalArgumentException("Missing 'radar'")
        fun frames(key: String, kind: RadarFrameKind) = (radar[key] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val time = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("/") && !it.contains("..") } ?: return@mapNotNull null
            RadarFrame(Instant.ofEpochSecond(time), kind, "$host$path/256/{z}/{x}/{y}/$COLOR/$OPTIONS.png")
        }
        val all = (frames("past", RadarFrameKind.PAST) + frames("nowcast", RadarFrameKind.NOWCAST)).sortedBy { it.time }.distinctBy { it.time }
        require(all.any { it.kind == RadarFrameKind.PAST }) { "No radar frames" }
        return RadarSnapshot("RainViewer", ATTRIBUTION, generated, fetchedAt, all, maxZoom)
    }

    /** Tile URL for one frame. */
    fun tileUrl(frame: RadarFrame, zoom: Int, x: Long, y: Long): String =
        frame.tileUrlTemplate.replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}

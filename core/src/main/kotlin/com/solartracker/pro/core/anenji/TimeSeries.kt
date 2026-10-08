package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import java.time.Duration
import java.time.Instant

/** Source of one value (never mix REAL and SIMULATED). */
enum class TelemetrySource(val label: String) {
    ANENJI_REAL("Anenji – pomiar"),
    ANENJI_IMPORTED("Anenji – import"),
    ANENJI_SIMULATED("Anenji – symulator"),
    PV_MODEL("model PV"),
    WEATHER_REAL("pogoda – pomiar"),
    WEATHER_FORECAST("pogoda – prognoza"),
    BATTERY_MODEL("model baterii"),
    USER_INPUT("dane użytkownika"),
    UNKNOWN("nieznane"),
    ;

    companion object {
        fun of(origin: DataOrigin) = when (origin) {
            DataOrigin.DEVICE -> ANENJI_REAL
            DataOrigin.IMPORTED -> ANENJI_IMPORTED
            DataOrigin.SIMULATOR -> ANENJI_SIMULATED
        }
    }
}

enum class SampleQuality { GOOD, SUSPECT, INVALID, MISSING, STALE, UNVERIFIED }

/**
 * Point view of the data ("TelemetrySample"): one parameter at one time with its source, the raw value as found in the
 * source, the normalised value, quality and confidence. [AnalyzerSample] rows stay the working model; this view is used
 * for evidence, raw-data display and export.
 */
data class TelemetrySample(
    val timestamp: Instant,
    val source: TelemetrySource,
    val parameter: Channel,
    val rawValue: String?,
    val value: Double?,
    val unit: String,
    val quality: SampleQuality,
    val confidence: Double,
    val raw: RawRef?,
)

object TelemetryView {
    /**
     * Flattens a row. The register map is not verified on a device, so device values are UNVERIFIED (not GOOD) unless the
     * caller passes channels whose registers were confirmed; validator-rejected channels are INVALID.
     */
    fun points(s: AnalyzerSample, invalid: Set<Channel> = emptySet(), verified: Set<Channel> = emptySet()): List<TelemetrySample> =
        s.values.map { (c, v) ->
            val rawText = s.raw?.fields?.entries?.firstOrNull { (h, _) -> (LogImporter.role(h) as? ColumnRole.Measurement)?.channel == c }?.value
            val q = when {
                c in invalid -> SampleQuality.INVALID
                s.origin == DataOrigin.SIMULATOR -> SampleQuality.UNVERIFIED
                c in verified -> SampleQuality.GOOD
                else -> SampleQuality.UNVERIFIED
            }
            TelemetrySample(s.time, TelemetrySource.of(s.origin), c, rawText ?: v.toString(), v, c.unit, q,
                when (q) { SampleQuality.GOOD -> 0.95; SampleQuality.UNVERIFIED -> 0.7; SampleQuality.SUSPECT -> 0.4; else -> 0.0 }, s.raw)
        }
}

/** Resolutions of the time-series engine. */
enum class Resolution(val label: String, val duration: Duration) {
    M1("1 min", Duration.ofMinutes(1)),
    M5("5 min", Duration.ofMinutes(5)),
    M15("15 min", Duration.ofMinutes(15)),
    H1("1 h", Duration.ofHours(1)),
    H6("6 h", Duration.ofHours(6)),
    D1("1 dzień", Duration.ofDays(1)),
    D7("7 dni", Duration.ofDays(7)),
    D30("30 dni", Duration.ofDays(30)),
    D90("90 dni", Duration.ofDays(90)),
}

/** One resampled bucket – always says how much real data is behind it. Nothing is interpolated. */
data class Bucket(
    val start: Instant,
    val originalSamples: Int,
    val expectedSamples: Int,
    val missingSamples: Int,
    val mean: Double?,
    val min: Double?,
    val max: Double?,
    val interpolated: Boolean = false,
    val aggregated: Boolean,
    /** Share of expected samples present (0 = NO DATA). */
    val confidence: Double,
) {
    val hasData: Boolean get() = originalSamples > 0
}

object TimeSeriesEngine {
    /**
     * Resamples one channel into buckets aligned to the epoch (days in UTC). Empty buckets have mean = null (NO DATA),
     * never a filled-in value.
     */
    fun resample(samples: List<AnalyzerSample>, channel: Channel, resolution: Resolution, from: Instant, to: Instant,
                 sourceInterval: Duration? = AnenjiEventLog.typicalInterval(samples.sortedBy { it.time })): List<Bucket> {
        require(to.isAfter(from))
        val step = resolution.duration.seconds
        val first = from.epochSecond - Math.floorMod(from.epochSecond, step)
        val values = samples.filter { !it.time.isBefore(from) && it.time.isBefore(to) }.mapNotNull { s -> s[channel]?.let { s.time.epochSecond to it } }
            .groupBy { (t, _) -> t - Math.floorMod(t, step) }
        val expected = sourceInterval?.seconds?.takeIf { it > 0 }?.let { (step / it).toInt().coerceAtLeast(1) } ?: 1
        val out = mutableListOf<Bucket>()
        var b = first
        while (b < to.epochSecond) {
            val v = values[b].orEmpty().map { it.second }
            val n = v.size
            out += Bucket(Instant.ofEpochSecond(b), n, expected, (expected - n).coerceAtLeast(0), v.takeIf { n > 0 }?.average(),
                v.minOrNull(), v.maxOrNull(), interpolated = false, aggregated = n > 1, confidence = (n.toDouble() / expected).coerceAtMost(1.0))
            b += step
        }
        return out
    }

    /** Median value at the same local hour over previous days (simple robust seasonal reference). */
    fun hourlyMedian(samples: List<AnalyzerSample>, channel: Channel, zone: java.time.ZoneId): Map<Int, Double> =
        samples.mapNotNull { s -> s[channel]?.let { s.time.atZone(zone).hour to it } }.groupBy({ it.first }, { it.second })
            .mapValues { Stats.median(it.value)!! }
}

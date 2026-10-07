package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

enum class TrendWindow(val label: String, val duration: Duration) {
    H1("1 h", Duration.ofHours(1)),
    H6("6 h", Duration.ofHours(6)),
    H24("24 h", Duration.ofHours(24)),
    D7("7 dni", Duration.ofDays(7)),
    D30("30 dni", Duration.ofDays(30)),
    D90("90 dni", Duration.ofDays(90)),
    Y1("1 rok", Duration.ofDays(365)),
}

enum class TrendDirection(val label: String) { RISING("rośnie"), FALLING("maleje"), STABLE("stabilny"), UNKNOWN("nieokreślony") }

data class ChannelStats(
    val channel: Channel,
    val window: TrendWindow,
    val samples: Int,
    /** Share of the window covered by data (0..1). */
    val coverage: Double,
    val min: Double,
    val max: Double,
    val average: Double,
    val median: Double,
    val p95: Double,
    /** Least-squares change per day in the channel unit; null with too few points. */
    val slopePerDay: Double?,
    val direction: TrendDirection,
    /** Values more than 5 MAD from the median (robust outliers) with their times. */
    val anomalies: List<Pair<Instant, Double>>,
)

/** Statistics per channel and window; a window is reported only with ≥ [MIN_SAMPLES] samples and ≥ 30 % coverage. */
object TrendAnalyzer {
    const val MIN_SAMPLES = 10
    const val MIN_COVERAGE = 0.3

    fun analyze(samples: List<AnalyzerSample>, now: Instant, channels: Collection<Channel> = Channel.entries): List<ChannelStats> {
        val s = samples.sortedBy { it.time }
        val interval = AnenjiEventLog.typicalInterval(s) ?: return emptyList()
        return TrendWindow.entries.flatMap { w ->
            val from = now.minus(w.duration)
            val inWindow = s.filter { !it.time.isBefore(from) && !it.time.isAfter(now) }
            channels.mapNotNull { c ->
                val pts = inWindow.mapNotNull { x -> x[c]?.let { x.time to it } }
                if (pts.size < MIN_SAMPLES) return@mapNotNull null
                val coverage = (pts.size * interval.toMillis().toDouble() / w.duration.toMillis()).coerceAtMost(1.0)
                if (coverage < MIN_COVERAGE) return@mapNotNull null
                stats(c, w, pts, coverage)
            }
        }
    }

    internal fun stats(c: Channel, w: TrendWindow, pts: List<Pair<Instant, Double>>, coverage: Double): ChannelStats {
        val v = pts.map { it.second }
        val median = Stats.median(v)!!
        val mad = Stats.median(v.map { abs(it - median) }) ?: 0.0
        val anomalies = if (mad > 0) pts.filter { abs(it.second - median) > 5 * 1.4826 * mad } else emptyList()
        val t0 = pts.first().first
        val days = pts.map { Duration.between(t0, it.first).toMillis() / 86_400_000.0 }
        val slope = if (days.last() > 0) Stats.slope(days, v) else null
        val spanDays = days.last()
        val range = (v.max() - v.min()).takeIf { it > 0 }
        // A trend counts when the fitted change over the window exceeds 10 % of the observed range.
        val direction = when {
            slope == null || range == null -> TrendDirection.UNKNOWN
            abs(slope * spanDays) < 0.1 * range -> TrendDirection.STABLE
            slope > 0 -> TrendDirection.RISING
            else -> TrendDirection.FALLING
        }
        return ChannelStats(c, w, pts.size, coverage, v.min(), v.max(), v.average(), median, Stats.percentile(v, 95.0)!!, slope, direction, anomalies.take(20))
    }
}

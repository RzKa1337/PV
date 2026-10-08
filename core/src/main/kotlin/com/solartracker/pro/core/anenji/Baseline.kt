package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Typical range of a quantity in comparable conditions (P25–P75 and median) and how good that baseline is. */
data class BaselineValue(
    val channel: Channel,
    val low: Double,
    val median: Double,
    val high: Double,
    val samples: Int,
    /** Which conditions were matched, e.g. "podobne nasłonecznienie ±15 %, wysokość słońca ±5°, ±45 dni". */
    val matchedOn: String,
    /** 0..1 – sample count and how strict the match was. */
    val quality: Double,
) {
    fun deviation(observed: Double): Double? = if (abs(median) > 1e-9) (observed - median) / median else null
}

/**
 * "SystemBaselineEngine": learns the installation's normal behaviour from its own history. Comparisons are made in
 * similar conditions only (never a winter day against a summer day): PV by modelled irradiance (expected PV) and sun
 * elevation within ±45 days; other channels by local hour within ±30 days. The query's own day is excluded.
 */
class SystemBaselineEngine(samples: List<AnalyzerSample>, private val zone: ZoneId, private val context: ContextProvider?) {
    private val history = samples.filter { it.origin != DataOrigin.SIMULATOR || samples.all { s -> s.origin == DataOrigin.SIMULATOR } }.sortedBy { it.time }

    private data class PvPoint(val time: Instant, val actual: Double, val expected: Double, val elevation: Double)

    private val pv: List<PvPoint> by lazy {
        val c = context ?: return@lazy emptyList()
        history.mapNotNull { s ->
            val p = s[Channel.PV_POWER] ?: return@mapNotNull null
            val ctx = c.at(s.time) ?: return@mapNotNull null
            val e = ctx.expectedPvW ?: return@mapNotNull null
            val el = ctx.sunElevationDeg ?: return@mapNotNull null
            if (el < 5 || e < 50) null else PvPoint(s.time, p, e, el)
        }
    }

    /** Index by expected-power band so a query does not scan the whole history (90 days × 5 min). */
    private val pvByBand: Map<Int, List<PvPoint>> by lazy { pv.groupBy { (it.expected / BAND).toInt() } }
    private val byHour: Map<Int, List<AnalyzerSample>> by lazy { history.groupBy { it.time.atZone(zone).hour } }

    /** Typical PV for the conditions at [time] (modelled irradiance ±15 %, elevation ±5°, ±45 days); null = INSUFFICIENT DATA. */
    fun pvAt(time: Instant): BaselineValue? {
        val ctx = context?.at(time) ?: return null
        val e = ctx.expectedPvW ?: return null
        val el = ctx.sunElevationDeg ?: return null
        if (e < 50 || el < 5) return null
        val day = time.atZone(zone).toLocalDate()
        fun pick(tol: Double, elTol: Double, days: Long) = ((e * (1 - tol) / BAND).toInt()..(e * (1 + tol) / BAND).toInt() + 1)
            .flatMap { pvByBand[it].orEmpty() }.filter { p ->
                abs(p.expected - e) <= e * tol && abs(p.elevation - el) <= elTol &&
                    abs(Duration.between(p.time, time).toDays()) <= days && p.time.atZone(zone).toLocalDate() != day
            }
        val strict = pick(0.15, 5.0, 45)
        val (set, matched, strictness) = when {
            strict.size >= MIN_SAMPLES -> Triple(strict, "podobne nasłonecznienie ±15%, wysokość słońca ±5°, ±45 dni", 1.0)
            else -> pick(0.25, 10.0, 90).let { Triple(it, "podobne nasłonecznienie ±25%, wysokość słońca ±10°, ±90 dni", 0.7) }
        }
        if (set.size < MIN_SAMPLES) return null
        // Scale each historical point to today's expectation: the baseline is "how this installation performs vs the model".
        val scaled = set.map { it.actual / it.expected * e }
        return value(Channel.PV_POWER, scaled, matched, strictness)
    }

    /** Typical value of [channel] at the same local hour (±30 days, own day excluded). */
    fun hourly(channel: Channel, time: Instant): BaselineValue? {
        val z = time.atZone(zone)
        val set = byHour[z.hour].orEmpty().filter { s ->
            abs(Duration.between(s.time, time).toDays()) <= 30 && s.time.atZone(zone).toLocalDate() != z.toLocalDate()
        }.mapNotNull { it[channel] }
        if (set.size < MIN_SAMPLES) return null
        return value(channel, set, "ta sama godzina (${z.hour}:00), ±30 dni", 0.8)
    }

    /** Typical daily energy of a power channel [kWh] over previous days (±30 days). */
    fun dailyEnergy(channel: Channel, day: java.time.LocalDate): BaselineValue? {
        val byDay = history.groupBy { it.time.atZone(zone).toLocalDate() }
            .filterKeys { it != day && abs(java.time.temporal.ChronoUnit.DAYS.between(it, day)) <= 30 }
            .mapValues { (_, s) -> energyKwh(s, channel) }
            .filterValues { it > 0 }
        if (byDay.size < 3) return null
        return value(channel, byDay.values.toList(), "energia dobowa z ${byDay.size} dni (±30 dni)", (byDay.size / 14.0).coerceAtMost(1.0))
    }

    private fun value(c: Channel, v: List<Double>, matched: String, strictness: Double) = BaselineValue(
        c, Stats.percentile(v, 25.0)!!, Stats.median(v)!!, Stats.percentile(v, 75.0)!!, v.size, matched,
        (strictness * (v.size / 50.0).coerceAtMost(1.0)).coerceIn(0.1, 1.0),
    )

    companion object {
        const val MIN_SAMPLES = 10
        private const val BAND = 50.0

        fun energyKwh(s: List<AnalyzerSample>, c: Channel): Double = s.sortedBy { it.time }.zipWithNext().sumOf { (a, b) ->
            val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            if (dt <= 0 || dt > 0.25) 0.0 else (a[c] ?: 0.0).coerceAtLeast(0.0) * dt / 1000
        }
    }
}

/**
 * Explicit confidence: every factor is 0..1 and shown to the user; the result is their weighted geometric mean (one weak
 * factor pulls the result down, as it should).
 */
data class ConfidenceBreakdown(
    val dataQuality: Double,
    val sampleCount: Double,
    val sourceReliability: Double,
    val agreement: Double,
    val baselineQuality: Double?,
    val modelCertainty: Double?,
) {
    val value: Double get() {
        val parts = listOfNotNull(dataQuality to 1.0, sampleCount to 1.0, sourceReliability to 1.0, agreement to 1.5,
            baselineQuality?.let { it to 1.0 }, modelCertainty?.let { it to 0.8 })
        val w = parts.sumOf { it.second }
        return exp(parts.sumOf { (x, wt) -> ln(x.coerceIn(0.01, 1.0)) * wt } / w)
    }

    fun describe(): List<String> = listOfNotNull(
        "Jakość danych: ${pct(dataQuality)}", "Liczba próbek: ${pct(sampleCount)}", "Wiarygodność źródła: ${pct(sourceReliability)}",
        "Zgodność niezależnych sygnałów: ${pct(agreement)}", baselineQuality?.let { "Baseline historyczny: ${pct(it)}" },
        modelCertainty?.let { "Pewność modelu: ${pct(it)}" },
    )

    private fun pct(x: Double) = "${(x * 100).toInt()}%"
}

object ConfidenceEngine {
    /** Source reliability: the register map is unverified, so device data is capped below 1. Simulator data counts low. */
    fun sourceReliability(origin: DataOrigin, verifiedRegisters: Boolean = false): Double = when (origin) {
        DataOrigin.DEVICE -> if (verifiedRegisters) 0.95 else 0.8
        DataOrigin.IMPORTED -> 0.75
        DataOrigin.SIMULATOR -> 0.3
    }

    /** n of needed samples → 0..1 (saturating). */
    fun sampleScore(n: Int, needed: Int): Double = (n.toDouble() / needed).coerceIn(0.05, 1.0)

    /** Share of supporting signals among those that could be judged (supporting vs contradicting). */
    fun agreement(supporting: Int, contradicting: Int): Double =
        if (supporting + contradicting == 0) 0.3 else ((supporting + 0.5) / (supporting + contradicting + 1.0)).coerceIn(0.05, 1.0)
}

package com.solartracker.pro.core.analytics

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** One usable comparison point: measured and modelled PV power at the same time. */
data class CalibrationSample(val time: Instant, val realKw: Double, val modelKw: Double) {
    val ratio: Double get() = realKw / modelKw
}

/**
 * Learned correction of the PV model for this installation (real / model).
 * Not applied before [ready]; then [factor] is used and [confidence] shown.
 */
data class CalibrationResult(
    val factor: Double,
    val confidence: Double,
    val samples: Int,
    val rejectedOutliers: Int,
    val days: Int,
    val ready: Boolean,
    val reason: String,
)

/**
 * Learns the ratio between real Anenji PV power and the model gradually: only good samples (sun
 * high, no curtailment, model output significant), rolling window, median with MAD outlier rejection,
 * minimum number of samples and days. A single reading never changes the model.
 */
class CalibrationEngine(
    private val window: Duration = Duration.ofDays(30),
    private val minSamples: Int = 60,
    private val minDays: Int = 3,
    private val minModelFraction: Double = 0.15,
) {
    private val samples = ArrayDeque<CalibrationSample>()

    /** Offers a point; returns false when it is not suitable for calibration. */
    fun offer(time: Instant, realKw: Double, modelKw: Double, peakKw: Double, sunElevationDeg: Double, curtailed: Boolean): Boolean {
        if (curtailed || sunElevationDeg < 15 || modelKw < peakKw * minModelFraction || realKw < 0) return false
        if (!realKw.isFinite() || !modelKw.isFinite()) return false
        samples.addLast(CalibrationSample(time, realKw, modelKw))
        val cutoff = time.minus(window)
        while (samples.isNotEmpty() && samples.first().time.isBefore(cutoff)) samples.removeFirst()
        return true
    }

    fun load(saved: List<CalibrationSample>) {
        samples.clear()
        saved.sortedBy { it.time }.forEach { samples.addLast(it) }
    }

    fun samples(): List<CalibrationSample> = samples.toList()

    fun result(): CalibrationResult {
        val ratios = samples.map { it.ratio }
        val days = samples.map { it.time.epochSecond / 86_400 }.toSet().size
        if (ratios.isEmpty()) return CalibrationResult(1.0, 0.0, 0, 0, 0, false, "Brak danych – połącz falownik")
        val med = median(ratios)
        val mad = median(ratios.map { abs(it - med) }).coerceAtLeast(0.01)
        val kept = ratios.filter { abs(it - med) <= 3.0 * 1.4826 * mad }
        val factor = median(kept).coerceIn(0.4, 1.3)
        val spread = (median(kept.map { abs(it - factor) }) * 1.4826) / factor
        val confidence = ((kept.size.toDouble() / minSamples).coerceAtMost(1.0) * (days.toDouble() / minDays).coerceAtMost(1.0) *
            (1.0 - spread * 2).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val ready = kept.size >= minSamples && days >= minDays
        val reason = when {
            !ready -> "Uczenie: ${kept.size}/$minSamples próbek, $days/$minDays dni"
            confidence < 0.5 -> "Duży rozrzut pomiarów – korekta stosowana ostrożnie"
            else -> "Korekta modelu wyuczona z ${kept.size} pomiarów"
        }
        return CalibrationResult(factor, confidence, kept.size, ratios.size - kept.size, days, ready, reason)
    }

    /** The factor to apply: blends towards 1.0 with low confidence; 1.0 until ready. */
    fun appliedFactor(): Double {
        val r = result()
        return if (!r.ready) 1.0 else 1.0 + (r.factor - 1.0) * r.confidence
    }

    companion object {
        fun median(values: List<Double>): Double {
            require(values.isNotEmpty())
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
    }
}

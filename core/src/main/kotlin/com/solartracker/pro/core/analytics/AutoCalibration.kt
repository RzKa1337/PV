package com.solartracker.pro.core.analytics


import java.time.Instant
import kotlin.math.abs


/** One sample used for calibration, with the context needed to separate effects. */
data class CalibrationObservation(
    val time: Instant,
    val realKw: Double,
    val modelKw: Double,
    val cellTemperatureC: Double?,
    /** Model output was near the inverter/MPPT limit (excluded from temperature/soiling analysis). */
    val nearLimit: Boolean = false,
) {
    val ratio: Double get() = realKw / modelKw
}

enum class CorrectionKind(val label: String) {
    YIELD("Ogólny uzysk instalacji (real / model)"),
    TEMPERATURE_COEFFICIENT("Korekta współczynnika temperaturowego"),
    SOILING_TREND("Trend spadku uzysku (możliwe zabrudzenie / degradacja)"),
    FORECAST_BIAS("Korekta błędu systematycznego prognozy"),
    CLIPPING("Udział czasu z ograniczeniem mocy falownika"),
}

/** A learned correction with full provenance. Only [enabled] and [ready] corrections are applied. */
data class Correction(
    val kind: CorrectionKind,
    val value: Double,
    val unit: String,
    val source: String,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val samples: Int,
    val confidence: Double,
    val ready: Boolean,
    val enabled: Boolean,
    val note: String,
)

data class CorrectionChange(val time: Instant, val kind: CorrectionKind, val from: Double?, val to: Double, val reason: String)

/**
 * Statistical (no ML) self-calibration from MODEL vs REAL vs FORECAST:
 * - yield factor: median ratio with MAD outlier rejection (via [CalibrationEngine]);
 * - temperature coefficient: least-squares slope of ratio vs cell temperature (needs ≥ 10 °C spread);
 * - soiling/degradation trend: slope of daily median ratio over ≥ 14 days (reported, not auto-applied);
 * - forecast bias: from [ForecastAccuracy];
 * - clipping share: fraction of samples at the inverter limit (reported).
 * Each correction keeps source, period, sample count, confidence, enabled flag and change history.
 */
class AutoCalibrationEngine(
    private val disabled: Set<CorrectionKind> = emptySet(),
    private val minSamples: Int = 60,
) {
    private val history = mutableListOf<CorrectionChange>()
    private val last = mutableMapOf<CorrectionKind, Double>()

    fun history(): List<CorrectionChange> = history.toList()

    fun restoreHistory(changes: List<CorrectionChange>) {
        history.clear(); history += changes
        changes.forEach { last[it.kind] = it.to }
    }

    fun evaluate(observations: List<CalibrationObservation>, forecast: AccuracyReport?, now: Instant): List<Correction> {
        val obs = observations.filter { it.modelKw > 0.05 && it.realKw >= 0 && it.ratio.isFinite() }
        val start = obs.minOfOrNull { it.time }
        val end = obs.maxOfOrNull { it.time }
        val days = obs.map { it.time.epochSecond / 86_400 }.toSet().size
        val out = mutableListOf<Correction>()

        // Yield
        val unclipped = obs.filter { !it.nearLimit }
        val engine = CalibrationEngine(minSamples = minSamples, minDays = 3).apply {
            load(unclipped.map { CalibrationSample(it.time, it.realKw, it.modelKw) })
        }
        val y = engine.result()
        out += correction(CorrectionKind.YIELD, y.factor, "×", y.samples, y.confidence, y.ready, start, end, y.reason, now)

        // Temperature coefficient (slope of ratio vs cell temperature, normalised by the median ratio).
        val withTemp = unclipped.filter { it.cellTemperatureC != null }
        val spread = (withTemp.maxOfOrNull { it.cellTemperatureC!! } ?: 0.0) - (withTemp.minOfOrNull { it.cellTemperatureC!! } ?: 0.0)
        if (withTemp.size >= 10) {
            val xs = withTemp.map { it.cellTemperatureC!! - 25.0 }
            val ys = withTemp.map { it.ratio }
            val fit = linearFit(xs, ys)
            val median = CalibrationEngine.median(ys).coerceAtLeast(0.1)
            val slopePerC = fit.slope / median // relative change per °C beyond the model's coefficient
            val ready = withTemp.size >= minSamples && spread >= 10.0 && fit.r2 >= 0.2
            val conf = ((withTemp.size.toDouble() / minSamples).coerceAtMost(1.0) * (spread / 20.0).coerceAtMost(1.0) * fit.r2).coerceIn(0.0, 1.0)
            out += correction(CorrectionKind.TEMPERATURE_COEFFICIENT, slopePerC * 100, "%/°C", withTemp.size, conf, ready, start, end,
                if (ready) "Dodatkowy wpływ temperatury ${"%+.2f".format(slopePerC * 100)} %/°C (R²=${"%.2f".format(fit.r2)})"
                else "Za mało danych: ${withTemp.size}/$minSamples próbek, rozrzut ${spread.toInt()}/10 °C", now)
        }

        // Trend of daily median ratios (soiling / degradation).
        val daily = unclipped.groupBy { it.time.epochSecond / 86_400 }.filterValues { it.size >= 5 }
            .map { (day, list) -> day.toDouble() to CalibrationEngine.median(list.map { it.ratio }) }.sortedBy { it.first }
        if (daily.size >= 3) {
            val fit = linearFit(daily.map { it.first }, daily.map { it.second })
            val perMonth = fit.slope * 30 * 100
            val ready = daily.size >= 14 && fit.r2 >= 0.3 && perMonth < -0.5
            out += correction(CorrectionKind.SOILING_TREND, perMonth, "%/30 dni", daily.size, (daily.size / 30.0).coerceAtMost(1.0) * fit.r2, ready,
                start, end, if (ready) "Uzysk spada o ${"%.1f".format(-perMonth)}% na miesiąc – sprawdź zabrudzenie paneli" else "Brak istotnego trendu (dni: ${daily.size})", now,
                autoApply = false)
        }

        // Forecast bias
        if (forecast != null && forecast.biasPercent != null && forecast.count > 0) {
            val ready = forecast.count >= 48
            val factor = 1.0 / (1.0 + forecast.biasPercent / 100.0)
            out += correction(CorrectionKind.FORECAST_BIAS, factor, "×", forecast.count, (forecast.count / 168.0).coerceAtMost(1.0), ready, start, end,
                forecast.describe(), now)
        }

        // Clipping share
        if (obs.isNotEmpty()) {
            val share = obs.count { it.nearLimit }.toDouble() / obs.size * 100
            out += correction(CorrectionKind.CLIPPING, share, "% próbek", obs.size, (obs.size / 200.0).coerceAtMost(1.0), obs.size >= 50, start, end,
                if (share > 5) "Falownik ogranicza moc przez ${"%.0f".format(share)}% czasu produkcji" else "Clipping marginalny", now, autoApply = false)
        }
        if (days == 0) return out
        return out
    }

    private fun correction(
        kind: CorrectionKind, value: Double, unit: String, samples: Int, confidence: Double, ready: Boolean,
        start: Instant?, end: Instant?, note: String, now: Instant, autoApply: Boolean = true,
    ): Correction {
        val enabled = kind !in disabled && autoApply
        if (ready && enabled) {
            val prev = last[kind]
            if (prev == null || abs(prev - value) > abs(prev) * 0.01 + 1e-6) {
                history += CorrectionChange(now, kind, prev, value, note)
                last[kind] = value
            }
        }
        return Correction(kind, value, unit, "pomiary falownika vs model", start, end, samples, confidence.coerceIn(0.0, 1.0), ready, enabled, note)
    }

    data class Fit(val slope: Double, val intercept: Double, val r2: Double)

    companion object {
        fun linearFit(xs: List<Double>, ys: List<Double>): Fit {
            require(xs.size == ys.size && xs.size >= 2)
            val mx = xs.average()
            val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx == 0.0) return Fit(0.0, my, 0.0)
            val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
            val slope = sxy / sxx
            val intercept = my - slope * mx
            val ssTot = ys.sumOf { (it - my) * (it - my) }
            val ssRes = xs.indices.sumOf { val e = ys[it] - (intercept + slope * xs[it]); e * e }
            val r2 = if (ssTot == 0.0) 0.0 else (1 - ssRes / ssTot).coerceIn(0.0, 1.0)
            return Fit(slope, intercept, r2)
        }

    }
}


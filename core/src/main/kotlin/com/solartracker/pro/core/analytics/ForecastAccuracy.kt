package com.solartracker.pro.core.analytics

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** A forecast and the value that actually happened for the same interval (e.g. hourly kWh). */
data class ForecastPair(val time: Instant, val forecast: Double, val actual: Double)

data class AccuracyReport(
    val count: Int,
    val mae: Double,
    val rmse: Double,
    /** Mean absolute percentage error over intervals with meaningful production [%]; null if none. */
    val mapePercent: Double?,
    /** (Σforecast − Σactual) / Σactual [%]: + = forecast overestimates. */
    val biasPercent: Double?,
    /** 100 − weighted MAPE (Σ|f−a| / Σa), clamped to 0..100 [%]. */
    val accuracyPercent: Double?,
) {
    /** Human sentence, only when there is enough data. */
    fun describe(): String = when {
        count == 0 || accuracyPercent == null -> "Za mało danych do oceny prognozy"
        biasPercent == null || abs(biasPercent) < 1.0 -> "Trafność prognozy: ${accuracyPercent.toInt()}% (bez wyraźnego błędu systematycznego)"
        biasPercent > 0 -> "Trafność prognozy: ${accuracyPercent.toInt()}%. Prognoza zawyża średnio o ${"%.1f".format(biasPercent)}%."
        else -> "Trafność prognozy: ${accuracyPercent.toInt()}%. Prognoza zaniża średnio o ${"%.1f".format(-biasPercent)}%."
    }
}

/** Forecast vs reality: MAE, RMSE, MAPE, bias and accuracy. */
object ForecastAccuracy {
    fun evaluate(pairs: List<ForecastPair>, minActualForMape: Double = 0.05): AccuracyReport {
        val p = pairs.filter { it.forecast.isFinite() && it.actual.isFinite() && it.actual >= 0 && it.forecast >= 0 }
        if (p.isEmpty()) return AccuracyReport(0, 0.0, 0.0, null, null, null)
        val errors = p.map { it.forecast - it.actual }
        val mae = errors.map { abs(it) }.average()
        val rmse = sqrt(errors.map { it * it }.average())
        val mapeSet = p.filter { it.actual >= minActualForMape }
        val mape = mapeSet.takeIf { it.isNotEmpty() }?.map { abs(it.forecast - it.actual) / it.actual * 100 }?.average()
        val sumActual = p.sumOf { it.actual }
        val bias = if (sumActual > 0) (p.sumOf { it.forecast } - sumActual) / sumActual * 100 else null
        val accuracy = if (sumActual > 0) (100 - p.sumOf { abs(it.forecast - it.actual) } / sumActual * 100).coerceIn(0.0, 100.0) else null
        return AccuracyReport(p.size, mae, rmse, mape, bias, accuracy)
    }

    /**
     * Pairs stored hourly PV forecasts [kWh] (keyed by hour start) with measured energy from history rows.
     * Hours whose recorded time covers less than [minCoverage] of the hour are skipped, so link gaps
     * are never counted as zero production.
     */
    fun pairHourly(forecasts: Map<Instant, Double>, samples: List<HistorySample>, minCoverage: Double = 0.9): List<ForecastPair> {
        val byHour = samples.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }
        return forecasts.entries.sortedBy { it.key }.mapNotNull { (hour, forecast) ->
            val rows = byHour[hour] ?: return@mapNotNull null
            val covered = rows.sumOf { it.duration.seconds } / 3600.0
            if (covered < minCoverage) null else ForecastPair(hour, forecast, rows.sumOf { it.pvEnergyKwh })
        }
    }
}

/** Which stored forecast is compared: issued shortly before the hour, or the previous day. */
enum class ForecastHorizon(val label: String) { HOUR_AHEAD("na godzinę naprzód"), DAY_AHEAD("na dzień naprzód") }

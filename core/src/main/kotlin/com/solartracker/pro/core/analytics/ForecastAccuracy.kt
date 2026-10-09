package com.solartracker.pro.core.analytics

import java.time.Instant
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
    /** Coefficient of determination; null with < 3 pairs or no variance in the actual values. */
    val r2: Double? = null,
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
        val meanActual = p.map { it.actual }.average()
        val ssTot = p.sumOf { (it.actual - meanActual) * (it.actual - meanActual) }
        val r2 = if (p.size >= 3 && ssTot > 1e-9) 1 - errors.sumOf { it * it } / ssTot else null
        return AccuracyReport(p.size, mae, rmse, mape, bias, accuracy, r2)
    }

    /**
     * Pairs stored hourly PV forecasts [kWh] (keyed by hour start) with measured energy from history rows.
     * Hours whose recorded time covers less than [minCoverage] of the hour are skipped, so link gaps
     * are never counted as zero production.
     */
    fun pairHourly(forecasts: Map<Instant, Double>, samples: List<HistorySample>, minCoverage: Double = 0.9): List<ForecastPair> {
        // Rows are attributed to the forecast hour [key, key + 1 h) – the keys are LOCAL hour starts, which are not UTC
        // hour starts in zones with a 30/45-minute offset, so the rows are not grouped by UTC-truncated hours.
        val sorted = samples.sortedBy { it.start }
        val starts = sorted.map { it.start }
        return forecasts.entries.sortedBy { it.key }.mapNotNull { (hour, forecast) ->
            val end = hour.plus(java.time.Duration.ofHours(1))
            var lo = starts.binarySearch(hour).let { if (it < 0) -it - 1 else it }
            while (lo > 0 && starts[lo - 1] == hour) lo--
            val rows = ArrayList<HistorySample>()
            while (lo < sorted.size && sorted[lo].start.isBefore(end)) rows += sorted[lo++]
            if (rows.isEmpty()) return@mapNotNull null
            val covered = rows.sumOf { it.duration.seconds } / 3600.0
            if (covered < minCoverage) null else ForecastPair(hour, forecast, rows.sumOf { it.pvEnergyKwh })
        }
    }
}

/**
 * Which stored forecast is compared. MIN5/MIN15 store PV power [kW] at a 5-minute target time;
 * HOUR_AHEAD/DAY_AHEAD store energy [kWh] for the hour starting at the key.
 */
enum class ForecastHorizon(val label: String, val power: Boolean) {
    MIN5("5 min naprzód", true),
    MIN15("15 min naprzód", true),
    HOUR_AHEAD("na godzinę naprzód", false),
    DAY_AHEAD("na dzień naprzód", false),
}

/** Period over which hourly pairs are summed before comparing (day, week, month). */
enum class AccuracyPeriod(val label: String) { DAY("dzień"), WEEK("tydzień"), MONTH("miesiąc") }

/** Short-horizon and aggregated comparisons (forecast vs actual). */
object ForecastComparison {
    /**
     * Pairs point power forecasts [kW] with the measured mean power in ±[halfWindow] around each target.
     * Requires ≥ 80% of the window to be covered by history rows.
     */
    fun pairPower(forecasts: Map<Instant, Double>, samples: List<HistorySample>, halfWindow: java.time.Duration = java.time.Duration.ofMinutes(2)): List<ForecastPair> {
        val sorted = samples.sortedBy { it.start }
        return forecasts.entries.sortedBy { it.key }.mapNotNull { (target, forecast) ->
            val from = target.minus(halfWindow)
            val to = target.plus(halfWindow)
            var covered = 0.0
            var energyWs = 0.0
            for (r in sorted) {
                if (!r.end.isAfter(from)) continue
                if (!r.start.isBefore(to)) break
                val a = maxOf(r.start, from)
                val b = minOf(r.end, to)
                val sec = java.time.Duration.between(a, b).toMillis() / 1000.0
                val w = r.pvW ?: continue
                covered += sec
                energyWs += w * sec
            }
            val window = halfWindow.toMillis() * 2 / 1000.0
            if (covered < window * 0.8) null else ForecastPair(target, forecast, energyWs / covered / 1000.0)
        }
    }

    /** Sums hourly energy pairs per day/week/month (only complete hours were paired). */
    fun aggregate(hourly: List<ForecastPair>, period: AccuracyPeriod, zone: java.time.ZoneId): List<ForecastPair> =
        hourly.groupBy { p ->
            val d = p.time.atZone(zone).toLocalDate()
            when (period) {
                AccuracyPeriod.DAY -> d
                AccuracyPeriod.WEEK -> d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                AccuracyPeriod.MONTH -> d.withDayOfMonth(1)
            }
        }.toSortedMap().map { (start, list) -> ForecastPair(start.atStartOfDay(zone).toInstant(), list.sumOf { it.forecast }, list.sumOf { it.actual }) }

    /** Relative error of a live comparison: (actual − forecast) / forecast; null when the forecast is ~0. */
    fun relativeError(forecast: Double, actual: Double, minForecast: Double = 0.05): Double? =
        if (forecast < minForecast) null else (actual - forecast) / forecast
}

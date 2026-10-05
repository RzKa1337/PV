package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayProductionForecast(
    val date: LocalDate,
    /** Whole-day expectation [kWh] = produced so far (measured) + remaining (forecast). */
    val expectedKwh: Double,
    val producedKwh: Double?,
    val producedKind: DataKind,
    val remainingKwh: Double,
    val shadingLossKwh: Double,
    val minKwh: Double,
    val maxKwh: Double,
    val confidence: Double,
)

data class ShortTermForecast(val horizon: ShortHorizon, val point: PvForecastPoint) {
    /** Decimals justified by the uncertainty (no false precision). */
    val decimals: Int get() = if (point.maxKw - point.minKw > 1.0) 1 else 2
}

/**
 * Central forecast: PV (incl. shading and calibration) + load + battery + grid over time.
 */
class EnergyForecastEngine(
    private val pv: PredictivePvEngine,
    private val load: LoadForecaster,
    private val battery: BatteryPredictor?,
    private val zone: ZoneId,
    private val step: Duration = Duration.ofMinutes(15),
) {
    fun day(date: LocalDate, now: Instant, producedSoFarKwh: Double?, nowcastRatio: Double? = null): DayProductionForecast {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val from = if (producedSoFarKwh != null && now.isAfter(start)) now.coerceAtMost(end) else start
        val points = generateSequence(from) { it.plus(step) }.takeWhile { it.isBefore(end) }.map { pv.at(it, now, nowcastRatio) }.toList()
        val h = step.seconds / 3600.0
        val remaining = points.sumOf { it.expectedKw } * h
        val produced = if (from == start) null else producedSoFarKwh
        val expected = (produced ?: 0.0) + remaining
        return DayProductionForecast(
            date = date,
            expectedKwh = expected,
            producedKwh = produced,
            producedKind = if (produced != null) DataKind.MEASURED else DataKind.UNAVAILABLE,
            remainingKwh = remaining,
            shadingLossKwh = points.sumOf { it.shadingLossKw } * h,
            minKwh = (produced ?: 0.0) + points.sumOf { it.minKw } * h,
            maxKwh = (produced ?: 0.0) + points.sumOf { it.maxKw } * h,
            confidence = weightedConfidence(points),
        )
    }

    fun shortTerm(now: Instant, nowcastRatio: Double?): List<ShortTermForecast> =
        ShortHorizon.entries.map { ShortTermForecast(it, pv.at(now.plus(Duration.ofMinutes(it.minutes)), now, nowcastRatio)) }

    fun battery(now: Instant, socPercent: Double, kind: DataKind, nowcastRatio: Double?, horizon: Duration = Duration.ofHours(24)): BatteryPrediction? =
        battery?.predict(now, socPercent, kind, { pv.at(it, now, nowcastRatio) }, { load.at(it) }, horizon, step)

    /** Hourly rows without a battery model (PV, shading, load, grid balance). */
    fun timeline(now: Instant, hours: Int, nowcastRatio: Double?): List<EnergyForecastRow> =
        (0..hours).map { i ->
            val t = now.plus(Duration.ofHours(i.toLong()))
            val p = pv.at(t, now, nowcastRatio)
            val l = load.at(t)
            EnergyForecastRow(t, p.expectedKw, p.shadingLossKw, l.kw, 0.0, l.kw - p.expectedKw, null)
        }

    private fun weightedConfidence(points: List<PvForecastPoint>): Double {
        val w = points.sumOf { it.expectedKw }
        return if (w <= 0) points.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0
        else points.sumOf { it.confidence * it.expectedKw } / w
    }
}

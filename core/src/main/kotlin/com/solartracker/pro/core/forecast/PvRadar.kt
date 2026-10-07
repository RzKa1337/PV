package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant

/** One horizon of the PV radar. */
data class RadarPoint(
    val horizon: ShortHorizon,
    val time: Instant,
    val expectedKw: Double,
    val minKw: Double,
    val maxKw: Double,
    val confidence: Double,
    val kind: DataKind,
    /** Power lost to clouds vs a clear sky at that time [%]; null when the sun is too low to tell. */
    val cloudImpactPercent: Double?,
    /** Expected energy from now until this horizon [kWh]. */
    val energyKwh: Double,
)

/** A forecast sharp drop of PV caused by clouds (not by the sun going down). */
data class CloudEvent(
    val at: Instant,
    val minutesAhead: Long,
    val currentKw: Double,
    val expectedMinKw: Double,
    val dropPercent: Double,
    val confidence: Double,
    /** Timing resolution of the weather data behind the event [min] (Open-Meteo hourly = 60). */
    val resolutionMinutes: Int,
)

data class PvRadar(val points: List<RadarPoint>, val cloudEvent: CloudEvent?, val resolutionMinutes: Int, val basis: String)

/**
 * PV radar / nowcast built only from the existing [PredictivePvEngine] (weather forecast + calibration +
 * shading + live nowcast) and the same engine on a clear sky ([clearSky], no weather) for the cloud impact.
 * No synthetic cloud data: event timing is limited by the hourly weather resolution and says so.
 */
class PvRadarBuilder(
    private val pv: PredictivePvEngine,
    private val clearSky: PredictivePvEngine,
    private val peakKw: Double,
    private val resolutionMinutes: Int = 60,
) {
    fun build(now: Instant, nowcastRatio: Double?, currentKw: Double?, step: Duration = Duration.ofMinutes(5)): PvRadar {
        val horizons = ShortHorizon.entries
        val end = now.plus(Duration.ofMinutes(horizons.maxOf { it.minutes }))
        val series = generateSequence(now) { it.plus(step) }.takeWhile { !it.isAfter(end) }.map { it to pv.at(it, now, nowcastRatio) }.toList()
        val h = step.seconds / 3600.0
        val cumulative = DoubleArray(series.size)
        for (i in 1 until series.size) cumulative[i] = cumulative[i - 1] + (series[i - 1].second.expectedKw + series[i].second.expectedKw) / 2 * h
        val points = horizons.map { hz ->
            val t = now.plus(Duration.ofMinutes(hz.minutes))
            val idx = series.indexOfFirst { !it.first.isBefore(t) }.coerceAtLeast(0)
            val p = series[idx].second
            RadarPoint(hz, t, p.expectedKw, p.minKw, p.maxKw, p.confidence, p.kind, cloudImpact(t, p, now), cumulative[idx])
        }
        return PvRadar(points, detectEvent(now, nowcastRatio, currentKw, series), resolutionMinutes,
            "prognoza Open-Meteo (rozdzielczość ${resolutionMinutes} min) + kalibracja + bieżący pomiar")
    }

    /** 1 − (unshaded forecast / unshaded clear sky); null in low sun. */
    private fun cloudImpact(t: Instant, p: PvForecastPoint, now: Instant): Double? {
        val clear = clearSky.at(t, now).unshadedKw
        if (clear < peakKw * MIN_SHARE) return null
        return ((1 - p.unshadedKw / clear) * 100).coerceIn(0.0, 100.0)
    }

    /**
     * A cloud event = the clouds' share of the loss jumps (cloud factor falls ≥ [MIN_DROP]) and the power itself
     * falls ≥ [MIN_DROP] while the clear sky would still give enough – so sunset never counts as a cloud event.
     */
    private fun detectEvent(now: Instant, nowcastRatio: Double?, currentKw: Double?, series: List<Pair<Instant, PvForecastPoint>>): CloudEvent? {
        if (series.isEmpty()) return null
        val nowPoint = series.first().second
        val current = currentKw ?: nowPoint.expectedKw
        if (current < peakKw * MIN_SHARE) return null
        fun factor(t: Instant, p: PvForecastPoint): Double? {
            val clear = clearSky.at(t, now).unshadedKw
            return if (clear < peakKw * MIN_SHARE) null else p.unshadedKw / clear
        }
        val factorNow = factor(series.first().first, nowPoint) ?: return null
        val window = series.filter { Duration.between(now, it.first).toMinutes() <= EVENT_WINDOW_MIN }
        val start = window.firstOrNull { (t, p) ->
            val f = factor(t, p) ?: return@firstOrNull false
            f < factorNow * (1 - MIN_DROP) && p.expectedKw < current * (1 - MIN_DROP)
        } ?: return null
        val minAfter = window.filter { !it.first.isBefore(start.first) && Duration.between(start.first, it.first).toMinutes() <= 60 }
            .minByOrNull { it.second.expectedKw }!!.second
        return CloudEvent(
            at = start.first,
            minutesAhead = Duration.between(now, start.first).toMinutes(),
            currentKw = current,
            expectedMinKw = minAfter.expectedKw,
            dropPercent = ((1 - minAfter.expectedKw / current) * 100).coerceIn(0.0, 100.0),
            confidence = (start.second.confidence * 0.7).coerceIn(0.05, 0.9),
            resolutionMinutes = resolutionMinutes,
        )
    }

    companion object {
        const val MIN_SHARE = 0.05
        const val MIN_DROP = 0.3
        const val EVENT_WINDOW_MIN = 180L
    }
}

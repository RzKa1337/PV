package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.shading.ShadingAnalysisEngine
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherSource
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.min

data class PvForecastPoint(
    val time: Instant,
    /** Model without shading, with calibration [kW]. */
    val unshadedKw: Double,
    val shadingLossKw: Double,
    /** Expected AC power after shading, calibration, nowcast and inverter limits [kW]. */
    val expectedKw: Double,
    val minKw: Double,
    val maxKw: Double,
    val kind: DataKind,
    val confidence: Double,
    val basis: String,
    val clipped: Boolean,
)

/**
 * PV prediction combining: sun position, irradiance (weather forecast → climate → clear sky), panel
 * orientation and power, cell temperature, computed shading (per panel/string), the calibration
 * learned from real Anenji production, a short-term "nowcast" from the current real/model ratio, and
 * the inverter's power limit.
 */
class PredictivePvEngine(
    private val location: GeoLocation,
    private val system: PvSystem,
    private val weather: WeatherAwareIrradianceModel,
    private val shading: ShadingAnalysisEngine?,
    private val calibrationFactor: Double = 1.0,
    private val calibrationConfidence: Double = 0.0,
    private val inverterLimitKw: Double? = null,
    private val mpptLimitKw: Double? = null,
) {
    private val estimator = PvEstimator(weather)

    /**
     * @param nowcastRatio measured / expected PV right now (null without live data); its influence
     *   fades with horizon (half after ~40 min) because clouds move.
     */
    fun at(time: Instant, now: Instant, nowcastRatio: Double? = null): PvForecastPoint {
        val estimate = estimator.pointEstimate(system, location, time)
        val calibrated = estimate.powerKw * calibrationFactor
        val shadeFactor = shading?.snapshot(system, time, estimate)?.powerFactor ?: 1.0
        val horizonMin = Duration.between(now, time).toMinutes().coerceAtLeast(0)
        val nowcastWeight = if (nowcastRatio == null) 0.0 else exp(-horizonMin / 60.0)
        val ratio = 1.0 + ((nowcastRatio ?: 1.0).coerceIn(0.0, 1.5) - 1.0) * nowcastWeight
        val limit = listOfNotNull(inverterLimitKw, mpptLimitKw).minOrNull() ?: Double.MAX_VALUE
        val raw = calibrated * shadeFactor * ratio
        val expected = min(raw, limit)

        val source = weather.sourceAt(time)
        val base = when (source) {
            WeatherSource.FORECAST -> 0.8 - (horizonMin / 60.0 / 72.0).coerceAtMost(0.3)
            WeatherSource.CLIMATE -> 0.35
            WeatherSource.CLEAR_SKY -> 0.2
        }
        val shadingConfidence = shading?.confidence()?.score ?: 0.6
        val confidence = (base * (0.6 + 0.4 * shadingConfidence) * (0.85 + 0.15 * calibrationConfidence) + 0.15 * nowcastWeight).coerceIn(0.05, 0.97)
        val band = (1 - confidence) * 0.9
        val kind = if (source == WeatherSource.FORECAST || nowcastWeight > 0.2) DataKind.FORECAST else DataKind.ESTIMATED
        return PvForecastPoint(
            time = time,
            unshadedKw = calibrated,
            shadingLossKw = calibrated * (1 - shadeFactor),
            expectedKw = expected,
            minKw = expected * (1 - band),
            maxKw = min(expected * (1 + band), limit),
            kind = kind,
            confidence = confidence,
            basis = when (source) {
                WeatherSource.FORECAST -> "prognoza pogody"
                WeatherSource.CLIMATE -> "średnie klimatyczne (brak prognozy)"
                WeatherSource.CLEAR_SKY -> "bezchmurne niebo (górna granica)"
            } + (if (nowcastWeight > 0.05) " + bieżący pomiar" else ""),
            clipped = raw > limit,
        )
    }

    fun series(from: Instant, to: Instant, step: Duration, now: Instant, nowcastRatio: Double? = null): List<PvForecastPoint> =
        generateSequence(from) { it.plus(step) }.takeWhile { !it.isAfter(to) }.map { at(it, now, nowcastRatio) }.toList()
}

/** Short-term horizons shown to the user. */
enum class ShortHorizon(val minutes: Long, val label: String) {
    M5(5, "+5 min"), M15(15, "+15 min"), M30(30, "+30 min"), H1(60, "+60 min"), H2(120, "+2 h"), H6(360, "+6 h");
}

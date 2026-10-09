package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.CalibrationContext
import com.solartracker.pro.core.analytics.CalibrationModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.shading.ShadingAnalysisEngine
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherEffects
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
    /** Conditional calibration (AutoCalibration 3.0); when ready it replaces [calibrationFactor]. */
    private val calibrationModel: CalibrationModel? = null,
) {
    private val estimator = PvEstimator(weather)

    /**
     * @param nowcastRatio measured / expected PV right now (null without live data); its influence
     *   fades with horizon (half after ~40 min) because clouds move.
     */
    fun at(time: Instant, now: Instant, nowcastRatio: Double? = null): PvForecastPoint {
        // The estimator's own inverter clip is switched off here: calibration, shading and nowcast act on the power the
        // array could deliver, and ONE clip (PvSystem limit, inverter, MPPT) is applied at the end. Clipping first would
        // let a 20 % shading loss lower a power that still exceeds the limit.
        val estimate = estimator.pointEstimate(system.copy(inverterLimitKw = null), location, time)
        val hour = weather.hourAt(time)
        val snow = WeatherEffects.snowCovered(hour, system.tiltDeg)
        // Wind cooling is part of the estimator's cell temperature (Faiman) – not applied a second time here.
        val factor = calibrationModel?.takeIf { it.ready }?.factor(
            CalibrationContext(time, estimate.sun.position.elevationDeg, WeatherEffects.skyCondition(hour, estimate.sun.position, time)),
        ) ?: calibrationFactor
        val calibrated = if (snow) 0.0 else estimate.powerKw * factor
        val shadeFactor = shading?.snapshot(system, time, estimate)?.powerFactor ?: 1.0
        val horizonMin = Duration.between(now, time).toMinutes().coerceAtLeast(0)
        // Forecast skill falls with the time since the forecast was DOWNLOADED, not only with the distance from now:
        // an hour predicted by a 2-day-old cached forecast is a 2-day-old prediction even when it is "now".
        val fetchedAt = weather.forecastFetchedAt
        val leadMin = fetchedAt?.let { Duration.between(it, time).toMinutes() }?.coerceAtLeast(horizonMin) ?: horizonMin
        val forecastAgeH = fetchedAt?.let { Duration.between(it, now).toHours() }?.coerceAtLeast(0)
        val nowcastWeight = if (nowcastRatio == null) 0.0 else exp(-horizonMin / 60.0)
        val ratio = 1.0 + ((nowcastRatio ?: 1.0).coerceIn(0.0, 1.5) - 1.0) * nowcastWeight
        val limit = listOfNotNull(system.sanitized().inverterLimitKw, inverterLimitKw, mpptLimitKw).minOrNull() ?: Double.MAX_VALUE
        val raw = calibrated * shadeFactor * ratio
        val expected = min(raw, limit)

        val source = weather.sourceAt(time)
        val base = when (source) {
            WeatherSource.FORECAST -> 0.8 - (leadMin / 60.0 / 72.0).coerceAtMost(0.3)
            WeatherSource.CLIMATE -> 0.35
            WeatherSource.CLEAR_SKY -> 0.2
        }
        val shadingConfidence = shading?.confidence()?.score ?: 0.6
        val calConfidence = calibrationModel?.takeIf { it.ready }?.confidence ?: calibrationConfidence
        val confidence = (base * (0.6 + 0.4 * shadingConfidence) * (0.85 + 0.15 * calConfidence) + 0.15 * nowcastWeight).coerceIn(0.05, 0.97)
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
            } + (if (source == WeatherSource.FORECAST && forecastAgeH != null && forecastAgeH >= STALE_FORECAST_HOURS) " (pobrana ${forecastAgeH} h temu)" else "") +
                (if (nowcastWeight > 0.05) " + bieżący pomiar" else "") +
                (if (snow) " · śnieg na panelach (prognoza)" else "") +
                (WeatherEffects.describe(hour)?.takeIf { !snow }?.let { " · $it" } ?: ""),
            clipped = raw > limit,
        )
    }

    fun series(from: Instant, to: Instant, step: Duration, now: Instant, nowcastRatio: Double? = null): List<PvForecastPoint> =
        generateSequence(from) { it.plus(step) }.takeWhile { !it.isAfter(to) }.map { at(it, now, nowcastRatio) }.toList()

    /**
     * Nowcast ratio for [at] (the instant of the measurement): measured power / what this engine expects right now WITHOUT a nowcast (so calibration,
     * shading and limits are already in the denominator and are not applied a second time). Null when the expectation
     * is below [minExpectedKw] (dawn/dusk: a ratio of two tiny numbers is noise).
     */
    fun nowcastRatio(measuredKw: Double, at: Instant, minExpectedKw: Double = MIN_NOWCAST_EXPECTED_KW): Double? {
        if (!measuredKw.isFinite() || measuredKw < 0) return null
        val expected = at(at, at).expectedKw
        return if (expected >= minExpectedKw) measuredKw / expected else null
    }

    /**
     * Energy slices for [from, to): each is (hours, forecast at the slice MIDDLE). Summing `kW · hours` is the
     * midpoint rule, exact for partial first/last slices; summing samples taken at slice starts is not (it biases
     * the energy by about half a step of the power at the boundary).
     */
    fun slices(from: Instant, to: Instant, step: Duration, now: Instant, nowcastRatio: Double? = null): List<Pair<Double, PvForecastPoint>> =
        midpointSlices(from, to, step).map { (mid, hours) -> hours to at(mid, now, nowcastRatio) }

    companion object {
        /** Below this expected power [kW] a measured/expected ratio is not used as a nowcast. */
        const val MIN_NOWCAST_EXPECTED_KW = 0.2

        /** A forecast downloaded at least this long ago is flagged as old in [PvForecastPoint.basis]. */
        const val STALE_FORECAST_HOURS = 3L
    }
}

/** Splits [from, to) into slices of at most [step]: (middle of the slice, length in hours). Empty when to <= from. */
internal fun midpointSlices(from: Instant, to: Instant, step: Duration): List<Pair<Instant, Double>> {
    require(!step.isZero && !step.isNegative) { "step must be positive" }
    val out = ArrayList<Pair<Instant, Double>>()
    var t = from
    while (t.isBefore(to)) {
        val next = minOf(t.plus(step), to)
        val len = Duration.between(t, next)
        out += t.plus(len.dividedBy(2)) to len.toMillis() / 3_600_000.0
        t = next
    }
    return out
}

/** Short-term horizons shown to the user. */
enum class ShortHorizon(val minutes: Long, val label: String) {
    NOW(0, "TERAZ"), M5(5, "+5 min"), M15(15, "+15 min"), M30(30, "+30 min"), H1(60, "+60 min"), H2(120, "+2 h"), H3(180, "+3 h"), H6(360, "+6 h");
}

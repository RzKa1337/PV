package com.solartracker.pro.core.weather

import com.solartracker.pro.core.analytics.SkyClassifier
import com.solartracker.pro.core.analytics.SkyCondition
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant

/**
 * Effects of forecast weather on PV output beyond irradiance. Each effect applies only when the
 * forecast actually provides the variable; otherwise the factor is 1 (no correction, nothing assumed).
 */
object WeatherEffects {
    /** Faiman coefficients (same as the loss-chain engine). */
    private const val U0 = 25.0
    private const val U1 = 6.84
    /** The NOCT model used by [PvEstimator] corresponds to roughly 1 m/s of wind. */
    private const val NOCT_WIND = 1.0

    /** Power factor from wind cooling relative to the NOCT model (> 1 with strong wind, < 1 when calm). */
    fun windFactor(poa: Double, ambientC: Double?, windMs: Double?, temperatureCoefficient: Double = PvEstimator.TEMPERATURE_COEFFICIENT): Double {
        if (ambientC == null || windMs == null || poa <= 0) return 1.0
        val tWind = ambientC + poa / (U0 + U1 * windMs)
        val tRef = ambientC + poa / (U0 + U1 * NOCT_WIND)
        return ((1 + temperatureCoefficient * (tWind - 25)) / (1 + temperatureCoefficient * (tRef - 25))).coerceIn(0.9, 1.1)
    }

    /** Panels considered covered: ≥ 2 cm of snow, air ≤ +1 °C, tilt below 60° (snow slides off steeper panels). */
    fun snowCovered(hour: HourlyWeather?, tiltDeg: Double): Boolean =
        com.solartracker.pro.core.pv.PvSimulationEngine.snowCovered(hour?.snowDepthM, hour?.temperatureC, tiltDeg)

    private val clearSky = ClearSkyModel()

    /** Clear-sky index of a forecast hour: forecast GHI / clear-sky GHI (null at night or without GHI). */
    fun clearSkyIndex(hour: HourlyWeather?, position: SolarPosition, time: Instant): Double? {
        val ghi = hour?.ghi ?: return null
        if (position.elevationDeg < 5) return null
        val clear = clearSky.irradiance(position, time).ghi(position)
        return if (clear > 50) (ghi / clear).coerceIn(0.0, 1.5) else null
    }

    fun skyCondition(hour: HourlyWeather?, position: SolarPosition, time: Instant): SkyCondition =
        SkyClassifier.classify(hour?.cloudCoverPercent, hour?.precipitationMm, hour?.snowDepthM, hour?.temperatureC, clearSkyIndex(hour, position, time))

    /**
     * Explains why a high total cloud cover can still give a lot of sun: the forecast irradiance (not the
     * cloud percentage) drives the PV estimate, and high thin clouds let most sunlight through.
     */
    fun cloudExplanation(hour: HourlyWeather?, clearSkyIndex: Double?): String? {
        hour ?: return null
        val total = hour.cloudCoverPercent ?: return null
        val csi = clearSkyIndex ?: return null
        if (total < 50 || csi < 0.6) return null
        val low = hour.cloudLowPercent
        val mid = hour.cloudMidPercent
        val high = hour.cloudHighPercent
        val thinHigh = high != null && (low ?: 0.0) < 30 && (mid ?: 0.0) < 30 && high >= 50
        return if (thinHigh) "Głównie wysokie, cienkie chmury – przepuszczają większość światła"
        else "Mimo zachmurzenia prognoza przewiduje sporo słońca (przerwy w chmurach)"
    }

    /** Short Polish description of notable conditions for the forecast basis. */
    fun describe(hour: HourlyWeather?): String? {
        hour ?: return null
        return buildList {
            hour.windSpeedMs?.takeIf { it >= 8 }?.let { add("silny wiatr ${"%.0f".format(it)} m/s") }
            hour.precipitationMm?.takeIf { it >= 0.5 }?.let { add("opady ${"%.1f".format(it)} mm") }
            hour.snowDepthM?.takeIf { it >= 0.02 }?.let { add("śnieg ${"%.0f".format(it * 100)} cm") }
            hour.visibilityM?.takeIf { it < 1000 }?.let { add("mgła (widoczność ${"%.0f".format(it)} m)") }
        }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

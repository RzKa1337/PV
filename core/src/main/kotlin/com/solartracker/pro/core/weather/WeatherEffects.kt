package com.solartracker.pro.core.weather

import com.solartracker.pro.core.analytics.SkyClassifier
import com.solartracker.pro.core.analytics.SkyCondition
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant

/**
 * Effects of forecast weather on PV output beyond irradiance. Each effect applies only when the
 * forecast actually provides the variable; otherwise the factor is 1 (no correction, nothing assumed).
 * Wind is NOT here: it cools the cells inside [com.solartracker.pro.core.pv.PvEstimator] (Faiman), a second
 * wind factor would count the same effect twice.
 */
object WeatherEffects {
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

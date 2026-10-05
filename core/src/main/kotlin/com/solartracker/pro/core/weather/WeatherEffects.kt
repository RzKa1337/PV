package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.PvEstimator

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
    fun snowCovered(hour: HourlyWeather?, tiltDeg: Double): Boolean {
        val depth = hour?.snowDepthM ?: return false
        val t = hour.temperatureC ?: return false
        return depth >= 0.02 && t <= 1.0 && tiltDeg < 60.0
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

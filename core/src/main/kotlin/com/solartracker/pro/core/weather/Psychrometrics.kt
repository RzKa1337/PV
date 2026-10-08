package com.solartracker.pro.core.weather

import kotlin.math.ln

/**
 * Dew point from air temperature and relative humidity of the SAME hour – Magnus formula with the
 * Alduchov & Eskridge (1996) coefficients (a = 17.625, b = 243.04 °C). Error below ~0.4 °C for
 * −40…+50 °C, which is finer than the inputs (forecast temperature ±1 °C, RH ±5 %), so the result is
 * shown to 0.1 °C at most and labelled CALCULATED.
 */
object Psychrometrics {
    const val A = 17.625
    const val B = 243.04
    const val MIN_C = -40.0
    const val MAX_C = 50.0

    /** Dew point [°C]; null outside the formula's validity (RH ≤ 0, RH > 100, T outside −40…50 °C). */
    fun dewPointC(temperatureC: Double?, relativeHumidityPercent: Double?): Double? {
        val t = temperatureC ?: return null
        val rh = relativeHumidityPercent ?: return null
        if (!t.isFinite() || !rh.isFinite() || rh <= 0.0 || rh > 100.0 || t < MIN_C || t > MAX_C) return null
        val gamma = ln(rh / 100.0) + A * t / (B + t)
        return B * gamma / (A - gamma)
    }

    /** Dew point of a forecast hour: the provider's value, otherwise computed from the same hour's T and RH. */
    fun dewPoint(hour: HourlyWeather): Pair<Double, Boolean>? =
        hour.dewPointC?.let { it to false } ?: dewPointC(hour.temperatureC, hour.relativeHumidityPercent)?.let { it to true }
}

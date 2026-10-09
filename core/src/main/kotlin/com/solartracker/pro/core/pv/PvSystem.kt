package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.normalizeDegrees

/**
 * PV installation parameters.
 *
 * @property peakPowerKw installed peak power [kWp]
 * @property tiltDeg panel tilt from horizontal, 0 (flat) .. 90 (vertical)
 * @property azimuthDeg compass direction the panels face, 0 = north, 90 = east, 180 = south
 * @property performanceRatio annual system losses (inverter, temperature, angle of incidence, wiring, soiling), 0..1;
 *   the model redistributes its temperature and angle parts over the day and the year (annual total unchanged)
 * @property temperatureCoefficient power temperature coefficient γ from the module datasheet [1/°C], e.g. −0.0035
 * @property inverterLimitKw AC limit of the inverter (or export limit) [kW]; null = no clipping below the peak power
 */
data class PvSystem(
    val peakPowerKw: Double = DEFAULT_PEAK_POWER_KW,
    val tiltDeg: Double = DEFAULT_TILT_DEG,
    val azimuthDeg: Double = DEFAULT_AZIMUTH_DEG,
    val performanceRatio: Double = DEFAULT_PERFORMANCE_RATIO,
    val temperatureCoefficient: Double = DEFAULT_TEMPERATURE_COEFFICIENT,
    val inverterLimitKw: Double? = null,
) {
    /** Returns a copy with every value clamped to its valid range. */
    fun sanitized(): PvSystem = PvSystem(
        peakPowerKw = peakPowerKw.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_PEAK_POWER_KW) ?: 0.0,
        tiltDeg = tiltDeg.takeIf { it.isFinite() }?.coerceIn(MIN_TILT_DEG, MAX_TILT_DEG) ?: 0.0,
        azimuthDeg = azimuthDeg.takeIf { it.isFinite() }?.let { normalizeDegrees(it) } ?: DEFAULT_AZIMUTH_DEG,
        performanceRatio = performanceRatio.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: DEFAULT_PERFORMANCE_RATIO,
        temperatureCoefficient = temperatureCoefficient.takeIf { it.isFinite() }?.coerceIn(MIN_TEMPERATURE_COEFFICIENT, 0.0)
            ?: DEFAULT_TEMPERATURE_COEFFICIENT,
        inverterLimitKw = inverterLimitKw?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_PEAK_POWER_KW),
    )

    companion object {
        const val DEFAULT_PEAK_POWER_KW = 2.09
        const val DEFAULT_TILT_DEG = 0.0
        const val DEFAULT_AZIMUTH_DEG = 180.0
        const val DEFAULT_PERFORMANCE_RATIO = 0.80
        /** Typical crystalline silicon (datasheets: about −0.0029 TOPCon … −0.0045 older poly). */
        const val DEFAULT_TEMPERATURE_COEFFICIENT = -0.004
        const val MIN_TEMPERATURE_COEFFICIENT = -0.01

        const val MIN_TILT_DEG = 0.0
        const val MAX_TILT_DEG = 90.0
        const val MIN_AZIMUTH_DEG = 0.0
        const val MAX_AZIMUTH_DEG = 359.0
        const val MAX_PEAK_POWER_KW = 1000.0
    }
}

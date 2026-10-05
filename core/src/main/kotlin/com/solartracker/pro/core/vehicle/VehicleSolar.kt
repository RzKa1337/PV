package com.solartracker.pro.core.vehicle

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.normalizeDegrees
import java.time.Duration
import java.time.Instant

/**
 * Vehicle / camper / boat mode. Panels are fixed to the vehicle, so their azimuth follows the heading.
 * Energy figures use the clear-sky model (upper bound) unless a clearness factor is supplied.
 */
data class VehicleSolarConfig(
    val panelPowerW: Double,
    /** Panel tilt relative to the roof; 0 = flat. */
    val tiltDeg: Double = 0.0,
    /** Panel facing relative to the vehicle front (0 = forward, 180 = rearward). */
    val relativeAzimuthDeg: Double = 0.0,
    val losses: LossProfile = LossProfile(),
    /** Vehicle consumption [kWh/100 km]; null = no range estimate. */
    val consumptionKwhPer100Km: Double? = null,
)

data class VehicleEstimate(
    val energyKwh: Double,
    val rangeKm: Double?,
    val headingDeg: Double,
    val clearSky: Boolean,
)

object VehicleSolarEstimator {
    private val engine = PvSimulationEngine()
    private val clearSky = ClearSkyModel()

    fun estimate(
        cfg: VehicleSolarConfig,
        location: GeoLocation,
        headingDeg: Double,
        from: Instant,
        to: Instant,
        clearnessFactor: Double? = null,
        stepMinutes: Long = 15,
    ): VehicleEstimate {
        val array = PvArrayConfig(1, cfg.panelPowerW, cfg.tiltDeg, normalizeDegrees(headingDeg + cfg.relativeAzimuthDeg))
        var wh = 0.0
        var t = from
        val step = Duration.ofMinutes(stepMinutes)
        while (t.isBefore(to)) {
            val h = minOf(step, Duration.between(t, to)).seconds / 3600.0
            val mid = t.plusSeconds((h * 1800).toLong())
            wh += engine.simulate(array, cfg.losses, location, mid, { PvConditions(clearSky.irradiance(it, mid)) }).acPowerW * h
            t = t.plus(step)
        }
        val kwh = wh / 1000.0 * (clearnessFactor?.coerceIn(0.0, 1.0) ?: 1.0)
        return VehicleEstimate(kwh, cfg.consumptionKwhPer100Km?.takeIf { it > 0 }?.let { kwh / it * 100.0 }, normalizeDegrees(headingDeg), clearnessFactor == null)
    }

    /** Parking heading that maximises energy in the window (relevant only for tilted panels). */
    fun bestHeading(cfg: VehicleSolarConfig, location: GeoLocation, from: Instant, to: Instant, stepDeg: Int = 15): VehicleEstimate =
        (0 until 360 step stepDeg).map { estimate(cfg, location, it.toDouble(), from, to, stepMinutes = 30) }.maxBy { it.energyKwh }
}

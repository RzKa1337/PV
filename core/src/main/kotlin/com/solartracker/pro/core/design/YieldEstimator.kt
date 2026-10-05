package com.solartracker.pro.core.design

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Annual yield from the clear-sky model: the 15th of each month is simulated through the full loss
 * chain and scaled by the month length. Clear sky = cloudless upper bound. A realistic figure needs a
 * site clearness factor (measured real/clear-sky ratio, e.g. from calibration) — never assumed here.
 */
data class AnnualYield(
    val clearSkyKwh: Double,
    val monthlyClearSkyKwh: List<Double>,
    /** Clear sky × clearness factor; null when no factor is known. */
    val expectedKwh: Double?,
    val expectedKind: DataKind,
    val peakPowerW: Double,
) {
    val specificClearSkyKwhPerKwp: Double get() = if (peakPowerW > 0) clearSkyKwh / (peakPowerW / 1000.0) else 0.0
}

object YieldEstimator {
    private val engine = PvSimulationEngine()
    private val clearSky = ClearSkyModel()

    fun annual(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        clearnessFactor: Double? = null,
        year: Int = 2026,
        stepMinutes: Long = 30,
    ): AnnualYield {
        val monthly = (1..12).map { m ->
            val date = LocalDate.of(year, m, 15)
            // Start the 24 h window at local solar midnight so the whole solar day is integrated.
            val dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds((location.longitude / 15.0 * 3600).toLong())
            val day = engine.daily(array, losses, location, dayStart, { t, sun -> PvConditions(clearSky.irradiance(sun, t)) }, stepMinutes)
            day.acOutputW / 1000.0 * YearMonth.of(year, m).lengthOfMonth()
        }
        val total = monthly.sum()
        val factor = clearnessFactor?.takeIf { it in 0.05..1.0 }
        return AnnualYield(total, monthly, factor?.let { total * it }, if (factor != null) DataKind.ESTIMATED else DataKind.UNKNOWN, array.peakPowerW)
    }

    /** Best fixed tilt (equator-facing) by clear-sky annual energy, searched every [stepDeg]. */
    fun optimalTilt(array: PvArrayConfig, losses: LossProfile, location: GeoLocation, stepDeg: Int = 5): Pair<Double, AnnualYield> {
        val azimuth = if (location.latitude >= 0) 180.0 else 0.0
        return (0..80 step stepDeg).map { tilt ->
            tilt.toDouble() to annual(array.copy(tiltDeg = tilt.toDouble(), azimuthDeg = azimuth), losses, location, stepMinutes = 60)
        }.maxBy { it.second.clearSkyKwh }
    }
}

/** World PV map / location comparison: same array evaluated at several places. */
data class SiteYield(
    val name: String,
    val location: GeoLocation,
    val clearSkyKwh: Double,
    val specificKwhPerKwp: Double,
    val optimalTiltDeg: Double,
    /** Gain of the optimal tilt over the configured tilt [%]. */
    val optimalTiltGainPercent: Double,
)

object LocationComparison {
    fun compare(sites: List<Pair<String, GeoLocation>>, array: PvArrayConfig, losses: LossProfile): List<SiteYield> =
        sites.map { (name, loc) ->
            val configured = YieldEstimator.annual(array, losses, loc, stepMinutes = 60)
            val (tilt, best) = YieldEstimator.optimalTilt(array, losses, loc)
            SiteYield(name, loc, configured.clearSkyKwh, configured.specificClearSkyKwhPerKwp, tilt,
                if (configured.clearSkyKwh > 0) (best.clearSkyKwh / configured.clearSkyKwh - 1) * 100 else 0.0)
        }.sortedByDescending { it.specificKwhPerKwp }
}

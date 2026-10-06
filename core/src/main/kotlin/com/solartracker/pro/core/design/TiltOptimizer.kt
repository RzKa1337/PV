package com.solartracker.pro.core.design

import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.solar.GeoLocation
import java.time.LocalDate
import java.time.Month

/** Yield of one tilt: the chosen day, each month and the year (clear sky = upper bound). */
data class TiltYield(val tiltDeg: Double, val dailyKwh: Double, val monthlyKwh: List<Double>, val annualKwh: Double)

data class TiltRecommendation(
    val yields: List<TiltYield>,
    /** Best single fixed tilt for the whole year. */
    val bestStatic: TiltYield,
    /** Best tilt for the chosen day. */
    val bestDaily: TiltYield,
    /** Best tilt for each month (adjusting monthly). */
    val bestMonthly: Map<Month, Double>,
    /** Annual gain of adjusting monthly instead of the best static tilt [%]. */
    val monthlyAdjustGainPercent: Double,
)

/**
 * Mechanism that can change the tilt (manual or motorised). This is a MODEL for planning only: the app
 * does not drive any actuator; a future control module would need its own explicit confirmation.
 */
data class AdjustableMount(val minTiltDeg: Double, val maxTiltDeg: Double, val stepDeg: Double = 5.0) {
    fun clamp(tilt: Double): Double {
        val c = tilt.coerceIn(minTiltDeg, maxTiltDeg)
        return (Math.round((c - minTiltDeg) / stepDeg) * stepDeg + minTiltDeg).coerceIn(minTiltDeg, maxTiltDeg)
    }
}

/** Compares tilts 0–90° for daily, monthly and annual clear-sky yield of a fixed azimuth. */
object TiltOptimizer {
    fun compare(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        date: LocalDate,
        tilts: List<Double> = (0..90 step 5).map { it.toDouble() },
        mount: AdjustableMount? = null,
    ): TiltRecommendation {
        val allowed = mount?.let { m -> tilts.filter { it in m.minTiltDeg..m.maxTiltDeg } }?.ifEmpty { null } ?: tilts
        val yields = allowed.map { tilt ->
            val a = array.copy(tiltDeg = tilt)
            val annual = YieldEstimator.annual(a, losses, location, stepMinutes = 60)
            TiltYield(tilt, YieldEstimator.day(a, losses, location, date), annual.monthlyClearSkyKwh, annual.clearSkyKwh)
        }
        val bestStatic = yields.maxBy { it.annualKwh }
        val bestMonthly = Month.entries.associateWith { m -> yields.maxBy { it.monthlyKwh[m.ordinal] }.tiltDeg }
        val monthlyTotal = Month.entries.sumOf { m -> yields.maxOf { it.monthlyKwh[m.ordinal] } }
        return TiltRecommendation(
            yields, bestStatic, yields.maxBy { it.dailyKwh }, bestMonthly,
            if (bestStatic.annualKwh > 0) (monthlyTotal / bestStatic.annualKwh - 1) * 100 else 0.0,
        )
    }
}

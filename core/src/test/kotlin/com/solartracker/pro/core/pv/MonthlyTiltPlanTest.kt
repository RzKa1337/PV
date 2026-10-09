package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Month
import java.time.ZoneId

/** Monthly re-tilting on the clear-sky model (no weather) – geometry only, so the expected behaviour is physical. */
class MonthlyTiltPlanTest {
    private val estimator = PvEstimator()
    private val warsaw = GeoLocation(52.23, 21.01)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun winterSteepSummerFlatAndMonthlyBeatsAnyFixedAngle() {
        val t0 = System.nanoTime()
        val plan = estimator.monthlyTiltPlan(PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0), warsaw, 2026, zone)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("plan: ${plan.months.map { "${it.month}=${it.bestTiltDeg.toInt()}°" }} adj=${plan.monthlyAdjustedKwh} fixed30=${plan.currentFixedKwh} best=${plan.bestFixedTiltDeg}/${plan.bestFixedKwh} in $ms ms")
        fun tilt(m: Month) = plan.months.single { it.month == m }.bestTiltDeg
        assertTrue("December steep: ${tilt(Month.DECEMBER)}", tilt(Month.DECEMBER) >= 60)
        assertTrue("June flat: ${tilt(Month.JUNE)}", tilt(Month.JUNE) <= 25)
        assertTrue(tilt(Month.MARCH) in tilt(Month.JUNE)..tilt(Month.DECEMBER))
        // Every month is at least as good as the current angle; the year beats the best fixed angle.
        plan.months.forEach { assertTrue(it.month.name, it.bestKwh >= it.currentTiltKwh - 1e-9) }
        assertTrue(plan.monthlyAdjustedKwh >= plan.bestFixedKwh)
        assertTrue(plan.bestFixedKwh >= plan.currentFixedKwh - 1e-9)
        assertTrue("best fixed angle for 52° N: ${plan.bestFixedTiltDeg}", plan.bestFixedTiltDeg in 30.0..50.0)
        val gain = plan.gainPercent(plan.bestFixedKwh)!!
        assertTrue("monthly gain vs best fixed angle $gain %", gain in 1.0..15.0)
        assertTrue("whole degrees", plan.months.all { it.bestTiltDeg == Math.rint(it.bestTiltDeg) })
        assertTrue("fast enough: $ms ms", ms < 20_000)
    }

    @Test
    fun currentAngleOffGridAndSouthernHemisphere() {
        val plan = estimator.monthlyTiltPlan(PvSystem(tiltDeg = 33.5, azimuthDeg = 180.0), warsaw, 2026, zone, tiltStepDeg = 5.0)
        assertEquals(33.5, plan.currentTiltDeg, 0.0)
        assertTrue(plan.months.all { it.bestTiltDeg % 5.0 == 0.0 })
        // Southern hemisphere, panels facing north: June (winter) steep, December (summer) flat.
        val sydney = estimator.monthlyTiltPlan(PvSystem(tiltDeg = 30.0, azimuthDeg = 0.0), GeoLocation(-33.87, 151.21), 2026, ZoneId.of("Australia/Sydney"), tiltStepDeg = 2.0)
        val jun = sydney.months.single { it.month == Month.JUNE }.bestTiltDeg
        val dec = sydney.months.single { it.month == Month.DECEMBER }.bestTiltDeg
        assertTrue("Sydney June $jun > December $dec", jun > dec + 20)
    }
}

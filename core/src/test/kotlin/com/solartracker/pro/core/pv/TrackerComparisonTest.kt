package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Month
import java.time.ZoneId

/** Fixed panels vs single/dual-axis tracker on the clear-sky model – geometry only, so the ordering is physical. */
class TrackerComparisonTest {
    private val estimator = PvEstimator()
    private val warsaw = GeoLocation(52.23, 21.01)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun dualAxisBeatsSingleAxisBeatsFixed() {
        val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 35.0, azimuthDeg = 180.0)
        val c = estimator.trackerComparison(system, warsaw, 2026, zone)
        println("tracker: fixed=${c.fixedKwh} 1-axis=${c.singleAxisKwh} 2-axis=${c.dualAxisKwh}")
        assertEquals(12, c.months.size)
        // The fixed column is the regular estimate for the same panels.
        assertEquals(estimator.monthlyTiltPlan(system, warsaw, 2026, zone, tiltStepDeg = 45.0).currentFixedKwh, c.fixedKwh, c.fixedKwh * 0.001)
        c.months.forEach {
            assertTrue(it.month.name, it.dualAxisKwh >= it.singleAxisKwh - 1e-6)
            assertTrue(it.month.name, it.dualAxisKwh >= it.fixedKwh - 1e-6)
        }
        val dual = c.gainPercent(c.dualAxisKwh)!!
        val single = c.gainPercent(c.singleAxisKwh)!!
        assertTrue("dual-axis gain $dual %", dual in 20.0..60.0)
        assertTrue("single-axis gain $single %", single in 5.0..dual)
        // Summer gains of a tracker are much bigger than winter ones (long days, sun in the east/west).
        fun m(x: Month) = c.months.single { it.month == x }
        assertTrue(m(Month.JUNE).dualAxisKwh / m(Month.JUNE).fixedKwh > m(Month.DECEMBER).dualAxisKwh / m(Month.DECEMBER).fixedKwh)
        // A horizontal single-axis tracker gains little in December at 52° N (low sun in the south).
        assertTrue(m(Month.DECEMBER).singleAxisKwh < m(Month.DECEMBER).fixedKwh)
    }

    @Test
    fun eastFacingPanelsGainMoreAndOrientationGeometry() {
        val south = estimator.trackerComparison(PvSystem(tiltDeg = 35.0, azimuthDeg = 180.0), warsaw, 2026, zone, stepMinutes = 30)
        val east = estimator.trackerComparison(PvSystem(tiltDeg = 35.0, azimuthDeg = 90.0), warsaw, 2026, zone, stepMinutes = 30)
        // A tracker does not care where the fixed panels were pointing.
        assertEquals(south.dualAxisKwh, east.dualAxisKwh, south.dualAxisKwh * 1e-9)
        assertTrue(east.gainPercent(east.dualAxisKwh)!! > south.gainPercent(south.dualAxisKwh)!!)
        // Geometry: dual axis faces the sun; single axis is flat at noon and limited to ±60°.
        val noon = com.solartracker.pro.core.solar.SolarPosition(elevationDeg = 40.0, azimuthDeg = 180.0)
        assertEquals(50.0 to 180.0, trackerSurfaceOrientation(TrackerType.DUAL_AXIS, 35.0, 180.0, 60.0, noon))
        assertEquals(0.0, trackerSurfaceOrientation(TrackerType.SINGLE_AXIS, 35.0, 180.0, 60.0, noon).first, 1e-9)
        val morning = com.solartracker.pro.core.solar.SolarPosition(elevationDeg = 5.0, azimuthDeg = 80.0)
        assertEquals(60.0 to 90.0, trackerSurfaceOrientation(TrackerType.SINGLE_AXIS, 35.0, 180.0, 60.0, morning))
    }
}

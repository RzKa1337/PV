package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Southern hemisphere: south-facing panels look away from the sun; the monthly plan then (correctly) says "flat". */
class HemisphereTest {
    @Test
    fun equatorDirectionPerHemisphere() {
        assertEquals(180.0, PvSystem.equatorAzimuth(52.2), 0.0)
        assertEquals(0.0, PvSystem.equatorAzimuth(-24.5), 0.0)
        assertTrue(PvSystem.facesAwayFromEquator(180.0, -24.5))   // Atacama, panels to the south
        assertFalse(PvSystem.facesAwayFromEquator(0.0, -24.5))
        assertFalse(PvSystem.facesAwayFromEquator(30.0, -24.5))   // NE still faces the equator side
        assertTrue(PvSystem.facesAwayFromEquator(0.0, 52.2))      // Warsaw, panels to the north
        assertFalse(PvSystem.facesAwayFromEquator(180.0, 52.2))
        assertFalse(PvSystem.facesAwayFromEquator(180.0, -2.0))   // near the equator: no warning
        assertFalse(PvSystem.facesAwayFromEquator(270.0, -24.5))  // west: exactly 90° off, not "away"
    }

    @Test
    fun atacamaFlatWhenFacingSouthTiltedWhenFacingNorth() {
        val loc = GeoLocation(-24.5, -69.25)
        val z = ZoneId.of("America/Santiago")
        val e = PvEstimator()
        val south = e.monthlyTiltPlan(PvSystem(peakPowerKw = 1.0, tiltDeg = 0.0, azimuthDeg = 180.0), loc, 2026, z, tiltStepDeg = 2.0)
        val north = e.monthlyTiltPlan(PvSystem(peakPowerKw = 1.0, tiltDeg = 0.0, azimuthDeg = 0.0), loc, 2026, z, tiltStepDeg = 2.0)
        assertEquals(0.0, south.bestFixedTiltDeg, 0.0)
        // Facing the equator at 24.5° S: best fixed tilt near the latitude, steep in June (local winter).
        assertTrue("best fixed ${north.bestFixedTiltDeg}", north.bestFixedTiltDeg in 16.0..30.0)
        assertTrue(north.months.single { it.month.value == 6 }.bestTiltDeg >= 45.0)
        assertTrue(north.bestFixedKwh > south.bestFixedKwh * 1.04)
        // The tracker does not depend on how the fixed panels were pointed – it gains over both.
        val t = e.trackerComparison(PvSystem(peakPowerKw = 1.0, tiltDeg = north.bestFixedTiltDeg, azimuthDeg = 0.0), loc, 2026, z)
        assertTrue(t.singleAxisKwh > t.fixedKwh * 1.2)
        assertTrue(t.dualAxisKwh > t.singleAxisKwh)
    }
}

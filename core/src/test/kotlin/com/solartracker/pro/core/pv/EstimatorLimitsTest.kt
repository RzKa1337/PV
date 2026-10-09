package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EstimatorLimitsTest {
    private val warsaw = GeoLocation(52.23, 21.01)
    private val noon = SolarCalculator.sunTimes(warsaw, LocalDate.of(2026, 6, 21)).solarNoon

    @Test
    fun capAtPeakWithoutInverterLimitIsNotReportedAsInverterClipping() {
        // PR 1.0 on a clear June noon pushes the model above the nameplate; the cap at kWp is only a sanity bound.
        val s = PvSystem(peakPowerKw = 6.0, tiltDeg = 35.0, azimuthDeg = 180.0, performanceRatio = 1.0)
        val e = PvEstimator().pointEstimate(s, warsaw, noon)
        assertEquals(6.0, e.powerKw, 1e-9)
        assertFalse("no inverter limit is configured, so nothing was clipped by an inverter", e.clipped)
        val limited = PvEstimator().pointEstimate(s.copy(inverterLimitKw = 5.0), warsaw, noon)
        assertEquals(5.0, limited.powerKw, 1e-9)
        assertTrue(limited.clipped)
    }

    @Test
    fun energyWithLimitEqualsPowerCappedAtTheLimitEverywhere() {
        val s = PvSystem(peakPowerKw = 6.0, tiltDeg = 35.0, azimuthDeg = 180.0)
        val free = PvEstimator()
        val capped = PvEstimator()
        val limit = 3.5
        val profile = free.dailyProfile(s, warsaw, LocalDate.of(2026, 6, 21), java.time.ZoneOffset.UTC, 5)
        val limitedProfile = capped.dailyProfile(s.copy(inverterLimitKw = limit), warsaw, LocalDate.of(2026, 6, 21), java.time.ZoneOffset.UTC, 5)
        profile.zip(limitedProfile).forEach { (a, b) -> assertEquals(minOf(a.powerKw, limit), b.powerKw, 1e-9) }
    }
}

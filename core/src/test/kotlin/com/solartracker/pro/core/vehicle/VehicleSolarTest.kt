package com.solartracker.pro.core.vehicle

import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VehicleSolarTest {
    private val loc = GeoLocation(50.0, 19.0)
    private val from = Instant.parse("2026-06-21T03:00:00Z")
    private val to = Instant.parse("2026-06-21T19:00:00Z")

    @Test
    fun flatPanelIgnoresHeading() {
        val cfg = VehicleSolarConfig(400.0, consumptionKwhPer100Km = 20.0)
        val a = VehicleSolarEstimator.estimate(cfg, loc, 0.0, from, to)
        val b = VehicleSolarEstimator.estimate(cfg, loc, 135.0, from, to)
        assertEquals(a.energyKwh, b.energyKwh, 1e-6)
        assertTrue(a.energyKwh in 1.0..4.0)
        assertEquals(a.energyKwh / 20.0 * 100, a.rangeKm!!, 1e-9)
        assertTrue(a.clearSky)
        assertEquals(a.energyKwh * 0.4, VehicleSolarEstimator.estimate(cfg, loc, 0.0, from, to, clearnessFactor = 0.4).energyKwh, 1e-9)
    }

    @Test
    fun tiltedPanelPrefersSouth() {
        val cfg = VehicleSolarConfig(300.0, tiltDeg = 30.0)
        val best = VehicleSolarEstimator.bestHeading(cfg, loc, Instant.parse("2026-12-21T07:00:00Z"), Instant.parse("2026-12-21T15:00:00Z"))
        assertTrue("best ${best.headingDeg}", best.headingDeg in 150.0..210.0)
    }
}

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

class MobilePvTest {
    private val loc = GeoLocation(50.0, 19.0)
    private val from = Instant.parse("2026-03-21T05:00:00Z")
    private val to = Instant.parse("2026-03-21T17:00:00Z")
    private val bus = VehicleBody(12.0, 2.5, roofUsableAreaM2 = 20.0)
    private fun panel(n: Int, x: Double, tilt: Double = 0.0, az: Double = 0.0) = VehiclePanel("P$n", 400.0, 1.7, 1.1, xM = x, yM = 0.2, tiltDeg = tilt, relativeAzimuthDeg = az)

    @Test
    fun roofLayoutValidation() {
        val ok = VehicleSolarConfig(0.0, body = bus, panels = listOf(panel(1, 0.5), panel(2, 2.3), panel(3, 4.1)))
        assertTrue(ok.validate().toString(), ok.validate().isEmpty())
        assertEquals(1200.0, ok.totalPowerW, 0.0)
        assertTrue(ok.copy(panels = listOf(panel(1, 0.5), panel(2, 1.0))).validate().any { it.contains("nachodzą") })
        assertTrue(ok.copy(panels = listOf(panel(1, 11.0))).validate().any { it.contains("poza dach") })
        assertTrue(ok.copy(body = bus.copy(roofUsableAreaM2 = 3.0)).validate().any { it.contains("użyteczny dach") })
    }

    @Test
    fun headingMattersForTiltedNotForFlat() {
        val flat = VehicleSolarConfig(0.0, body = bus, panels = listOf(panel(1, 0.5), panel(2, 2.3)))
        val fp = VehicleSolarEstimator.headingProfile(flat, loc, from, to, stepDeg = 30)
        assertTrue(fp.flat)
        assertTrue("flat sensitivity ${fp.sensitivity}", fp.sensitivity < 0.01)
        val tilted = VehicleSolarConfig(0.0, body = bus, panels = listOf(panel(1, 0.5, tilt = 40.0, az = 90.0), panel(2, 2.3, tilt = 40.0, az = 90.0)))
        val tp = VehicleSolarEstimator.headingProfile(tilted, loc, from, to, stepDeg = 10)
        assertEquals(36, tp.points.size)
        assertTrue(tp.sensitivity > 0.3)
        // Panels face the vehicle's right side → best when the right side points south: heading ≈ 90°.
        assertTrue("best ${tp.best.headingDeg}", tp.best.headingDeg in 60.0..120.0)
    }

    @Test
    fun tiltComparisonUsesConfiguredFacing() {
        val cfg = VehicleSolarConfig(0.0, panels = listOf(panel(1, 0.0, tilt = 0.0, az = 180.0)))
        val rows = VehicleSolarEstimator.tiltComparison(cfg, loc, headingDeg = 0.0, from = from, to = to)
        assertEquals(19, rows.size)
        val best = rows.maxBy { it.second }
        assertTrue("best tilt ${best.first}", best.first in 30.0..60.0) // facing south in March
        assertTrue(rows.first { it.first == 90.0 }.second < best.second)
    }
}

class TiltOptimizerTest {
    @Test
    fun staticDailyMonthly() {
        val loc = GeoLocation(52.23, 21.01)
        val array = com.solartracker.pro.core.pv.PvArrayConfig(1, 4000.0, 0.0, 180.0)
        val rec = com.solartracker.pro.core.design.TiltOptimizer.compare(array, com.solartracker.pro.core.pv.LossProfile(), loc, java.time.LocalDate.of(2026, 12, 21),
            tilts = (0..90 step 10).map { it.toDouble() })
        assertEquals(10, rec.yields.size)
        assertTrue(rec.bestStatic.tiltDeg in 30.0..50.0)
        assertTrue("winter day prefers steep: ${rec.bestDaily.tiltDeg}", rec.bestDaily.tiltDeg >= 60.0)
        assertTrue(rec.bestMonthly.getValue(java.time.Month.JUNE) < rec.bestMonthly.getValue(java.time.Month.DECEMBER))
        assertTrue(rec.monthlyAdjustGainPercent > 0)
        val mount = com.solartracker.pro.core.design.AdjustableMount(10.0, 45.0, 5.0)
        assertEquals(45.0, mount.clamp(70.0), 0.0)
        assertEquals(25.0, mount.clamp(23.0), 0.0)
        val limited = com.solartracker.pro.core.design.TiltOptimizer.compare(array, com.solartracker.pro.core.pv.LossProfile(), loc, java.time.LocalDate.of(2026, 12, 21),
            tilts = (0..90 step 10).map { it.toDouble() }, mount = mount)
        assertTrue(limited.yields.all { it.tiltDeg in 10.0..45.0 })
    }
}

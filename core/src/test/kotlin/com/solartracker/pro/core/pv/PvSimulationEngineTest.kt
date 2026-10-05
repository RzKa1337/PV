package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PvSimulationEngineTest {
    private val engine = PvSimulationEngine()
    private val warsaw = GeoLocation(52.23, 21.01, 100.0)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val noon = LocalDate.of(2026, 6, 21).atTime(13, 0).atZone(zone).toInstant()
    private val noLoss = LossProfile(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, null)
    private fun array(n: Int = 10, w: Double = 400.0, tilt: Double = 35.0) = PvArrayConfig(n, w, tilt, 180.0)

    /** Irradiance chosen so that POA ≈ 1000 W/m² is not needed: compare relative values instead. */
    private val clear = { _: com.solartracker.pro.core.solar.SolarPosition -> PvConditions(Irradiance(850.0, 120.0), ambientC = 25.0, windMs = 0.0) }

    @Test
    fun stcLikeConditionsGivePeakPowerWithoutLosses() {
        // Cell at 25 °C, POA exactly 1000: use dual-axis tracker facing the sun with DNI 1000, no diffuse, no albedo.
        val a = PvArrayConfig(10, 400.0, 0.0, 180.0, albedo = 0.0, tracker = TrackerType.DUAL_AXIS)
        val p = engine.simulate(a, noLoss, warsaw, noon, { PvConditions(Irradiance(1000.0, 0.0), ambientC = -1000.0 / 25.0 + 25.0, windMs = 0.0) })
        // Faiman: Tc = Ta + 1000/25 = 25 °C
        assertEquals(25.0, p.cellTemperatureC!!, 1e-6)
        assertEquals(1000.0, p.losses.poaWm2, 1e-6)
        assertEquals(4000.0, p.acPowerW, 1e-6)
    }

    @Test
    fun lossChainIsConsistentAndOrdered() {
        val losses = LossProfile(soiling = 0.03, mismatch = 0.02, dcWiring = 0.015, acWiring = 0.01, degradationPerYear = 0.005, ageYears = 4.0, inverterLimitW = 2500.0)
        val p = engine.simulate(array(), losses, warsaw, noon, clear)
        val b = p.losses
        val sumLosses = b.steps.sumOf { it.second }
        assertEquals(b.idealDcW + b.bifacialGainW - b.acOutputW, sumLosses, 1e-6)
        assertTrue("clipped at the limit", b.clippingLossW > 0)
        assertEquals(2500.0 * (1 - 0.01), b.acOutputW, 1e-6)
        assertTrue(b.temperatureLossW > 0) // cell hotter than 25 °C
        assertEquals(b.dcInputW, b.idealDcW - b.temperatureLossW - b.soilingLossW - b.mismatchLossW - b.degradationLossW - b.dcWiringLossW, 1e-6)
        assertTrue(b.performanceRatio!! < 1.0)
    }

    @Test
    fun windCoolsCellsAndTemperatureCoefficientApplies() {
        val calm = engine.simulate(array(), noLoss, warsaw, noon, { PvConditions(Irradiance(850.0, 120.0), 30.0, 0.0) })
        val windy = engine.simulate(array(), noLoss, warsaw, noon, { PvConditions(Irradiance(850.0, 120.0), 30.0, 8.0) })
        assertTrue(windy.cellTemperatureC!! < calm.cellTemperatureC!!)
        assertTrue(windy.acPowerW > calm.acPowerW)
        val loss = calm.losses.temperatureLossW / calm.losses.idealDcW
        assertEquals(0.0035 * (calm.cellTemperatureC!! - 25.0), loss, 1e-9)
    }

    @Test
    fun snowCoversPanelsOnlyWhenFreezing() {
        val snowy = engine.simulate(array(), noLoss, warsaw, noon, { PvConditions(Irradiance(850.0, 120.0), -3.0, 2.0, snowDepthM = 0.1) })
        assertTrue(snowy.snowCovered)
        assertEquals(0.0, snowy.acPowerW, 1e-9)
        val thaw = engine.simulate(array(), noLoss, warsaw, noon, { PvConditions(Irradiance(850.0, 120.0), 6.0, 2.0, snowDepthM = 0.1) })
        assertFalse(thaw.snowCovered)
        val steep = engine.simulate(array(tilt = 70.0), noLoss, warsaw, noon, { PvConditions(Irradiance(850.0, 120.0), -3.0, 2.0, snowDepthM = 0.1) })
        assertFalse(steep.snowCovered)
        assertFalse(PvSimulationEngine.isSnowCovered(PvConditions(Irradiance.ZERO, -5.0, null, null), 30.0))
    }

    @Test
    fun trackersAndBifacialProduceMore() {
        val day = LocalDate.of(2026, 6, 21).atStartOfDay(zone).toInstant()
        fun daily(a: PvArrayConfig) = engine.daily(a, LossProfile(), warsaw, day, { _, s -> PvConditions(ClearSkyModel().irradiance(s, day.plusSeconds(43200)), 20.0, 2.0) }).acOutputW
        val fixed = daily(array())
        val single = daily(array().copy(tracker = TrackerType.SINGLE_AXIS))
        val dual = daily(array().copy(tracker = TrackerType.DUAL_AXIS))
        val bifacial = daily(array().copy(bifacialGain = 0.1, albedo = 0.3))
        assertTrue("single $single > fixed $fixed", single > fixed * 1.1)
        assertTrue(dual >= single)
        assertTrue(bifacial > fixed)
        assertTrue(fixed in 25_000.0..40_000.0) // 4 kWp, June clear day ≈ 6–9 kWh/kWp
    }

    @Test
    fun singleAxisFacesEastInTheMorningWestInTheEvening() {
        val sunEast = com.solartracker.pro.core.solar.SolarPosition(20.0, 90.0)
        val sunWest = com.solartracker.pro.core.solar.SolarPosition(20.0, 270.0)
        val a = array().copy(tracker = TrackerType.SINGLE_AXIS)
        assertEquals(90.0, engine.surfaceOrientation(a, sunEast).second, 0.0)
        assertEquals(270.0, engine.surfaceOrientation(a, sunWest).second, 0.0)
        assertEquals(60.0, engine.surfaceOrientation(a, sunEast).first, 1e-9) // limited to ±60°
    }

    @Test
    fun nightIsZeroAndDeterministic() {
        val night = LocalDate.of(2026, 6, 21).atTime(1, 0).atZone(zone).toInstant()
        assertEquals(0.0, engine.simulate(array(), LossProfile(), warsaw, night, clear).acPowerW, 0.0)
        assertEquals(engine.simulate(array(), LossProfile(), warsaw, noon, clear), engine.simulate(array(), LossProfile(), warsaw, noon, clear))
        assertTrue(PvArrayConfig(0, 400.0, 30.0, 180.0).validate().isNotEmpty())
        assertTrue(LossProfile(soiling = 0.9).validate().isNotEmpty())
    }
}

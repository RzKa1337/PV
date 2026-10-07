package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.pv.Irradiance
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.pv.TrackerType
import com.solartracker.pro.core.pv.ashraeIam
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PvRealityEngineTest {
    private val site = GeoLocation(37.3453, 27.2646, 50.0)
    private val zone = ZoneId.of("Europe/Istanbul")
    private val noon = LocalDate.of(2026, 6, 21).atTime(13, 0).atZone(zone).toInstant()
    private val night = LocalDate.of(2026, 6, 21).atTime(1, 0).atZone(zone).toInstant()
    private val sunrise = LocalDate.of(2026, 6, 21).atTime(6, 10).atZone(zone).toInstant()
    private val array = PvArrayConfig(4, 535.0, 0.0, 180.0)
    private val losses = LossProfile()
    private val sunny = PvConditions(Irradiance(850.0, 110.0), ambientC = 28.0, windMs = 2.0)
    private fun measured(w: Double) = Quantity(w, "W", DataKind.MEASURED, "Anenji", noon)

    private fun assess(actual: Quantity?, conditions: PvConditions = sunny, time: java.time.Instant = noon, a: PvArrayConfig = array, l: LossProfile = losses,
                       basis: IrradianceBasis = IrradianceBasis.FORECAST, shading: Double = 1.0, csi: Double? = 1.0) =
        PvRealityEngine.assess(a, l, site, time, conditions, actual, basis, shadingFactor = shading, shadingConfidence = 0.8, clearSkyIndex = csi)

    @Test
    fun breakdownAddsUpFromTheoreticalToActual() {
        val model = assess(null)
        val r = assess(measured(model.expectedW * 0.98))
        val sum = r.losses.sumOf { it.watts }
        assertEquals(r.theoreticalW - r.actualW!!, sum, 1e-6)
        assertEquals(RealityStatus.OK, r.status)
        assertEquals(-2.0, r.deviationPercent!!, 1e-6)
        assertTrue(r.confidence in 0.2..0.98)
        assertTrue(r.losses.single { it.step == LossStep.SPECTRAL }.let { !it.modelled && it.kind == DataKind.UNAVAILABLE })
        assertTrue(r.losses.single { it.step == LossStep.AOI }.watts >= 0.0)
    }

    @Test
    fun belowAndAboveBeyondUncertainty() {
        val model = assess(null)
        assertEquals(RealityStatus.BELOW, assess(measured(model.expectedW * 0.6)).status)
        assertEquals(RealityStatus.ABOVE, assess(measured(model.expectedW * 1.5)).status)
    }

    @Test
    fun nightSunriseZeroIrradianceAndNoMeasurement() {
        assertEquals(RealityStatus.NIGHT, assess(measured(0.0), time = night).status)
        val dawn = assess(measured(5.0), conditions = PvConditions(Irradiance(30.0, 10.0), 18.0, 1.0), time = sunrise)
        assertEquals(RealityStatus.LOW_LIGHT, dawn.status)
        assertNull(dawn.deviationPercent)
        val dark = assess(measured(0.0), conditions = PvConditions(Irradiance.ZERO, 20.0, 1.0))
        assertEquals(RealityStatus.LOW_LIGHT, dark.status)
        assertEquals(0.0, dark.theoreticalW, 1e-9)
        val none = assess(null)
        assertEquals(RealityStatus.NO_MEASUREMENT, none.status)
        assertEquals(0.0, none.confidence, 0.0)
    }

    @Test
    fun staleOrLastKnownValuesAreNotCompared() {
        val stale = assess(Quantity(1000.0, "W", DataKind.STALE, "Anenji", noon))
        assertEquals(RealityStatus.NO_MEASUREMENT, stale.status)
        val last = assess(Quantity(1000.0, "W", DataKind.LAST_KNOWN, "Anenji", noon))
        assertNull(last.unexplainedW)
    }

    @Test
    fun simulatedDataHalvesConfidenceAndKeepsItsKind() {
        val model = assess(null)
        val real = assess(measured(model.expectedW))
        val sim = assess(Quantity(model.expectedW, "W", DataKind.SIMULATED, "symulator", noon))
        assertEquals(DataKind.SIMULATED, sim.actual!!.kind)
        assertEquals(real.confidence * 0.5, sim.confidence, 1e-9)
    }

    @Test
    fun extremeHeatGivesLargeTemperatureLossAndColdGivesGain() {
        val hot = assess(null, conditions = PvConditions(Irradiance(850.0, 110.0), 45.0, 0.0))
        val cold = assess(null, conditions = PvConditions(Irradiance(850.0, 110.0), -15.0, 3.0))
        val hotLoss = hot.losses.single { it.step == LossStep.TEMPERATURE }
        assertTrue(hotLoss.percentOfTheoretical > 10)
        assertTrue(cold.losses.single { it.step == LossStep.TEMPERATURE }.watts < 0) // below 25 °C cells gain
        assertTrue(cold.expectedW > hot.expectedW)
    }

    @Test
    fun snowShadingAndClippingAppearAsTheirOwnSteps() {
        val snow = assess(null, conditions = PvConditions(Irradiance(850.0, 110.0), -3.0, 1.0, snowDepthM = 0.1))
        assertEquals(snow.theoreticalW - snow.losses.single { it.step == LossStep.AOI }.watts - snow.losses.single { it.step == LossStep.TEMPERATURE }.watts,
            snow.losses.single { it.step == LossStep.SNOW }.watts, 1e-6)
        assertEquals(0.0, snow.expectedW, 1e-9)
        val shaded = assess(null, shading = 0.7)
        assertTrue(shaded.losses.single { it.step == LossStep.SHADING }.percentOfTheoretical > 20)
        val clipped = assess(null, l = losses.copy(inverterLimitW = 1000.0))
        assertTrue(clipped.losses.single { it.step == LossStep.CLIPPING }.watts > 0)
        assertTrue(clipped.expectedW <= 1000.0)
    }

    @Test
    fun calibrationIsAnExplicitLineAndBrokenCloudsWidenUncertainty() {
        val cal = PvRealityEngine.assess(array, losses, site, noon, sunny, null, IrradianceBasis.FORECAST, calibrationFactor = 0.9, calibrationConfidence = 0.8)
        val line = cal.losses.single { it.step == LossStep.CALIBRATION }
        assertEquals(cal.breakdown.acOutputW * 0.1, line.watts, 1e-6)
        val clear = PvRealityEngine.uncertainty(IrradianceBasis.FORECAST, 1.0, 0.8, 0.0)
        val broken = PvRealityEngine.uncertainty(IrradianceBasis.FORECAST, 0.5, 0.8, 0.0)
        val sensor = PvRealityEngine.uncertainty(IrradianceBasis.SENSOR, 0.5, 0.8, 0.0)
        assertTrue(broken > clear)
        assertTrue(sensor < clear)
    }

    @Test
    fun aoiLossIsZeroFacingTheSunAndGrowsAtGrazingAngles() {
        assertEquals(1.0, ashraeIam(1.0, 0.05), 1e-12)
        assertEquals(0.0, ashraeIam(0.0, 0.05), 1e-12)
        assertTrue(ashraeIam(0.26, 0.05) < 0.9) // ~75°
        val engine = PvSimulationEngine()
        val dual = engine.simulate(array.copy(tracker = TrackerType.DUAL_AXIS), losses, site, noon, { sunny })
        assertEquals(0.0, dual.losses.aoiLossW, 1e-6)
        val evening = LocalDate.of(2026, 6, 21).atTime(19, 30).atZone(zone).toInstant()
        val flat = engine.simulate(array, losses, site, evening, { sunny })
        assertTrue(flat.losses.aoiLossW / flat.losses.idealDcW > 0.05)
    }
}

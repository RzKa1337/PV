package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.SolarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Closed-form checks; every expected number is worked out in the comment next to it. */
class PvHandCalcTest {
    private val sun60 = SolarPosition(elevationDeg = 60.0, azimuthDeg = 180.0) // zenith 30°, cos z = 0.8660254

    @Test
    fun groundReflectedLightFollowsGhiAlbedoAndViewFactor() {
        // GHI = DNI·cos z + DHI = 800·0.8660254 + 100 = 792.8203 W/m²
        // tilt 30°: ground view factor (1 − cos 30°)/2 = 0.0669873; albedo 0.25 → 792.8203 · 0.25 · 0.0669873 = 13.2766
        val c = poaComponents(Irradiance(800.0, 100.0), sun60, 30.0, 180.0, albedo = 0.25)
        assertEquals(13.2766, c.groundReflected, 0.001)
        // vertical wall: view factor 0.5 → 792.8203 · 0.25 · 0.5 = 99.1025
        assertEquals(99.1025, poaComponents(Irradiance(800.0, 100.0), sun60, 90.0, 180.0, albedo = 0.25).groundReflected, 0.001)
        // flat panel sees no ground at all
        assertEquals(0.0, poaComponents(Irradiance(800.0, 100.0), sun60, 0.0, 180.0, albedo = 0.9).groundReflected, 1e-12)
    }

    @Test
    fun nothingBelowOrAtTheHorizon() {
        for (elevation in listOf(0.0, -0.5, -30.0)) {
            val c = poaComponents(Irradiance(800.0, 100.0), SolarPosition(elevation, 180.0), 30.0, 180.0)
            assertEquals(PoaComponents.ZERO, c)
        }
        assertEquals(0.0, ClearSkyModel().irradiance(SolarPosition(-1.0, 0.0), Instant.parse("2026-06-21T00:00:00Z")).dni, 0.0)
    }

    @Test
    fun beamIncidenceReflectionLossAshrae() {
        // IAM = 1 − b0 (1/cos θ − 1), b0 = 0.05:  θ = 60° → 1 − 0.05·(2 − 1) = 0.95
        assertEquals(0.95, ashraeIam(0.5, 0.05), 1e-12)
        // θ = 80° → cos = 0.173648, 1/cos = 5.758770 → 1 − 0.05·4.758770 = 0.762061
        assertEquals(0.762061, ashraeIam(Math.cos(Math.toRadians(80.0)), 0.05), 1e-5)
        // θ = 89° → the formula goes negative (1 − 0.05·56.3 = −1.8): clamped to 0, never a negative gain
        assertEquals(0.0, ashraeIam(Math.cos(Math.toRadians(89.0)), 0.05), 0.0)
        // behind the panel
        assertEquals(0.0, ashraeIam(-0.2, 0.05), 0.0)
    }

    @Test
    fun cellTemperatureModelsAgainstHandValues() {
        // NOCT: Tc = Ta + POA/800 · (45 − 20) → Ta 25, POA 1000 → 25 + 31.25 = 56.25 °C; at 800 W/m², 20 °C → 45 °C
        assertEquals(56.25, PvEstimator.cellTemperatureC(1000.0, 25.0), 1e-9)
        assertEquals(45.0, PvEstimator.cellTemperatureC(800.0, 20.0), 1e-9)
        assertEquals(56.25, PvEstimator.cellTemperatureC(1000.0, 25.0, null), 1e-9) // unknown wind → NOCT
        // Faiman: Tc = Ta + POA/(25 + 6.84 v): v = 0 → 25 + 1000/25 = 65 °C; v = 5 → 25 + 1000/59.2 = 41.8919 °C
        assertEquals(65.0, PvEstimator.cellTemperatureC(1000.0, 25.0, 0.0), 1e-9)
        assertEquals(41.8919, PvEstimator.cellTemperatureC(1000.0, 25.0, 5.0), 1e-4)
        // negative wind values from a bad feed are treated as calm
        assertEquals(65.0, PvEstimator.cellTemperatureC(1000.0, 25.0, -3.0), 1e-9)
        // dark: cell = air
        assertEquals(12.0, PvEstimator.cellTemperatureC(0.0, 12.0, 3.0), 1e-12)
    }

    @Test
    fun temperatureCoefficientIsAppliedOnceWithTheRightSign() {
        val gamma = -0.004
        // (1 + γ (Tc − 25)) / 0.95:  Tc 45 → 0.92/0.95 = 0.968421;  Tc 25 → 1/0.95 = 1.052632;  Tc −5 → 1.12/0.95 = 1.178947
        assertEquals(0.968421, PvEstimator.cellTemperatureFactor(45.0, gamma), 1e-6)
        assertEquals(1.052632, PvEstimator.cellTemperatureFactor(25.0, gamma), 1e-6)
        assertEquals(1.178947, PvEstimator.cellTemperatureFactor(-5.0, gamma), 1e-6)
        assertEquals(1.0, PvEstimator.cellTemperatureFactor(null, gamma), 0.0)
        // Hotter is never better, colder never worse
        assertTrue(PvEstimator.cellTemperatureFactor(60.0, gamma) < PvEstimator.cellTemperatureFactor(30.0, gamma))
        // A positive coefficient (data entry error) cannot turn heat into a gain: it is clamped to 0.
        assertEquals(0.0, PvSystem(temperatureCoefficient = 0.004).sanitized().temperatureCoefficient, 0.0)
        assertEquals(PvSystem.MIN_TEMPERATURE_COEFFICIENT, PvSystem(temperatureCoefficient = -0.5).sanitized().temperatureCoefficient, 0.0)
    }

    @Test
    fun clearSkyAtZenithMatchesMeinelFormula() {
        // 3 January: E0 = 1361 · (1 + 0.033 cos(2π·3/365)) = 1361 · 1.032956 = 1405.85 W/m²
        // zenith 0 → Kasten–Young air mass 0.99971, DNI = E0 · 0.7^(0.99971^0.678) = 1405.85 · 0.70005 = 984.2 W/m²; DHI = 10 % of it
        val overhead = ClearSkyModel().irradiance(SolarPosition(90.0, 0.0), Instant.parse("2026-01-03T12:00:00Z"))
        assertEquals(984.2, overhead.dni, 0.5)
        assertEquals(98.4, overhead.dhi, 0.1)
        assertEquals(1082.6, overhead.ghi(SolarPosition(90.0, 0.0)), 0.6)
    }

    @Test
    fun powerIsLinearInKwpAndZeroWithoutLight() {
        val model = IrradianceModel { _, _ -> Irradiance(700.0, 100.0, 20.0, 2.0) }
        val loc = com.solartracker.pro.core.solar.GeoLocation(52.23, 21.01)
        val t = Instant.parse("2026-06-21T10:40:00Z")
        val one = PvEstimator(model).powerKw(PvSystem(peakPowerKw = 1.0, tiltDeg = 30.0), loc, t)
        for (kwp in listOf(0.3, 2.09, 5.0, 12.5)) {
            assertEquals(kwp * one, PvEstimator(model).powerKw(PvSystem(peakPowerKw = kwp, tiltDeg = 30.0), loc, t), 1e-9)
        }
        // W → Wh → kWh: constant power over an hour is exactly that many kWh
        val e = PvEstimator(model).energyKwh(PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0), loc, Instant.parse("2026-06-21T10:00:00Z"), Instant.parse("2026-06-21T10:30:00Z"), 1)
        val p = PvEstimator(model).powerKw(PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0), loc, Instant.parse("2026-06-21T10:15:00Z"))
        assertEquals(p * 0.5, e, 0.01 * p)
        // No light at all (dark irradiance model) → exactly zero, also with a known temperature
        val dark = IrradianceModel { _, _ -> Irradiance(0.0, 0.0, 15.0, 3.0) }
        assertEquals(0.0, PvEstimator(dark).powerKw(PvSystem(peakPowerKw = 5.0), loc, t), 0.0)
    }
}

package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * PvEstimator (annual-PR model used by live/forecast) and PvSimulationEngine (explicit loss chain used by the designer
 * and the reality check) share the irradiance, transposition, IAM and cell-temperature code. With the loss chain
 * reduced to one lumped efficiency η = PR the two must differ ONLY by the parts the PR already contains
 * (typical temperature loss 0.95 and typical angle loss), i.e. exactly   estimator · 0.95 · angleFactor = chain(η = PR).
 * If any loss were applied twice (or temperature / IAM / γ in only one of them) this identity breaks.
 */
class EngineConsistencyTest {
    private val loc = GeoLocation(52.23, 21.01)
    private val pr = 0.8
    private val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0, performanceRatio = pr, temperatureCoefficient = -0.0035)
    private val array = PvArrayConfig(1, 5000.0, 30.0, 180.0, temperatureCoefficient = -0.0035)
    private val lumped = LossProfile(
        soiling = 0.0, mismatch = 0.0, dcWiring = 0.0, acWiring = 0.0, degradationPerYear = 0.0, ageYears = 0.0,
        inverterPeakEfficiency = pr, inverterSelfConsumption = 0.0, mpptEfficiency = 1.0,
    )

    private fun check(utc: String, dni: Double, dhi: Double, airC: Double, wind: Double?) {
        val t = Instant.parse(utc)
        val irradiance = Irradiance(dni, dhi, airC, wind)
        val est = PvEstimator { _, _ -> irradiance }
        val estimatorW = est.powerKw(system, loc, t) * 1000
        val chain = PvSimulationEngine().simulate(array, lumped, loc, t, { PvConditions(irradiance, airC, wind) })
        val angle = est.typicalAngleFactor(loc, 30.0, 180.0)
        assertTrue("sun must be up for $utc", chain.sun.isAboveHorizon)
        assertEquals("$utc", chain.acPowerW, estimatorW * PvEstimator.TYPICAL_TEMPERATURE_FACTOR * angle, 1e-6 * chain.acPowerW)
    }

    @Test
    fun estimatorEqualsLumpedLossChainExceptForTheDividedOutTypicalLosses() {
        check("2026-06-21T05:00:00Z", 700.0, 100.0, 22.0, 2.0)   // summer morning, wind known (Faiman)
        check("2026-06-21T10:40:00Z", 850.0, 90.0, 30.0, 0.5)    // summer noon, hot and calm
        check("2026-03-20T14:00:00Z", 500.0, 120.0, 3.0, null)   // spring afternoon, NOCT cell model (no wind)
        check("2026-12-21T10:50:00Z", 600.0, 40.0, -8.0, 4.0)    // winter noon, cold (temperature gain)
        check("2026-09-23T15:30:00Z", 0.0, 150.0, 14.0, 1.0)     // overcast: diffuse only
    }

    @Test
    fun noAmbientTemperatureMeansTheTypicalLossInBothEngines() {
        val t = Instant.parse("2026-06-21T10:40:00Z")
        val irradiance = Irradiance(800.0, 100.0)
        val est = PvEstimator { _, _ -> irradiance }
        val chain = PvSimulationEngine().simulate(array, lumped, loc, t, { PvConditions(irradiance) })
        // Estimator: temperature factor 1 (the PR already holds the typical loss); chain: explicit 0.95.
        assertEquals(chain.acPowerW, est.powerKw(system, loc, t) * 1000 * PvEstimator.TYPICAL_TEMPERATURE_FACTOR * est.typicalAngleFactor(loc, 30.0, 180.0), 1e-6 * chain.acPowerW)
    }

    @Test
    fun sunAndPlaneGeometryAreShared() {
        val t = Instant.parse("2026-06-21T08:00:00Z")
        val sun = SolarCalculator.position(loc, t)
        val c = PvSimulationEngine().simulate(array, lumped, loc, t, { PvConditions(Irradiance(700.0, 100.0)) })
        assertEquals(sun.elevationDeg, c.sun.elevationDeg, 0.0)
        assertEquals(30.0, c.surfaceTiltDeg, 0.0)
        assertEquals(180.0, c.surfaceAzimuthDeg, 0.0)
    }
}

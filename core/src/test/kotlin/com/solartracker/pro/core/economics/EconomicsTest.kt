package com.solartracker.pro.core.economics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EconomicsTest {
    @Test
    fun simpleCaseWithoutEscalationOrDegradation() {
        val r = EconomicsEngine.evaluate(
            SystemCosts(pv = 10_000.0, installation = 2_000.0),
            TariffAssumptions(gridPricePerKwh = 1.0, feedInPricePerKwh = 0.0, discountRate = 0.0),
            EnergyYear(productionKwh = 5000.0, selfConsumedKwh = 3000.0, exportedKwh = 2000.0),
            lifetimeYears = 20, degradationPerYear = 0.0,
        )
        assertEquals(12_000.0, r.investment, 0.0)
        assertEquals(3000.0, r.firstYearSavings, 1e-9)
        assertEquals(4.0, r.simplePaybackYears!!, 1e-9)
        assertEquals(4.0, r.paybackYears!!, 1e-9)
        assertEquals((3000.0 * 20 - 12_000) / 12_000 * 100, r.roiPercent, 1e-9)
        assertEquals(3000.0 * 20 - 12_000, r.npv, 1e-6)
        assertEquals(12_000.0 / (5000.0 * 20), r.lcoe!!, 1e-12)
        assertNull(r.storageCostPerKwh)
    }

    @Test
    fun discountingDegradationMaintenanceAndBattery() {
        val base = EconomicsEngine.evaluate(SystemCosts(pv = 12_000.0), TariffAssumptions(1.0, discountRate = 0.05), EnergyYear(5000.0, 3000.0, 2000.0))
        val worse = EconomicsEngine.evaluate(SystemCosts(pv = 12_000.0, maintenancePerYear = 300.0), TariffAssumptions(1.0, discountRate = 0.05), EnergyYear(5000.0, 3000.0, 2000.0), degradationPerYear = 0.01)
        assertTrue(worse.npv < base.npv)
        assertTrue(worse.paybackYears!! > base.paybackYears!!)
        val esc = EconomicsEngine.evaluate(SystemCosts(pv = 12_000.0), TariffAssumptions(1.0, priceEscalation = 0.05), EnergyYear(5000.0, 3000.0, 2000.0))
        assertTrue(esc.paybackYears!! < base.paybackYears!!)
        val battery = EconomicsEngine.evaluate(SystemCosts(pv = 10_000.0, battery = 9_000.0, batteryLifeYears = 15), TariffAssumptions(1.0), EnergyYear(5000.0, 4500.0, 500.0, batteryThroughputKwh = 1500.0))
        assertEquals(9_000.0 / (1500.0 * 15), battery.storageCostPerKwh!!, 1e-12)
        val never = EconomicsEngine.evaluate(SystemCosts(pv = 1e7), TariffAssumptions(1.0), EnergyYear(100.0, 100.0, 0.0))
        assertNull(never.paybackYears)
    }

    @Test
    fun offGridGeneratorSavingsAndPeriodCost() {
        val r = EconomicsEngine.evaluate(SystemCosts(pv = 6000.0), TariffAssumptions(1.0, generatorPricePerKwh = 3.0, discountRate = 0.0), EnergyYear(2000.0, 2000.0, 0.0, generatorAvoidedKwh = 2000.0), lifetimeYears = 10, degradationPerYear = 0.0)
        assertEquals(6000.0, r.firstYearSavings, 1e-9)
        assertEquals(1.0, r.paybackYears!!, 1e-9)
        assertEquals(10.0 * 1.0 + 2.0 * 3.0 - 5.0 * 0.2, EconomicsEngine.periodCost(10.0, 2.0, 5.0, TariffAssumptions(1.0, 0.2, 3.0)), 1e-12)
    }
}

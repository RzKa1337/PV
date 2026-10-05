package com.solartracker.pro.core.ems

import com.solartracker.pro.core.energy.BatteryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class EnergyOptimizationEngineTest {
    private val zone = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    /** 48 hourly slots: bell-shaped PV 6–18 h peaking at [peakKw], constant 0.5 kW load. */
    private fun day(peakKw: Double, load: Double = 0.5, days: Int = 2) = (0 until 24 * days).map { i ->
        val h = i % 24
        val pv = if (h in 6..18) peakKw * Math.sin(Math.PI * (h - 6) / 12.0) else 0.0
        EnergySlot(t0.plusSeconds(i * 3600L), 1.0, pv, load, pv * 0.8, 0.7)
    }

    private val battery = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0, maxSocPercent = 100.0,
        maxChargePowerKw = 3.0, maxDischargePowerKw = 3.0, chargeEfficiencyPercent = 100.0, dischargeEfficiencyPercent = 100.0)

    @Test
    fun energyIsConservedInDispatch() {
        val input = EmsInput(day(4.0), battery, 50.0, zone = zone)
        val sim = EnergyOptimizationEngine.simulate(input, input.slots.map { it.loadKw })
        sim.forEach { r ->
            val supply = r.slot.pvKw + r.gridImportKw + r.generatorKw + r.unservedKw
            val demand = r.slot.loadKw + r.batteryKw + r.exportOrCurtailKw
            assertEquals(supply, demand, 1e-9)
        }
        val soc = sim.mapNotNull { it.socPercent }
        assertTrue(soc.all { it in 19.99..100.0 })
    }

    @Test
    fun surplusWindowsAndLoadScheduling() {
        val washer = FlexibleLoad("w", "Pralka", 2.0, 2.0, priority = 2)
        val nightOnly = FlexibleLoad("n", "Grzałka nocą", 2.0, 1.0, earliest = LocalTime.of(22, 0), latest = LocalTime.of(23, 59))
        val plan = EnergyOptimizationEngine.plan(EmsInput(day(5.0), battery, 100.0, listOf(washer, nightOnly), zone = zone))
        val w = plan.windows.first { it.kind == WindowKind.SURPLUS }
        assertTrue(w.start.atZone(zone).hour in 6..8 && w.end.atZone(zone).hour in 17..19)
        val rw = plan.recommendations.first { it.load.id == "w" }
        assertEquals(RecommendationKind.RUN, rw.kind)
        val hour = rw.start!!.atZone(zone).hour
        assertTrue("washer around noon, was $hour", hour in 10..13)
        assertTrue(rw.surplusCoverage > 0.9)
        val rn = plan.recommendations.first { it.load.id == "n" }
        assertEquals(RecommendationKind.NOT_RECOMMENDED, rn.kind)
        assertTrue(plan.decisions.any { it.startsWith("Uruchom Pralka") })
    }

    @Test
    fun lowSurplusIsNotRecommended() {
        val plan = EnergyOptimizationEngine.plan(EmsInput(day(0.8), battery, 30.0, listOf(FlexibleLoad("h", "Bojler", 3.0, 2.0)), zone = zone))
        assertEquals(RecommendationKind.NOT_RECOMMENDED, plan.recommendations.single().kind)
    }

    @Test
    fun offGridGeneratorStartsAtLowSoc() {
        val gen = GeneratorConfig(ratedKw = 3.0, startSocPercent = 25.0, stopSocPercent = 80.0, litersPerKwh = 0.4)
        val slots = day(0.3, load = 1.0)
        val withGen = EnergyOptimizationEngine.plan(EmsInput(slots, battery, 40.0, generator = gen, gridAvailable = false, zone = zone))
        val advice = withGen.generator
        assertNotNull(advice)
        assertTrue(advice!!.fuelLiters!! > 0)
        assertEquals(0.0, withGen.unservedKwh, 1e-9)
        val noGen = EnergyOptimizationEngine.plan(EmsInput(slots, battery, 40.0, gridAvailable = false, zone = zone))
        assertNull(noGen.generator)
        assertTrue(noGen.unservedKwh > 1.0)
        assertNotNull(noGen.batteryEmptyAt)
        assertTrue(noGen.decisions.any { it.startsWith("Ryzyko niedoboru") })
    }

    @Test
    fun periodsAndDailyBalance() {
        val slots = day(4.0, days = 7)
        val daily = EnergyOptimizationEngine.daily(slots, zone)
        assertEquals(7, daily.size)
        assertTrue(daily.all { it.surplus })
        assertEquals(12.0, daily.first().loadKwh, 1e-9)
        val p = EnergyOptimizationEngine.periods(slots.take(24), zone).associateBy { it.label }
        assertEquals(12.0, p.values.sumOf { it.loadKwh }, 1e-9)
        assertTrue(p.getValue("Noc").balanceKwh < 0)
        assertTrue(p.getValue("Dzień").balanceKwh > 0)
    }
}

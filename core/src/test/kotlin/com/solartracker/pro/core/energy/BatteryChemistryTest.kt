package com.solartracker.pro.core.energy

import com.solartracker.pro.core.quality.DataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryChemistryTest {

    @Test
    fun restingVoltageToSoc() {
        val agm = BatteryChemistry.socFromRestingVoltage(BatteryType.AGM, 12.24, 12.0, atRest = true) // 2.04 V/cell
        assertEquals(50.0, agm.value!!, 0.5)
        assertEquals(DataKind.ESTIMATED, agm.kind)
        val lfp = BatteryChemistry.socFromRestingVoltage(BatteryType.LIFEPO4, 52.48, 51.2, atRest = true) // 16 × 3.28
        assertEquals(50.0, lfp.value!!, 0.5)
        assertTrue("flat LFP curve → wide band", lfp.uncertainty!! > agm.uncertainty!!)
        val loaded = BatteryChemistry.socFromRestingVoltage(BatteryType.AGM, 12.0, 12.0, atRest = false)
        assertNull(loaded.value)
        assertEquals(DataKind.UNKNOWN, loaded.kind)
        assertEquals(100.0, BatteryChemistry.socFromRestingVoltage(BatteryType.GEL, 13.5, 12.0, true).value!!, 0.0)
        assertEquals(0.0, BatteryChemistry.socFromRestingVoltage(BatteryType.LEAD_ACID, 10.5, 12.0, true).value!!, 0.0)
    }

    @Test
    fun peukertTemperatureAndCharging() {
        // 2.4 kWh lead acid discharged at 10× the C20 power loses capacity; LiFePO4 barely.
        val lead = BatteryChemistry.effectiveCapacityKwh(BatteryType.LEAD_ACID, 2.4, 1.2)
        val lfp = BatteryChemistry.effectiveCapacityKwh(BatteryType.LIFEPO4, 2.4, 1.2)
        assertTrue(lead < 2.0)
        assertTrue(lfp > 2.2 && lfp > lead + 0.3)
        assertEquals(2.4, BatteryChemistry.effectiveCapacityKwh(BatteryType.AGM, 2.4, 0.05), 1e-9)
        assertTrue(BatteryChemistry.effectiveCapacityKwh(BatteryType.AGM, 2.4, 0.05, cellTempC = 0.0) < 2.0)
        assertFalse(BatteryChemistry.canCharge(BatteryType.LIFEPO4, -5.0))
        assertTrue(BatteryChemistry.canCharge(BatteryType.AGM, -5.0))
        assertTrue(BatteryChemistry.canCharge(BatteryType.LIFEPO4, null))
    }

    @Test
    fun cycleLifeAndAging() {
        assertEquals(6000.0, BatteryChemistry.cycleLife(BatteryType.LIFEPO4, 0.8), 1e-9)
        assertTrue(BatteryChemistry.cycleLife(BatteryType.AGM, 0.8) < BatteryChemistry.cycleLife(BatteryType.AGM, 0.5))
        val a = BatteryAgingModel.estimate(BatteryType.LIFEPO4, 10.0, 30_000.0, 0.8, 2.0)
        assertEquals(3000.0, a.equivalentFullCycles, 1e-9)
        assertEquals(1 - 0.2 * 0.5 - 0.04, a.stateOfHealth, 1e-9)
        assertEquals(3000.0, a.remainingCycles, 1e-9)
    }

    @Test
    fun timeToFullAndMinimum() {
        val b = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0, maxSocPercent = 100.0, chargeEfficiencyPercent = 100.0, dischargeEfficiencyPercent = 100.0)
        assertEquals(4.0 + 2.0, BatteryTiming.hoursToFull(b, 50.0, 1.0)!!, 1e-9) // 4 h bulk to 90%, CV 10% at half speed
        assertEquals(0.0, BatteryTiming.hoursToFull(b, 100.0, 1.0)!!, 0.0)
        assertNull(BatteryTiming.hoursToFull(b, 50.0, 0.0))
        assertEquals(3.0, BatteryTiming.hoursToMinimum(b, 50.0, 1.0)!!, 1e-9)
    }

    @Test
    fun capacityFromHistory() {
        val segs = listOf(
            BatteryCapacityEstimator.Segment(30.0, 80.0, 4.6, 0.0),
            BatteryCapacityEstimator.Segment(80.0, 40.0, 0.0, 3.6),
            BatteryCapacityEstimator.Segment(40.0, 45.0, 1.0, 0.0), // too small
        )
        val cap = BatteryCapacityEstimator.estimateKwh(segs, 0.95, 0.95)!!
        assertEquals(listOf(4.6 * 0.95 / 0.5, 3.6 / 0.95 / 0.4).sorted()[1], cap, 1e-9)
        assertNull(BatteryCapacityEstimator.estimateKwh(segs.drop(2), 0.95, 0.95))
    }
}

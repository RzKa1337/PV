package com.solartracker.pro.core.energy

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EnergyFlowSimulatorTest {

    private val simulator = EnergyFlowSimulator()
    private val day = LocalDate.of(2024, 6, 21)

    /** Battery with round numbers: 10 kWh usable, no losses unless specified. */
    private fun battery(
        initialSoc: Double = 50.0,
        minSoc: Double = 0.0,
        maxSoc: Double = 100.0,
        chargeKw: Double = 10.0,
        dischargeKw: Double = 10.0,
        chargeEff: Double = 100.0,
        dischargeEff: Double = 100.0,
    ) = BatteryStorage(
        nominalCapacityKwh = 10.0,
        usableCapacityPercent = 100.0,
        initialSocPercent = initialSoc,
        minSocPercent = minSoc,
        maxSocPercent = maxSoc,
        maxChargePowerKw = chargeKw,
        maxDischargePowerKw = dischargeKw,
        chargeEfficiencyPercent = chargeEff,
        dischargeEfficiencyPercent = dischargeEff,
    )

    /** Hourly synthetic PV series starting at midnight of [day]: one value per hour. */
    private fun series(vararg pvKwh: Double, startDate: LocalDate = day): PvSeries {
        val n = pvKwh.size
        return PvSeries(
            start = List(n) { Instant.parse("2024-06-21T00:00:00Z").plusSeconds(3600L * it) },
            durationHours = DoubleArray(n) { 1.0 },
            pvKwh = pvKwh,
            hourOfDay = IntArray(n) { it % 24 },
            date = List(n) { startDate.plusDays((it / 24).toLong()) },
        )
    }

    private fun load(kw: Double) = ConsumptionProfile.constant(kw)

    // 1
    @Test
    fun pvAboveConsumption_chargesBattery() {
        val r = simulator.run(series(3.0), load(1.0), battery(initialSoc = 50.0))
        val s = r.steps.single()
        assertEquals(1.0, s.directUseKwh, 1e-9)
        assertEquals(2.0, s.toBatteryKwh, 1e-9)
        assertEquals(0.0, s.surplusKwh, 1e-9)
        assertEquals(0.0, s.gridKwh, 1e-9)
        assertEquals(70.0, s.socPercent, 1e-9)
    }

    // 2
    @Test
    fun pvBelowConsumption_dischargesBattery() {
        val r = simulator.run(series(0.5), load(2.0), battery(initialSoc = 50.0))
        val s = r.steps.single()
        assertEquals(0.5, s.directUseKwh, 1e-9)
        assertEquals(1.5, s.fromBatteryKwh, 1e-9)
        assertEquals(0.0, s.gridKwh, 1e-9)
        assertEquals(35.0, s.socPercent, 1e-9)
    }

    // 3
    @Test
    fun fullBattery_extraEnergyBecomesSurplus() {
        val r = simulator.run(series(5.0, 5.0), load(1.0), battery(initialSoc = 80.0, maxSoc = 100.0))
        val first = r.steps[0]
        assertEquals(2.0, first.toBatteryKwh, 1e-9) // only 2 kWh room
        assertEquals(2.0, first.surplusKwh, 1e-9)
        assertEquals(100.0, first.socPercent, 1e-9)
        val second = r.steps[1]
        assertEquals(0.0, second.toBatteryKwh, 1e-9)
        assertEquals(4.0, second.surplusKwh, 1e-9)
    }

    @Test
    fun maxSocBelow100_isRespected() {
        val r = simulator.run(series(10.0), load(0.0), battery(initialSoc = 50.0, maxSoc = 90.0))
        assertEquals(90.0, r.steps.single().socPercent, 1e-9)
        assertEquals(6.0, r.steps.single().surplusKwh, 1e-9)
    }

    // 4
    @Test
    fun minSoc_stopsDischarge() {
        val r = simulator.run(series(0.0, 0.0), load(3.0), battery(initialSoc = 30.0, minSoc = 20.0))
        val first = r.steps[0]
        assertEquals(1.0, first.fromBatteryKwh, 1e-9)
        assertEquals(2.0, first.gridKwh, 1e-9)
        assertEquals(20.0, first.socPercent, 1e-9)
        val second = r.steps[1]
        assertEquals(0.0, second.fromBatteryKwh, 1e-9)
        assertEquals(3.0, second.gridKwh, 1e-9)
        assertEquals(20.0, second.socPercent, 1e-9)
    }

    @Test
    fun startBelowMinSoc_doesNotDischargeButCanCharge() {
        val r = simulator.run(series(0.0, 2.0), load(1.0), battery(initialSoc = 5.0, minSoc = 10.0))
        assertEquals(0.0, r.steps[0].fromBatteryKwh, 1e-9)
        assertEquals(1.0, r.steps[0].gridKwh, 1e-9)
        assertEquals(1.0, r.steps[1].toBatteryKwh, 1e-9)
        assertEquals(15.0, r.steps[1].socPercent, 1e-9)
    }

    // 5
    @Test
    fun chargePowerLimit_isApplied() {
        val r = simulator.run(series(4.0), load(0.0), battery(initialSoc = 0.0, chargeKw = 1.5))
        val s = r.steps.single()
        assertEquals(1.5, s.toBatteryKwh, 1e-9)
        assertEquals(2.5, s.surplusKwh, 1e-9)
        assertEquals(1.5, s.chargePowerKw, 1e-9)
    }

    // 6
    @Test
    fun dischargePowerLimit_isApplied() {
        val r = simulator.run(series(0.0), load(3.0), battery(initialSoc = 100.0, dischargeKw = 0.8))
        val s = r.steps.single()
        assertEquals(0.8, s.fromBatteryKwh, 1e-9)
        assertEquals(2.2, s.gridKwh, 1e-9)
    }

    @Test
    fun powerLimitsScaleWithStepLength() {
        val quarter = PvSeries(
            start = listOf(Instant.parse("2024-06-21T12:00:00Z")),
            durationHours = doubleArrayOf(0.25),
            pvKwh = doubleArrayOf(2.0),
            hourOfDay = intArrayOf(12),
            date = listOf(day),
        )
        val r = simulator.run(quarter, load(0.0), battery(initialSoc = 0.0, chargeKw = 2.0))
        assertEquals(0.5, r.steps.single().toBatteryKwh, 1e-9) // 2 kW × 0.25 h
    }

    // 7
    @Test
    fun efficiency_isAppliedOnChargeAndDischarge() {
        val b = battery(initialSoc = 50.0, chargeEff = 80.0, dischargeEff = 50.0)
        val charge = simulator.run(series(3.0), load(1.0), b).steps.single()
        assertEquals(2.0, charge.toBatteryKwh, 1e-9)
        assertEquals(66.0, charge.socPercent, 1e-9) // 5 + 2 × 0.8 = 6.6 kWh
        assertEquals(0.4, charge.batteryLossKwh, 1e-9)

        val discharge = simulator.run(series(0.0), load(1.0), b).steps.single()
        assertEquals(1.0, discharge.fromBatteryKwh, 1e-9)
        assertEquals(30.0, discharge.socPercent, 1e-9) // 5 − 1 / 0.5 = 3 kWh
        assertEquals(1.0, discharge.batteryLossKwh, 1e-9)
    }

    @Test
    fun efficiency_limitsDeliverableEnergyNearMinSoc() {
        val b = battery(initialSoc = 20.0, minSoc = 10.0, dischargeEff = 90.0)
        val s = simulator.run(series(0.0), load(5.0), b).steps.single()
        assertEquals(0.9, s.fromBatteryKwh, 1e-9) // 1 kWh in cells × 0.9
        assertEquals(10.0, s.socPercent, 1e-9)
    }

    // 8
    @Test
    fun nextDayStartsWithPreviousDayFinalSoc() {
        val pv = DoubleArray(72) { h -> if (h % 24 in 9..15) 2.0 else 0.0 }
        val b = battery(initialSoc = 30.0, minSoc = 10.0, chargeEff = 95.0, dischargeEff = 95.0)
        val r = simulator.run(series(*pv), load(0.6), b)
        assertEquals(3, r.days.size)
        assertEquals(30.0, r.days[0].balance.startSocPercent!!, 1e-9)
        for (i in 1 until r.days.size) {
            assertEquals(r.days[i - 1].balance.endSocPercent!!, r.days[i].balance.startSocPercent!!, 1e-9)
        }
        // Simulating day 2 on its own from day 1's final SOC gives identical results.
        val day2Alone = simulator.run(
            series(*pv.copyOfRange(24, 48), startDate = day.plusDays(1)),
            load(0.6),
            b,
            startSocPercent = r.days[0].balance.endSocPercent,
        )
        assertEquals(r.days[1].balance.endSocPercent!!, day2Alone.balance.endSocPercent!!, 1e-9)
        assertEquals(r.days[1].balance.gridKwh, day2Alone.balance.gridKwh, 1e-9)
    }

    @Test
    fun slice_keepsCarriedOverSoc() {
        val pv = DoubleArray(48) { h -> if (h % 24 in 10..13) 3.0 else 0.0 }
        val r = simulator.run(series(*pv), load(0.5), battery(initialSoc = 40.0))
        val tomorrow = r.slice(day.plusDays(1), day.plusDays(1))
        assertEquals(r.days[0].balance.endSocPercent!!, tomorrow.balance.startSocPercent!!, 1e-9)
        assertEquals(24, tomorrow.steps.size)
    }

    // 9
    @Test
    fun noBattery_behavesLikePvOnly() {
        val r = simulator.run(series(3.0, 0.0, 1.0), load(1.5), battery = null)
        assertFalse(r.hasBattery)
        assertNull(r.statistics)
        assertNull(r.balance.endSocPercent)
        val b = r.balance
        assertEquals(4.0, b.pvKwh, 1e-9)
        assertEquals(4.5, b.consumptionKwh, 1e-9)
        assertEquals(2.5, b.directUseKwh, 1e-9)
        assertEquals(1.5, b.surplusKwh, 1e-9)
        assertEquals(2.0, b.gridKwh, 1e-9)
        assertEquals(0.0, b.toBatteryKwh + b.fromBatteryKwh + b.batteryLossKwh, 0.0)
    }

    @Test
    fun pvProductionMatchesExistingPvEstimator() {
        val system = PvSystem()
        val warsaw = GeoLocation(52.2297, 21.0122)
        val zone = ZoneId.of("Europe/Warsaw")
        val r = simulator.simulate(system, warsaw, day, 1, zone, load(0.5), battery = null)
        val expected = PvEstimator().dailyEnergyKwh(system, warsaw, day, zone)
        assertEquals(expected, r.balance.pvKwh, expected * 0.01)
        assertEquals(96, r.steps.size)
    }

    // 10
    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityBattery_isRejected() {
        simulator.run(series(1.0), load(1.0), BatteryStorage(nominalCapacityKwh = 0.0))
    }

    @Test
    fun invalidParameters_areReported() {
        fun errors(b: BatteryStorage) = b.validate()
        assertTrue(BatteryStorage().isValid)
        assertTrue(BatteryValidationError.CAPACITY_NOT_POSITIVE in errors(BatteryStorage(nominalCapacityKwh = -1.0)))
        assertTrue(BatteryValidationError.SOC_OUT_OF_RANGE in errors(BatteryStorage(initialSocPercent = 120.0)))
        assertTrue(BatteryValidationError.SOC_OUT_OF_RANGE in errors(BatteryStorage(minSocPercent = -5.0)))
        assertTrue(BatteryValidationError.MIN_SOC_NOT_BELOW_MAX in errors(BatteryStorage(minSocPercent = 80.0, maxSocPercent = 80.0)))
        assertTrue(BatteryValidationError.CHARGE_POWER_NOT_POSITIVE in errors(BatteryStorage(maxChargePowerKw = 0.0)))
        assertTrue(BatteryValidationError.DISCHARGE_POWER_NOT_POSITIVE in errors(BatteryStorage(maxDischargePowerKw = -2.0)))
        assertTrue(BatteryValidationError.EFFICIENCY_OUT_OF_RANGE in errors(BatteryStorage(chargeEfficiencyPercent = 0.0)))
        assertTrue(BatteryValidationError.EFFICIENCY_OUT_OF_RANGE in errors(BatteryStorage(dischargeEfficiencyPercent = 101.0)))
        assertTrue(BatteryValidationError.USABLE_CAPACITY_OUT_OF_RANGE in errors(BatteryStorage(usableCapacityPercent = 0.0)))
        assertTrue(BatteryValidationError.CAPACITY_NOT_POSITIVE in errors(BatteryStorage(nominalCapacityKwh = Double.NaN)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun minAboveMaxBattery_isRejectedBySimulator() {
        simulator.run(series(1.0), load(1.0), BatteryStorage(minSocPercent = 90.0, maxSocPercent = 50.0))
    }

    // --- Conservation and realistic scenarios ---

    @Test
    fun energyIsConserved() {
        val system = PvSystem(peakPowerKw = 2.09, tiltDeg = 30.0)
        val warsaw = GeoLocation(52.2297, 21.0122)
        val zone = ZoneId.of("Europe/Warsaw")
        val b = BatteryStorage(nominalCapacityKwh = 16.0)
        val r = simulator.simulate(system, warsaw, day, 7, zone, load(8.0 / 24), b)
        for (s in r.steps) {
            assertEquals(s.pvKwh, s.directUseKwh + s.toBatteryKwh + s.surplusKwh, 1e-9)
            assertEquals(s.consumptionKwh, s.directUseKwh + s.fromBatteryKwh + s.gridKwh, 1e-9)
            assertTrue(s.socPercent in b.minSocPercent - 1e-9..b.maxSocPercent + 1e-9)
            assertTrue(s.chargePowerKw <= b.maxChargePowerKw + 1e-9)
            assertTrue(s.dischargePowerKw <= b.maxDischargePowerKw + 1e-9)
        }
        val bal = r.balance
        val storedChange = b.storedKwh(bal.endSocPercent!!) - b.storedKwh(bal.startSocPercent!!)
        assertEquals(bal.toBatteryKwh - bal.fromBatteryKwh - bal.batteryLossKwh, storedChange, 1e-6)
    }

    @Test
    fun batteryReducesSurplusAndGridCompared() {
        val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 35.0)
        val warsaw = GeoLocation(52.2297, 21.0122)
        val zone = ZoneId.of("Europe/Warsaw")
        val pv = simulator.pvSeries(system, warsaw, day, 3, zone)
        val without = simulator.run(pv, load(0.4), null)
        val with = simulator.run(pv, load(0.4), BatteryStorage())
        assertEquals(without.balance.pvKwh, with.balance.pvKwh, 1e-9)
        assertTrue(with.balance.surplusKwh < without.balance.surplusKwh)
        assertTrue(with.balance.gridKwh < without.balance.gridKwh)
    }

    @Test
    fun hourlyAggregation_hasTwentyFourHoursPerDay() {
        val system = PvSystem()
        val warsaw = GeoLocation(52.2297, 21.0122)
        val r = simulator.simulate(system, warsaw, day, 2, ZoneId.of("Europe/Warsaw"), load(0.3), BatteryStorage())
        assertEquals(48, r.hourly.size)
        assertEquals(r.balance.pvKwh, r.hourly.sumOf { it.pvKwh }, 1e-9)
    }

    // --- Statistics ---

    @Test
    fun statistics_countCyclesAndFullEmptyHours() {
        // 10 kWh battery: charge 0→100% in 5 h, then discharge 100→0% in 5 h.
        val pv = DoubleArray(10) { if (it < 5) 2.0 else 0.0 }
        val consumption = ConsumptionProfile.fromPeriods(listOf(ConsumptionPeriod(5, 10, 2.0)))
        val r = simulator.run(series(*pv), consumption, battery(initialSoc = 0.0))
        val st = r.statistics!!
        assertEquals(1.0, st.equivalentFullCycles, 1e-9)
        assertEquals(0.0, st.minSocPercent, 1e-9)
        assertEquals(100.0, st.maxSocPercent, 1e-9)
        assertEquals(10.0, st.chargedKwh, 1e-9)
        assertEquals(10.0, st.dischargedKwh, 1e-9)
        assertEquals(1.0, st.hoursFull, 1e-9) // end of hour 5
        assertEquals(1.0, st.hoursEmpty, 1e-9) // end of hour 10
        assertEquals(50.0, st.averageSocPercent, 1e-9)
    }

    // --- Consumption profile ---

    @Test
    fun consumptionProfile_periodsFromSpecification() {
        val p = ConsumptionProfile.fromPeriods(
            listOf(
                ConsumptionPeriod(0, 6, 0.5),
                ConsumptionPeriod(6, 10, 1.0),
                ConsumptionPeriod(10, 18, 2.0),
                ConsumptionPeriod(18, 24, 1.0),
            ),
        )
        assertEquals(29.0, p.dailyKwh, 1e-9)
        assertEquals(0.5, p.powerKwAtHour(3), 0.0)
        assertEquals(2.0, p.powerKwAtHour(17), 0.0)
        assertEquals(1.0, p.powerKwAtHour(23), 0.0)
        assertEquals(12.0, ConsumptionProfile.constant(0.5).dailyKwh, 1e-9)
    }

    @Test
    fun consumptionProfile_rejectsInvalidPeriods() {
        assertTrue(ConsumptionProfile.validatePeriods(listOf(ConsumptionPeriod(0, 10, 1.0), ConsumptionPeriod(8, 12, 1.0))).isNotEmpty())
        assertTrue(ConsumptionProfile.validatePeriods(listOf(ConsumptionPeriod(10, 8, 1.0))).isNotEmpty())
        assertTrue(ConsumptionProfile.validatePeriods(listOf(ConsumptionPeriod(0, 25, 1.0))).isNotEmpty())
        assertTrue(ConsumptionProfile.validatePeriods(listOf(ConsumptionPeriod(0, 5, -1.0))).isNotEmpty())
        assertTrue(ConsumptionProfile.validatePeriods(emptyList()).isNotEmpty())
        assertTrue(ConsumptionProfile.validatePeriods(listOf(ConsumptionPeriod(0, 24, 0.3))).isEmpty())
    }

    // --- Autonomy ---

    @Test
    fun autonomy_usesWindowUsableCapacityAndEfficiency() {
        val b = BatteryStorage() // 10 kWh × 90% × (100−10)% × 95%
        assertEquals(7.695, AutonomyCalculator.deliverableKwh(b), 1e-9)
        val r = AutonomyCalculator.forDailyConsumption(b, 5.0)
        assertEquals(7.695 / 5.0, r.days!!, 1e-9)
        val constant = AutonomyCalculator.forConstantLoad(b, 0.5)
        assertEquals(7.695 / 0.5, constant.hours!!, 1e-9)
        assertFalse(constant.limitedByDischargePower)
    }

    @Test
    fun autonomy_flagsLoadAboveDischargePower() {
        val r = AutonomyCalculator.forConstantLoad(BatteryStorage(maxDischargePowerKw = 1.0), 2.0)
        assertTrue(r.limitedByDischargePower)
        assertNull(AutonomyCalculator.forConstantLoad(BatteryStorage(), 0.0).hours)
    }

    // --- Costs ---

    @Test
    fun costs_batterySavesMoneyAndHasPayback() {
        val pv = DoubleArray(24) { h -> if (h in 9..15) 2.0 else 0.0 }
        val pvSeries = series(*pv)
        val without = simulator.run(pvSeries, load(0.5), null)
        val with = simulator.run(pvSeries, load(0.5), battery(initialSoc = 0.0, chargeEff = 95.0, dischargeEff = 95.0))
        val prices = EnergyPrices(gridPricePerKwh = 1.0, feedInPricePerKwh = 0.2, batteryCost = 10_000.0)
        val c = CostComparison.of(without, with, prices)
        assertNotNull(c)
        assertEquals(without.balance.gridKwh * 1.0 - without.balance.surplusKwh * 0.2, c!!.costWithoutBattery, 1e-9)
        assertTrue(c.savings > 0.0)
        assertEquals(c.savings * 365.0, c.yearlySavings, 1e-9)
        assertEquals(10_000.0 / c.yearlySavings, c.paybackYears!!, 1e-9)
    }

    @Test
    fun costs_generatorPriceAndMissingPrice() {
        val r = simulator.run(series(0.0), load(2.0), null)
        assertEquals(6.0, r.balance.netCost(EnergyPrices(generatorPricePerKwh = 3.0, backupSource = BackupSource.GENERATOR))!!, 1e-9)
        assertNull(r.balance.netCost(EnergyPrices(gridPricePerKwh = 1.0, backupSource = BackupSource.GENERATOR)))
        assertNull(CostComparison.of(r, r, EnergyPrices()))
    }
}

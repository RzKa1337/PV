package com.solartracker.pro.core.design

import com.solartracker.pro.core.economics.BackupKind
import com.solartracker.pro.core.economics.TariffAssumptions
import com.solartracker.pro.core.economics.WhatIfBase
import com.solartracker.pro.core.economics.WhatIfChange
import com.solartracker.pro.core.economics.WhatIfSimulator
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.energy.CoolingMode
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.vehicle.PanelLayoutOptimizer
import com.solartracker.pro.core.vehicle.PanelType
import com.solartracker.pro.core.vehicle.RoofArea
import com.solartracker.pro.core.vehicle.VehicleBody
import com.solartracker.pro.core.vehicle.VehicleSolarConfig
import com.solartracker.pro.core.weather.HourlyWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MobileAndTiltTest {
    private val site = GeoLocation(37.3453, 27.2646, 50.0)
    private val zone = ZoneId.of("Europe/Istanbul")

    // Parametric panels for the specification's case (dimensions are test inputs, not app defaults).
    private val p590 = PanelType("590 W", 590.0, 2.278, 1.134, weightKg = 27.5, available = 2)
    private val p455 = PanelType("455 W", 455.0, 1.903, 1.134, weightKg = 23.0, available = 2)

    @Test
    fun specificationRoofFitsAllFourPanels() {
        val roof = RoofArea(4.290, 2.290, edgeMarginM = 0.0, gapM = 0.002)
        val r = PanelLayoutOptimizer.optimize(roof, listOf(p590, p455))
        assertEquals(2 * 590.0 + 2 * 455.0, r.totalPowerW, 1e-9)
        assertTrue(r.leftOver.isEmpty())
        assertEquals(101.0, r.massKg!!, 1e-9)
        assertTrue(r.utilizationPercent in 80.0..100.0)
        // The result must pass the vehicle model's own fit/overlap validation.
        val cfg = VehicleSolarConfig(0.0, body = VehicleBody(4.290, 2.290), panels = r.toVehiclePanels())
        assertTrue(cfg.validate().toString(), cfg.validate().isEmpty())
        assertNotNull(r.cogOffset)
    }

    @Test
    fun marginsGapsAndLoadLimitReduceTheSet() {
        val tight = PanelLayoutOptimizer.optimize(RoofArea(4.290, 2.290, edgeMarginM = 0.05, gapM = 0.02), listOf(p590, p455))
        assertTrue(tight.totalPowerW < 2090.0)
        assertTrue(tight.leftOver.isNotEmpty())
        val cfg = VehicleSolarConfig(0.0, body = VehicleBody(4.290, 2.290), panels = tight.toVehiclePanels())
        assertTrue(cfg.validate().isEmpty())
        val light = PanelLayoutOptimizer.optimize(RoofArea(4.290, 2.290, gapM = 0.002, maxLoadKg = 60.0), listOf(p590, p455))
        assertTrue(light.massKg!! <= 60.0)
        val none = PanelLayoutOptimizer.optimize(RoofArea(1.0, 1.0), listOf(p590))
        assertEquals(0.0, none.totalPowerW, 0.0)
        assertNull("no panels = no centre of gravity", none.centerOfGravity)
    }

    @Test
    fun rotationIsUsedWhenItHelps() {
        // A short, wide roof: the panel only fits turned across the vehicle.
        val roof = RoofArea(1.50, 2.40, gapM = 0.01)
        val r = PanelLayoutOptimizer.optimize(roof, listOf(p590))
        assertEquals(590.0, r.totalPowerW, 1e-9)
        assertTrue(r.placements.all { it.rotated })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPanelIsRejected() {
        PanelLayoutOptimizer.optimize(RoofArea(4.0, 2.0), listOf(PanelType("x", 400.0, 0.0, 1.0)))
    }

    private val array = PvArrayConfig(2, 535.0, 0.0, 180.0)
    private val day = LocalDate.of(2026, 3, 20)

    @Test
    fun dynamicScheduleBeatsStaticOnlyWhenWorthIt() {
        val mount = AdjustableMount(0.0, 60.0, 15.0)
        val free = TiltScheduleOptimizer.optimize(array, LossProfile(), site, day, zone, TiltConstraints(mount, maxChanges = 4, minIntervalMinutes = 60))
        assertTrue(free.changes.size <= 4)
        assertTrue(free.valueKwh >= free.staticValueKwh - 1e-9)
        // Minimum interval between moves is respected.
        free.changes.zipWithNext().forEach { (a, b) -> assertTrue(java.time.Duration.between(a.first, b.first).toMinutes() >= 60) }
        // No moves allowed = the best static tilt.
        val fixed = TiltScheduleOptimizer.optimize(array, LossProfile(), site, day, zone, TiltConstraints(mount, maxChanges = 0))
        assertTrue(fixed.changes.isEmpty())
        assertEquals(fixed.staticValueKwh, fixed.valueKwh, 1e-6)
        assertFalse(fixed.worthIt)
        // A very expensive move makes tracking pointless.
        val costly = TiltScheduleOptimizer.optimize(array, LossProfile(), site, day, zone, TiltConstraints(mount, maxChanges = 4, moveEnergyWh = 5000.0))
        assertTrue(costly.changes.isEmpty())
        assertFalse(costly.worthIt)
    }

    @Test
    fun selfConsumptionObjectiveCapsAtTheLoad() {
        val mount = AdjustableMount(0.0, 60.0, 15.0)
        val r = TiltScheduleOptimizer.optimize(array, LossProfile(), site, day, zone, TiltConstraints(mount, 2),
            objective = TiltObjective.SELF_CONSUMPTION, loadKw = { 0.3 })
        assertTrue(r.slots.all { it.valueKwh <= 0.3 * 0.5 + 1e-9 })
        assertTrue(r.energyKwh >= r.valueKwh)
    }

    @Test
    fun windLimitForcesStowEvenWithoutFreeMoves() {
        val mount = AdjustableMount(0.0, 60.0, 15.0)
        val noon = day.atTime(12, 0).atZone(zone).toInstant()
        val stormy = TiltScheduleOptimizer.optimize(array, LossProfile(), site, day, zone,
            TiltConstraints(mount, maxChanges = 0, maxTiltAt = { t -> if (!t.isBefore(noon) && t.isBefore(noon.plusSeconds(7200))) 0.0 else null }))
        val during = stormy.slots.filter { !it.start.isBefore(noon) && it.start.isBefore(noon.plusSeconds(7200)) }
        assertTrue(during.isNotEmpty() && during.all { it.tiltDeg == 0.0 })
    }

    @Test
    fun windSafetyLevelsAndRecommendation() {
        val setup = WindPanelSetup(panelAreaM2 = 5.2, tiltDeg = 45.0, mounting = MountingType.VEHICLE_RACK,
            rating = WindRating(15.0, 45.0, userProvided = true), mount = AdjustableMount(0.0, 60.0, 5.0))
        val calm = PvWindSafetyEngine.assess(setup, gustMs = 5.0, meanWindMs = 3.0)!!
        assertEquals(WindLevel.SAFE, calm.level)
        val gusty = PvWindSafetyEngine.assess(setup, gustMs = 46 / 3.6, meanWindMs = null)!!
        assertTrue(gusty.level != WindLevel.SAFE)
        assertTrue(gusty.recommendedTiltDeg < 45.0)
        assertTrue(gusty.message.contains("46 km/h") && gusty.message.contains("Zalecana pozycja"))
        val storm = PvWindSafetyEngine.assess(setup, gustMs = 25.0, meanWindMs = null)!!
        assertEquals(WindLevel.STOW_IMMEDIATELY, storm.level)
        assertEquals(0.0, storm.recommendedTiltDeg, 0.0)
        // Only mean wind: gust estimated and lower confidence; nothing at all: null.
        val est = PvWindSafetyEngine.assess(setup, null, 6.0)!!
        assertFalse(est.gustForecast)
        assertTrue(est.confidence < calm.confidence)
        assertNull(PvWindSafetyEngine.assess(setup, null, null))
        // Force grows with tilt; flat panels still feel edge uplift.
        assertTrue(PvWindSafetyEngine.forceN(15.0, 5.0, 60.0, MountingType.TILT_FRAME) > PvWindSafetyEngine.forceN(15.0, 5.0, 10.0, MountingType.TILT_FRAME))
        assertTrue(PvWindSafetyEngine.forceN(15.0, 5.0, 0.0, MountingType.ROOF_FLUSH) > 0)
        val hours = listOf(HourlyWeather(Instant.parse("2026-10-07T12:00:00Z"), null, null, null, 15.0, 20.0, windSpeedMs = 8.0, windGustsMs = 18.0),
            HourlyWeather(Instant.parse("2026-10-07T13:00:00Z"), null, null, null, 15.0, 20.0, windSpeedMs = 3.0, windGustsMs = 5.0))
        val worst = PvWindSafetyEngine.worstAhead(setup, hours, Instant.parse("2026-10-07T10:30:00Z"), zone = zone)!!
        assertEquals(18.0, worst.gustMs, 0.0)
        val user = WindPanelSetup(5.2, 30.0, MountingType.TILT_FRAME)
        assertTrue("default rating is labelled low confidence", PvWindSafetyEngine.assess(user, 5.0, null)!!.confidence < 0.6)
    }

    @Test
    fun seasonalTiltsComeFromMonthlyYields() {
        val rec = TiltOptimizer.compare(array, LossProfile(), site, day, tilts = listOf(0.0, 20.0, 40.0, 60.0))
        val s = TiltScheduleOptimizer.seasonal(rec)
        assertTrue(s.getValue(Season.WINTER) > s.getValue(Season.SUMMER))
    }

    @Test
    fun whatIfMorePvAndBatteryRaiseAutarkyAndPayBack() {
        val base = WhatIfBase(PvSystem(2.09, 0.0, 180.0), BatteryStorage(nominalCapacityKwh = 11.5), ConsumptionProfile.constant(0.5))
        val tariff = TariffAssumptions(gridPricePerKwh = 1.0, feedInPricePerKwh = 0.0, generatorPricePerKwh = 3.0)
        val r = WhatIfSimulator.compare(base, WhatIfChange(peakKw = 3.0, batteryKwh = 16.0, tiltDeg = 30.0, extraInvestment = 6000.0),
            site, zone, 2026, tariff, BackupKind.GENERATOR)
        assertTrue(r.pvChangePercent!! > 30)
        assertTrue(r.variant.autarkyPercent > r.base.autarkyPercent)
        assertTrue(r.annualCostDelta < 0)
        assertNotNull(r.paybackYears)
        assertTrue(r.basis.contains("górna granica"))
        // More consumption and a cold room cost more; no investment → no payback.
        val cold = CoolingLoadProfile(nominalPowerW = 400.0, minimumPowerW = 100.0, mode = CoolingMode.SCHEDULE, targetTemperatureC = 4.0)
        val more = WhatIfSimulator.compare(base.copy(coldRoom = cold, coldRoomOn = false), WhatIfChange(consumptionFactor = 1.2, coldRoomOn = true),
            site, zone, 2026, tariff)
        assertTrue(more.variant.loadKwh > more.base.loadKwh * 1.2)
        assertTrue(more.annualCostDelta > 0)
        assertNull(more.paybackYears)
    }
}

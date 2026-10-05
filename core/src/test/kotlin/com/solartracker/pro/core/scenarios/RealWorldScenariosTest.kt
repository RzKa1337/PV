package com.solartracker.pro.core.scenarios

import com.solartracker.pro.core.energy.BatteryChemistry
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.BatteryType
import com.solartracker.pro.core.ems.EmsInput
import com.solartracker.pro.core.ems.EnergyOptimizationEngine
import com.solartracker.pro.core.ems.EnergySlot
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.pv.TrackerType
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Plausibility checks across typical installations and conditions. These verify physics and model
 * consistency (ratios, ranges, monotonicity) — they are not measured reference data.
 */
class RealWorldScenariosTest {
    private val engine = PvSimulationEngine()
    private val sky = ClearSkyModel()
    private val site = GeoLocation(52.23, 21.01, 100.0)
    private val losses = LossProfile()

    private fun dayKwh(array: PvArrayConfig, date: LocalDate, scale: Double = 1.0, ambient: Double? = 20.0, snow: Double? = null): Double {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds((site.longitude / 15 * 3600).toLong())
        return engine.daily(array, losses, site, start, { t, sun ->
            val i = sky.irradiance(sun, t)
            PvConditions(i.copy(dni = i.dni * scale, dhi = i.dhi * scale), ambient, 2.0, snow)
        }, stepMinutes = 15).acOutputW / 1000.0
    }

    private val june = LocalDate.of(2026, 6, 21)
    private val december = LocalDate.of(2026, 12, 21)

    @Test
    fun sizesScaleLinearly() {
        val perKwp = listOf(1.0, 2.09, 5.0, 10.0).map { kwp -> dayKwh(PvArrayConfig(1, kwp * 1000, 30.0, 180.0), june) / kwp }
        perKwp.forEach { assertEquals(perKwp[0], it, perKwp[0] * 0.01) }
        // Clear June day at 52° N, 30° south: a few kWh per kWp.
        assertTrue("June clear-sky ${perKwp[0]} kWh/kWp", perKwp[0] in 4.0..9.0)
    }

    @Test
    fun tiltsAndTrackers() {
        fun a(tilt: Double, tracker: TrackerType = TrackerType.FIXED) = PvArrayConfig(1, 5000.0, tilt, 180.0, tracker = tracker)
        val flatW = dayKwh(a(0.0), december)
        val steepW = dayKwh(a(60.0), december)
        assertTrue("60° beats flat in winter", steepW > flatW * 1.5)
        val fixedS = dayKwh(a(30.0), june)
        assertTrue("1-axis > fixed in summer", dayKwh(a(0.0, TrackerType.SINGLE_AXIS), june) > fixedS * 1.1)
        assertTrue("2-axis > fixed by ≥ 15%", dayKwh(a(0.0, TrackerType.DUAL_AXIS), june) > fixedS * 1.15)
        assertTrue("60° loses in summer vs 30°", dayKwh(a(60.0), june) < fixedS)
    }

    @Test
    fun weatherConditions() {
        val a = PvArrayConfig(1, 5000.0, 30.0, 180.0)
        val clear = dayKwh(a, june)
        val cloudy = dayKwh(a, june, scale = 0.3)
        assertTrue(cloudy < clear * 0.35)
        assertTrue("cold sunny day yields more per irradiance", dayKwh(a, june, ambient = 0.0) > dayKwh(a, june, ambient = 35.0))
        assertEquals(0.0, dayKwh(a, december, ambient = -3.0, snow = 0.15), 1e-9)
        assertTrue(dayKwh(a, december, ambient = 5.0, snow = 0.15) > 0) // thawing: not treated as covered
    }

    @Test
    fun chemistriesAndGridModes() {
        val agm = BatteryChemistry.effectiveCapacityKwh(BatteryType.AGM, 4.8, 2.4)
        val lfp = BatteryChemistry.effectiveCapacityKwh(BatteryType.LIFEPO4, 4.8, 2.4)
        assertTrue("AGM loses more at 0.5C", agm < lfp)
        val battery = BatteryStorage(nominalCapacityKwh = 5.0, minSocPercent = 20.0)
        val night = (0 until 12).map { EnergySlot(java.time.Instant.parse("2026-12-21T16:00:00Z").plusSeconds(it * 3600L), 1.0, 0.0, 0.8) }
        val grid = EnergyOptimizationEngine.plan(EmsInput(night, battery, 60.0, gridAvailable = true, zone = ZoneOffset.UTC))
        val offGrid = EnergyOptimizationEngine.plan(EmsInput(night, battery, 60.0, gridAvailable = false, zone = ZoneOffset.UTC))
        assertTrue(grid.gridImportKwh > 0 && grid.unservedKwh == 0.0)
        assertEquals(grid.gridImportKwh, offGrid.unservedKwh, 1e-9)
    }
}

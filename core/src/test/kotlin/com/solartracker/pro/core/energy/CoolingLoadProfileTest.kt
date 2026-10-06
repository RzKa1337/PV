package com.solartracker.pro.core.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class CoolingLoadProfileTest {
    private val zone = ZoneOffset.UTC
    private val noon = Instant.parse("2026-07-01T12:00:00Z")
    private val night = Instant.parse("2026-07-01T02:00:00Z")
    private val room = CoolingLoadProfile(nominalPowerW = 3000.0, minimumPowerW = 150.0, mode = CoolingMode.AVERAGE, averagePowerW = 1290.0,
        targetTemperatureC = 2.0, referenceAmbientC = 25.0)

    @Test
    fun averageIsNotNominalAndFollowsAmbient() {
        assertTrue(room.validate().isEmpty())
        assertEquals(0.4, room.duty(noon, zone, 25.0), 1e-9) // (1290-150)/(3000-150)
        assertEquals(1.29, room.averagePowerKw(noon, zone, null), 1e-9)
        assertTrue(room.averagePowerKw(noon, zone, 35.0) > room.averagePowerKw(noon, zone, 25.0))
        assertEquals(0.15, room.averagePowerKw(noon, zone, 2.0), 1e-9) // no heat gain → standby only
        assertTrue(room.averagePowerKw(noon, zone, 50.0) <= 3.0)
        assertEquals(1.29 * 24, room.dailyEnergyKwh(LocalDate.of(2026, 7, 1), zone, 25.0), 1e-6)
    }

    @Test
    fun dutyCycleTraceAveragesToDuty() {
        val dc = room.copy(mode = CoolingMode.DUTY_CYCLE, averagePowerW = null, dutyAtReference = 0.5)
        val samples = (0 until 1200).map { dc.instantPowerKw(noon.plusSeconds(it.toLong()), zone, 25.0) }
        assertTrue(samples.all { it == 3.0 || it == 0.15 })
        assertEquals(dc.averagePowerKw(noon, zone, 25.0), samples.average(), 0.01)
    }

    @Test
    fun operatingHoursPreCoolingAndSchedule() {
        val shop = room.copy(operatingStart = LocalTime.of(8, 0), operatingEnd = LocalTime.of(20, 0), idleDutyFactor = 0.6,
            preCoolingEnabled = true, preCoolingTargetC = -2.0, preCoolingHours = 2.0)
        assertTrue(shop.validate().isEmpty())
        assertEquals(0.4 * 0.6, shop.duty(night, zone, 25.0), 1e-9)
        val pre = shop.duty(Instant.parse("2026-07-01T07:00:00Z"), zone, 25.0)
        assertTrue("pre-cooling raises duty: $pre", pre > shop.duty(noon, zone, 25.0))
        val sched = room.copy(mode = CoolingMode.SCHEDULE, averagePowerW = null,
            schedule = listOf(CoolingScheduleEntry(LocalTime.of(22, 0), LocalTime.of(6, 0), 800.0), CoolingScheduleEntry(LocalTime.of(6, 0), LocalTime.of(22, 0), 1600.0)))
        assertTrue(sched.validate().isEmpty())
        assertEquals(0.8, sched.averagePowerKw(night, zone, null), 1e-9)
        assertEquals(1.6, sched.averagePowerKw(noon, zone, null), 1e-9)
        assertTrue(room.copy(averagePowerW = 5000.0).validate().isNotEmpty())
        assertTrue(room.copy(preCoolingEnabled = true, preCoolingTargetC = 5.0).validate().isNotEmpty())
    }
}

class CoolingAwareLoadTest {
    @Test
    fun addsCoolingOnTopOfBaseLoad() {
        val zone = ZoneOffset.UTC
        val room = CoolingLoadProfile(nominalPowerW = 3000.0, minimumPowerW = 150.0, mode = CoolingMode.AVERAGE, averagePowerW = 1290.0, targetTemperatureC = 2.0)
        val base = com.solartracker.pro.core.forecast.LoadModel { t ->
            com.solartracker.pro.core.forecast.LoadForecastPoint(t, 0.5, com.solartracker.pro.core.quality.DataKind.FORECAST, 0.8, "test")
        }
        val load = com.solartracker.pro.core.forecast.CoolingAwareLoad(base, room, zone) { 25.0 }
        val p = load.at(Instant.parse("2026-07-01T12:00:00Z"))
        assertEquals(1.79, p.kw, 1e-9)
        assertEquals(com.solartracker.pro.core.quality.DataKind.ESTIMATED, p.kind)
        assertTrue(p.basis.contains("Chłodnia"))
    }
}

package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.fixtures.EnergyFixtures
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RadarAndMissionTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 3.0, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val now = LocalDate.of(2026, 6, 21).atTime(11, 0).atZone(zone).toInstant()

    /** Forecast hours: clear sky, except [cloudyHoursAfter] hours from now which pass only [cloudyFraction] of it. */
    private fun forecast(cloudyHoursAfter: Set<Int>, cloudyFraction: Double = 0.3): WeatherForecast {
        val cs = ClearSkyModel()
        val hours = (-2..10).map { i ->
            val end = now.plus(Duration.ofHours(i.toLong() + 1))
            val mid = end.minusSeconds(1800)
            val pos = SolarCalculator.position(loc, mid)
            val irr = cs.irradiance(pos, mid)
            val f = if (i in cloudyHoursAfter) cloudyFraction else 1.0
            HourlyWeather(end, irr.ghi(pos) * f, irr.dni * f * f, irr.dhi * (2 - f), 22.0, if (f < 1) 90.0 else 5.0)
        }
        return WeatherForecast(hours, now)
    }

    private fun radar(cloudy: Set<Int>, current: Double? = null): PvRadar {
        val pv = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, forecast(cloudy)), null)
        val clear = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc), null)
        return PvRadarBuilder(pv, clear, system.peakPowerKw).build(now, nowcastRatio = null, currentKw = current)
    }

    @Test
    fun radarCoversAllHorizonsWithEnergyAndCloudImpact() {
        val r = radar(emptySet())
        assertEquals(listOf(0L, 5L, 15L, 30L, 60L, 120L, 180L, 360L), r.points.map { it.horizon.minutes })
        val e = r.points.map { it.energyKwh }
        assertEquals(0.0, e.first(), 1e-9)
        assertTrue("energy is cumulative", e.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(r.points.first().cloudImpactPercent!! < 10)
        assertNull("clear sky: no cloud event", r.cloudEvent)
        assertEquals(60, r.resolutionMinutes)
        r.points.forEach { assertTrue(it.minKw <= it.expectedKw && it.expectedKw <= it.maxKw + 1e-9) }
    }

    @Test
    fun cloudEventIsDetectedWithDropAndHonestResolution() {
        val r = radar(setOf(1, 2), current = 2.0)
        val ev = r.cloudEvent!!
        assertTrue(ev.minutesAhead in 55..125)
        assertTrue("drop ${ev.dropPercent}", ev.dropPercent >= 30)
        assertTrue(ev.expectedMinKw < ev.currentKw)
        assertEquals(60, ev.resolutionMinutes)
        assertTrue(r.points.single { it.horizon == ShortHorizon.H2 }.cloudImpactPercent!! > 40)
    }

    @Test
    fun sunsetIsNotACloudEvent() {
        val evening = LocalDate.of(2026, 6, 21).atTime(18, 30).atZone(zone).toInstant()
        val pv = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc), null)
        val r = PvRadarBuilder(pv, pv, system.peakPowerKw).build(evening, null, null)
        assertNull(r.cloudEvent)
        val night = PvRadarBuilder(pv, pv, system.peakPowerKw).build(LocalDate.of(2026, 12, 21).atTime(18, 0).atZone(zone).toInstant(), null, null)
        assertTrue(night.points.all { it.expectedKw == 0.0 && it.cloudImpactPercent == null })
    }

    private val battery = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0, maxSocPercent = 100.0,
        maxChargePowerKw = 3.0, maxDischargePowerKw = 3.0, chargeEfficiencyPercent = 95.0, dischargeEfficiencyPercent = 95.0)
    private val evening = Instant.parse("2026-06-21T16:00:00Z") // 18:00 local
    private val planner = EnergyMissionPlanner(EnergySecurityAnalyzer(battery, zone), zone)

    private fun mission(goal: MissionGoal, soc: Double?, p: EnergyFixtures.Profile = EnergyFixtures.CLEAR_SUMMER, req: MissionRequest = MissionRequest(goal)) =
        planner.evaluate(req, evening, soc, DataKind.MEASURED, EnergyFixtures.pv(p, zone), EnergyFixtures.load(p, zone))

    @Test
    fun surviveNightWithGoodSoc() {
        val m = mission(MissionGoal.SURVIVE_NIGHT, 72.0)
        assertEquals(true, m.achievable)
        assertTrue("scenario estimate shrinks toward 50% with forecast confidence", m.probabilityPercent!! >= 70)
        assertTrue(m.safetyMarginKwh!! > 0)
        assertEquals(EnergyRisk.LOW, m.risk)
        assertEquals(listOf("00:00", "06:00", "08:00"), m.milestones.map { it.label })
        assertEquals(LocalTime.of(8, 0), m.targetTime.atZone(zone).toLocalTime())
        m.milestones.forEach { assertTrue(it.lowPercent <= it.socPercent && it.socPercent <= it.highPercent) }
    }

    @Test
    fun emptyBatteryFailsAndNamesTheTime() {
        val m = mission(MissionGoal.SURVIVE_NIGHT, 25.0, EnergyFixtures.HIGH_LOAD)
        assertEquals(false, m.achievable)
        assertTrue(m.probabilityPercent!! < 40)
        assertTrue(m.safetyMarginKwh!! <= 0.01)
        assertTrue(m.deficitKwh!! > 0)
        assertEquals(EnergyRisk.HIGH, m.risk)
        assertTrue(m.recommendation.contains("zabraknie"))
    }

    @Test
    fun protectBatteryUsesTheHigherFloor() {
        val relaxed = mission(MissionGoal.SURVIVE_NIGHT, 45.0)
        val strict = mission(MissionGoal.PROTECT_BATTERY, 45.0, req = MissionRequest(MissionGoal.PROTECT_BATTERY, protectSocPercent = 40.0))
        assertTrue(strict.safetyMarginKwh!! < relaxed.safetyMarginKwh!!)
        assertTrue(strict.metrics.any { it.label.contains("Próg") && it.value == "40%" })
    }

    @Test
    fun selfConsumptionAndCostGoals() {
        val morning = LocalDate.of(2026, 6, 22).atTime(6, 0).atZone(zone).toInstant()
        val sc = planner.evaluate(MissionRequest(MissionGoal.MAXIMIZE_SELF_CONSUMPTION, LocalTime.of(20, 0)), morning, 95.0, DataKind.MEASURED,
            EnergyFixtures.pv(EnergyFixtures.CLEAR_SUMMER, zone), EnergyFixtures.load(EnergyFixtures.CLEAR_SUMMER, zone))
        assertTrue(sc.recommendation.contains(":00–"))
        assertTrue(sc.metrics.any { it.label == "Autokonsumpcja PV" })
        val cost = mission(MissionGoal.MINIMIZE_GRID_COST, 25.0, EnergyFixtures.HIGH_LOAD, MissionRequest(MissionGoal.MINIMIZE_GRID_COST, gridPricePerKwh = 1.2))
        assertTrue(cost.metrics.any { it.label == "Koszt" && it.value != "N/A (brak ceny)" })
        val noPrice = mission(MissionGoal.MINIMIZE_GRID_COST, 25.0, EnergyFixtures.HIGH_LOAD)
        assertTrue(noPrice.metrics.any { it.value.startsWith("N/A") })
        val gen = mission(MissionGoal.MINIMIZE_GENERATOR, 25.0, EnergyFixtures.HIGH_LOAD, MissionRequest(MissionGoal.MINIMIZE_GENERATOR, generatorPricePerKwh = 3.0))
        assertTrue(gen.recommendation.contains("Agregat potrzebny"))
        val cold = mission(MissionGoal.RUN_COLD_ROOM, 80.0, EnergyFixtures.COOLING_LOAD, MissionRequest(MissionGoal.RUN_COLD_ROOM, coldRoomKw = 0.7))
        assertNotNull(cold.metrics.firstOrNull { it.label.startsWith("Udział chłodni") })
    }

    @Test
    fun noBatteryOrNoSocCannotBeJudged() {
        assertNull(mission(MissionGoal.SURVIVE_NIGHT, null).achievable)
        val none = EnergyMissionPlanner(EnergySecurityAnalyzer(null, zone), zone).evaluate(MissionRequest(MissionGoal.SURVIVE_NIGHT), evening, 50.0,
            DataKind.MEASURED, EnergyFixtures.pv(EnergyFixtures.CLEAR_SUMMER, zone), EnergyFixtures.load(EnergyFixtures.CLEAR_SUMMER, zone))
        assertNull(none.probabilityPercent)
        assertEquals(EnergyRisk.UNKNOWN, none.risk)
        assertFalse(none.recommendation.isBlank())
    }
}

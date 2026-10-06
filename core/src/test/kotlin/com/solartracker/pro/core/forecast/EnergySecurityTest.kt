package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.AccuracyPeriod
import com.solartracker.pro.core.analytics.ForecastAccuracy
import com.solartracker.pro.core.analytics.ForecastComparison
import com.solartracker.pro.core.analytics.ForecastPair
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.fixtures.EnergyFixtures
import com.solartracker.pro.core.quality.DataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class EnergySecurityTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val evening = Instant.parse("2026-06-21T16:00:00Z") // 18:00 local
    private val battery = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0, maxSocPercent = 100.0,
        maxChargePowerKw = 3.0, maxDischargePowerKw = 3.0, chargeEfficiencyPercent = 95.0, dischargeEfficiencyPercent = 95.0)
    private val analyzer = EnergySecurityAnalyzer(battery, zone)

    private fun run(p: EnergyFixtures.Profile, soc: Double, grid: Boolean = false, now: Instant = evening) =
        analyzer.analyze(now, soc, DataKind.MEASURED, EnergyFixtures.pv(p, zone), EnergyFixtures.load(p, zone), grid)

    @Test
    fun summerEveningWithGoodSocIsSecure() {
        val s = run(EnergyFixtures.CLEAR_SUMMER, 72.0)
        assertEquals(listOf("21:00", "00:00", "03:00", "06:00", "08:00"), s.milestones.map { it.label })
        val socs = s.milestones.map { it.socPercent }
        assertTrue("SOC falls overnight: $socs", socs[0] > socs[1] && socs[1] > socs[2] && socs[2] > socs[3])
        s.milestones.forEach { assertTrue(it.lowPercent <= it.socPercent && it.socPercent <= it.highPercent) }
        assertFalse(s.shortageExpected)
        assertEquals(EnergyRisk.LOW, s.risk)
        assertEquals(100, s.securityPercent)
        assertNull(s.timeToMinSoc)
    }

    @Test
    fun lowSocAndHighLoadPredictShortage() {
        val s = run(EnergyFixtures.HIGH_LOAD, 35.0)
        assertTrue(s.shortageExpected)
        assertEquals(EnergyRisk.HIGH, s.risk)
        assertNotNull(s.timeToMinSoc)
        assertTrue(s.securityPercent!! < 60)
        assertTrue(s.explanation.contains("BRAK ENERGII"))
        assertEquals(20, s.predictedMinSoc)
        // With the grid as backup it is an import, not an outage.
        val g = run(EnergyFixtures.HIGH_LOAD, 35.0, grid = true)
        assertTrue(g.explanation.contains("z sieci"))
    }

    @Test
    fun marginalCaseIsMediumRisk() {
        // Find a SOC where only the pessimistic scenario reaches the minimum.
        val medium = (30..90 step 2).map { run(EnergyFixtures.CLEAR_SUMMER, it.toDouble()) }.firstOrNull { it.risk == EnergyRisk.MEDIUM }
        assertNotNull("some SOC level should give MEDIUM risk", medium)
        assertFalse(medium!!.shortageExpected)
        assertNotNull(medium.minSocAtPessimistic)
    }

    @Test
    fun noBatteryOrNoSoc() {
        val none = EnergySecurityAnalyzer(null, zone).analyze(evening, null, DataKind.UNKNOWN, EnergyFixtures.pv(EnergyFixtures.WINTER, zone), EnergyFixtures.load(EnergyFixtures.WINTER, zone), true)
        assertEquals(EnergyRisk.LOW, none.risk)
        assertNull(none.securityPercent)
        val noSoc = analyzer.analyze(evening, null, DataKind.UNKNOWN, EnergyFixtures.pv(EnergyFixtures.WINTER, zone), EnergyFixtures.load(EnergyFixtures.WINTER, zone), false)
        assertEquals(EnergyRisk.UNKNOWN, noSoc.risk)
        assertTrue(noSoc.explanation.contains("SOC"))
    }

    @Test
    fun inverterEfficiencyDrainsFaster() {
        val ideal = EnergySecurityAnalyzer(battery, zone, inverterEfficiency = 1.0)
            .analyze(evening, 70.0, DataKind.MEASURED, EnergyFixtures.pv(EnergyFixtures.WINTER, zone), EnergyFixtures.load(EnergyFixtures.WINTER, zone), false)
        val real = EnergySecurityAnalyzer(battery, zone, inverterEfficiency = 0.9)
            .analyze(evening, 70.0, DataKind.MEASURED, EnergyFixtures.pv(EnergyFixtures.WINTER, zone), EnergyFixtures.load(EnergyFixtures.WINTER, zone), false)
        assertTrue(real.milestones.first { it.label == "00:00" }.socPercent < ideal.milestones.first { it.label == "00:00" }.socPercent)
        assertTrue(real.timeToMinSoc!! < ideal.timeToMinSoc!!)
    }

    @Test
    fun fourDayOutlook() {
        val morning = Instant.parse("2026-06-21T06:00:00Z")
        val clear = analyzer.outlook(morning, 60.0, DataKind.MEASURED, EnergyFixtures.pv(EnergyFixtures.CLEAR_SUMMER, zone), EnergyFixtures.load(EnergyFixtures.CLEAR_SUMMER, zone))
        assertEquals(listOf("DZIŚ", "JUTRO", "POJUTRZE", "ZA 3 DNI"), clear.map { it.label })
        assertTrue(clear.first().partial)
        val tomorrow = clear[1]
        assertEquals(24 * 0.6 - 8 * 0.25 , tomorrow.loadKwh, 0.6) // 16 h × 0.6 + 8 h × 0.35
        assertTrue(tomorrow.pvKwh > 25)
        assertTrue(tomorrow.balanceKwh > 0)
        assertEquals(EnergyRisk.LOW, tomorrow.risk)
        assertTrue(clear[3].confidence < clear[1].confidence)
        val winter = analyzer.outlook(morning, 40.0, DataKind.MEASURED, EnergyFixtures.pv(EnergyFixtures.ZERO_PV, zone), EnergyFixtures.load(EnergyFixtures.ZERO_PV, zone))
        assertTrue(winter.drop(1).all { it.balanceKwh < 0 })
        assertEquals(EnergyRisk.HIGH, winter[1].risk)
        assertEquals(20, winter[1].socMin)
        assertTrue(winter[1].securityPercent!! < 50)
        // Without a battery the outlook still gives PV and load energy.
        val noBattery = EnergySecurityAnalyzer(null, zone).outlook(morning, null, DataKind.UNKNOWN, EnergyFixtures.pv(EnergyFixtures.CLEAR_SUMMER, zone), EnergyFixtures.load(EnergyFixtures.CLEAR_SUMMER, zone))
        assertEquals(4, noBattery.size)
        assertNull(noBattery[1].socMin)
    }

    @Test
    fun forecastVsActualMetrics() {
        val t = Instant.parse("2026-06-21T10:00:00Z")
        fun row(start: Instant, pvW: Double) = HistorySample(start, start.plusSeconds(30), 6, pvW, pvW, null, null, null, null, null, null, null, null,
            pvW / 1000 * 30 / 3600, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptySet(), emptySet())
        val samples = (-10 until 10).map { row(t.plusSeconds(it * 30L), 1640.0) }
        val pair = ForecastComparison.pairPower(mapOf(t to 1.82, t.plusSeconds(3600) to 1.0), samples).single()
        assertEquals(1.64, pair.actual, 1e-9)
        assertEquals(-0.0989, ForecastComparison.relativeError(1.82, 1.64)!!, 1e-3)
        assertNull(ForecastComparison.relativeError(0.01, 0.2))

        val hourly = (0 until 48).map { ForecastPair(t.plusSeconds(it * 3600L), 1.0 + (it % 5) * 0.2, 0.9 + (it % 5) * 0.2) }
        val r = ForecastAccuracy.evaluate(hourly)
        assertTrue(r.r2!! > 0.5 && r.r2!! < 1.0)
        val daily = ForecastComparison.aggregate(hourly, AccuracyPeriod.DAY, zone)
        assertTrue(daily.size in 2..3)
        assertEquals(hourly.sumOf { it.forecast }, daily.sumOf { it.forecast }, 1e-9)
        assertNull(ForecastAccuracy.evaluate(listOf(ForecastPair(t, 1.0, 1.0), ForecastPair(t, 1.0, 1.0), ForecastPair(t, 1.0, 1.0))).r2)
        // MAPE ignores near-zero actual values.
        assertNull(ForecastAccuracy.evaluate(listOf(ForecastPair(t, 0.3, 0.001))).mapePercent)
    }
}

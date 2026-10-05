package com.solartracker.pro.core.health

import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.pv.PvLossBreakdown
import com.solartracker.pro.core.quality.DataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HealthTest {
    private val now = Instant.parse("2026-06-20T18:00:00Z")
    private fun day(i: Int, real: Double, exp: Double, cov: Double = 10.0, v: Double? = 320.0, t: Double? = 45.0, night: Double? = 0.3, faults: Map<Int, Int> = emptyMap()) =
        DayStats(LocalDate.of(2026, 6, 1).plusDays(i.toLong()), real, exp, cov, v, t, night, faults, emptyMap())
    private fun losses(ideal: Double, temp: Double = 0.0, shade: Double = 0.0, clip: Double = 0.0) =
        PvLossBreakdown(0.0, ideal, 0.0, temp, 0.0, 0.0, shade, 0.0, 0.0, 0.0, 0.0, 0.0, clip, 0.0, ideal - temp - shade - clip)

    @Test
    fun healthyInstallationScoresHighWithEvidence() {
        val r = PvHealthEngine.assess(HealthInput((0 until 7).map { day(it, 20.0, 20.0) }, losses(100.0, temp = 2.0, shade = 4.0), 0.8, 0.965, 0.99, 0, 0, null))
        assertEquals(94, r.score)
        assertEquals(listOf("shading", "temperature"), r.deductions.map { it.category })
        assertEquals(DataKind.CALCULATED, r.kind)
        assertTrue(r.explanation().contains("−4 shading"))
    }

    @Test
    fun unexplainedUnderproductionAndInverterEfficiency() {
        val r = PvHealthEngine.assess(HealthInput((0 until 7).map { day(it, 17.0, 20.0) }, losses(100.0), 0.8, 0.93, 0.99, 0, 1, null))
        val cats = r.deductions.associate { it.category to it.points }
        assertEquals(10, cats["unexplained underproduction"]) // 15% gap − 5% tolerance
        assertEquals(3, cats["inverter efficiency"])
        assertEquals(3, cats["warnings"])
        assertEquals(84, r.score)
        assertTrue(r.deductions.first { it.category == "unexplained underproduction" }.evidence.contains("7 dni"))
    }

    @Test
    fun notEnoughDataGivesNoScoreAndLowConfidenceShadingIsUnknown() {
        val none = PvHealthEngine.assess(HealthInput(listOf(day(0, 20.0, 20.0, cov = 3.0)), null, null, null, null, 0, 0, null))
        assertNull(none.score)
        assertTrue(none.explanation().startsWith("Za mało danych"))
        val lowShade = PvHealthEngine.assess(HealthInput((0 until 3).map { day(it, 20.0, 20.0) }, losses(100.0, shade = 10.0), 0.2, null, null, 0, 0, null))
        assertTrue(lowShade.deductions.none { it.category == "shading" })
        assertTrue(lowShade.unknowns.any { it.contains("zacienienie") })
        assertTrue(lowShade.unknowns.any { it.contains("sprawność falownika") })
    }

    @Test
    fun predictiveFaultsFromTrends() {
        val base = (0 until 14).map { day(it, 20.0, 20.0, v = 320.0, t = 45.0 + it * 0.0, night = 0.3) }
        val today = day(14, 14.0, 20.0, v = 260.0, t = 46.0, night = 0.8)
        val w = PredictiveFaultEngine.analyze(base + today, now, linkQuality = 0.7, stringPowersW = listOf(1200.0, 800.0))
        val ids = w.map { it.id }.toSet()
        assertTrue(ids.toString(), ids.containsAll(listOf("efficiency-drop", "pv-voltage", "night-load", "link", "string-imbalance")))
        assertTrue(w.all { it.level == FaultLevel.ANOMALY })
        assertTrue(w.first { it.id == "efficiency-drop" }.evidence.isNotEmpty())
        assertTrue(w.all { it.recommendation.isNotBlank() && it.confidence in 0.0..1.0 })
    }

    @Test
    fun rising_temperature_and_repeated_faults() {
        val days = (0 until 10).map { day(it, 20.0, 20.0, t = 50.0 + it * 2.0, faults = if (it >= 7) mapOf(7 to 2) else emptyMap()) }
        val w = PredictiveFaultEngine.analyze(days, now)
        assertTrue(w.any { it.id == "inverter-temp" })
        val fault = w.first { it.id == "fault-7" }
        assertEquals(FaultLevel.FAULT, fault.level)
        assertEquals(FaultSeverity.CRITICAL, fault.severity)
        assertTrue(PredictiveFaultEngine.analyze((0 until 14).map { day(it, 20.0, 20.0) }, now).isEmpty())
    }

    @Test
    fun dayStatsFromHistory() {
        val start = Instant.parse("2026-06-01T10:00:00Z")
        val rows = (0 until 4).map { i ->
            val s = start.plusSeconds(i * 1800L)
            HistorySample(s, s.plusSeconds(1800), 60, 2000.0, 2000.0, 3200.0, 0.0, 0.0, 52.0, 0.0, 50.0, 55.0, null, 1.0, 0.5, 0.0, 0.0, 0.0, 0.0, null, if (i == 0) setOf(3) else emptySet(), emptySet())
        }
        val stats = DayStatsBuilder.build(rows, ZoneOffset.UTC, { 2500.0 }, peakPowerW = 4000.0, inverterRatedW = 6200.0) { 310.0 }
        val d = stats.single()
        assertEquals(4.0, d.realKwh, 1e-9)
        assertEquals(5.0, d.expectedKwh, 1e-9)
        assertEquals(0.8, d.ratio!!, 1e-9)
        assertEquals(310.0, d.pvVoltageAtPower!!, 0.0)
        assertEquals(55.0, d.inverterTempAtLoadC!!, 0.0)
        assertEquals(mapOf(3 to 1), d.faultCodes)
        assertEquals(2.0, d.coverageHours, 1e-9)
    }
}

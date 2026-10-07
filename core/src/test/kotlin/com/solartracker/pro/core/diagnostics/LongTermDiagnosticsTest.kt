package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.CalibrationObservation
import com.solartracker.pro.core.analytics.SkyCondition
import com.solartracker.pro.core.inverter.MpptReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LongTermDiagnosticsTest {
    private val start = LocalDate.of(2026, 5, 1)

    /** Clean PI 0.95; dust builds up 0.6 p.p./day of clear weather; rain ≥ 2 mm washes it off. */
    private fun season(rainDay: Int?, days: Int = 30, cloudyEvery: Int = 0): List<DailyPerformance> {
        var deficit = 0.0
        return (0 until days).map { d ->
            val rain = if (d == rainDay) 6.0 else 0.0
            if (rain >= SoilingDetector.CLEANING_RAIN_MM) deficit = 0.0 else deficit += 0.006
            val cloudy = cloudyEvery > 0 && d % cloudyEvery == 0
            DailyPerformance(start.plusDays(d.toLong()), 0.95 * (1 - deficit), if (cloudy) 0.5 else 6.0, rain)
        }
    }

    @Test
    fun rainRecoveryConfirmsSoiling() {
        // 15 days of build-up (≈ −9%), rain, then a few clean days.
        var deficit = 0.0
        val days = (0 until 20).map { d ->
            val rain = if (d == 15) 8.0 else 0.0
            deficit = if (d == 15) 0.0 else if (d < 15) d * 0.0065 else 0.021
            DailyPerformance(start.plusDays(d.toLong()), 0.95 * (1 - deficit), 6.0, rain)
        }
        val a = SoilingDetector.assess(days)
        assertEquals(SoilingState.CONFIRMED, a.state)
        val rec = a.recovery!!
        assertTrue("before ${rec.deficitBeforePercent}", rec.deficitBeforePercent > 6.0)
        assertTrue(rec.recoveryPercentPoints >= SoilingDetector.CONFIRM_RECOVERY_PP)
        assertTrue(a.evidence.any { it.contains("Przed deszczem") })
        assertTrue(a.confidence > 0.5)
    }

    @Test
    fun steadyBuildUpWithoutRainIsSuspected() {
        val a = SoilingDetector.assess(season(rainDay = null, days = 25))
        assertEquals(SoilingState.SUSPECTED, a.state)
        assertTrue(a.lossPercent!! > SoilingDetector.SUSPECT_DEFICIT_PERCENT)
        assertTrue(a.ratePerDay!! > 0)
    }

    @Test
    fun cleanPanelsAndCloudyOrSnowyDaysAreNotReported() {
        val clean = (0 until 20).map { DailyPerformance(start.plusDays(it.toLong()), 0.95 + (it % 3) * 0.002, 6.0, 0.0) }
        assertEquals(SoilingState.NONE, SoilingDetector.assess(clean).state)
        val cloudy = clean.map { it.copy(clearSkyHours = 1.0, performanceIndex = 0.6) }
        assertEquals(SoilingState.INSUFFICIENT_DATA, SoilingDetector.assess(cloudy).state)
        val snowy = clean.map { it.copy(snow = true) }
        assertEquals(SoilingState.INSUFFICIENT_DATA, SoilingDetector.assess(snowy).state)
        assertEquals(SoilingState.INSUFFICIENT_DATA, SoilingDetector.assess(emptyList()).state)
    }

    @Test
    fun shadingChangeResetsTheComparison() {
        val before = season(rainDay = null, days = 20)
        val changed = before.mapIndexed { i, d -> if (i == 17) d.copy(shadingChanged = true) else d }
        assertEquals(SoilingState.INSUFFICIENT_DATA, SoilingDetector.assess(changed).state)
    }

    private fun months(rate: Double, years: Int, noise: (Int) -> Double = { 0.0 }): List<MonthlyPerformance> =
        (0 until years * 12).map { i ->
            val m = YearMonth.of(2024, 1).plusMonths(i.toLong())
            val season = 1.0 + 0.04 * kotlin.math.sin(i * Math.PI / 6) // seasonal model error, cancels year over year
            MonthlyPerformance(m, 0.93 * season * Math.pow(1 - rate, i / 12.0) + noise(i), clearDays = 10)
        }

    @Test
    fun yearOverYearRecoversDegradationAndProjectsCapacity() {
        val a = DegradationAnalyzer.assess(months(0.005, 3), nameplateKwp = 2.14, commissioningYear = 2024, projectYears = listOf(2026, 2030, 2035))
        assertEquals(0.5, a.ratePercentPerYear!!, 0.02)
        assertEquals(DegradationTrend.NORMAL, a.trend)
        assertTrue(a.confidence > 0.8)
        assertEquals(2.14 * Math.pow(0.995, 11.0), a.projection.single { it.year == 2035 }.capacityKwp, 0.01)
    }

    @Test
    fun degradationNeedsTwoYearsAndIgnoresDarkMonths() {
        assertEquals(DegradationTrend.INSUFFICIENT_DATA, DegradationAnalyzer.assess(months(0.005, 1), 2.14, null).trend)
        val sparse = months(0.005, 3).map { it.copy(clearDays = 1) }
        assertNull(DegradationAnalyzer.assess(sparse, 2.14, null).ratePercentPerYear)
        val stable = DegradationAnalyzer.assess(months(0.0, 3), 2.14, null)
        assertEquals(DegradationTrend.STABLE, stable.trend)
        val elevated = DegradationAnalyzer.assess(months(0.015, 3), 2.14, null)
        assertEquals(DegradationTrend.ELEVATED, elevated.trend)
    }

    @Test
    fun mpptExampleFromTheSpecification() {
        val configs = listOf(MpptConfig(1, 1070.0, modules = 2), MpptConfig(2, 1070.0, modules = 2))
        val a = MpptAnalyzer.analyze(listOf(MpptReading(1, 82.0, 11.0, 901.0), MpptReading(2, 80.0, 8.9, 711.0)), configs, mapOf(1 to 920.0, 2 to 910.0))
        assertTrue(a.available)
        val m1 = a.statuses.single { it.index == 1 }
        val m2 = a.statuses.single { it.index == 2 }
        assertEquals(-2.1, m1.deviationPercent!!, 0.05)
        assertEquals(-21.9, m2.deviationPercent!!, 0.05)
        assertEquals(MpptFlag.NORMAL, m1.flag)
        assertEquals(MpptFlag.ABNORMAL, m2.flag)
        assertEquals(listOf(2), a.abnormal)
        assertTrue(a.possibleCauses.isNotEmpty())
        assertFalse("never names a damaged module as fact", a.note.contains("uszkodzony moduł") && !a.note.contains("możliwe"))
    }

    @Test
    fun mpptEdgeCases() {
        val none = MpptAnalyzer.analyze(emptyList(), emptyList())
        assertFalse(none.available)
        val configs = listOf(MpptConfig(1, 1000.0, 2), MpptConfig(2, 1000.0, 2))
        val dark = MpptAnalyzer.analyze(listOf(MpptReading(1, 60.0, 0.5, 30.0), MpptReading(2, 60.0, 0.3, 18.0)), configs, mapOf(1 to 40.0, 2 to 40.0))
        assertTrue(dark.statuses.all { it.flag == MpptFlag.LOW_LIGHT })
        val missing = MpptAnalyzer.analyze(listOf(MpptReading(1, null, null, null), MpptReading(2, 80.0, 9.0, 700.0)), configs, mapOf(1 to 720.0, 2 to 720.0))
        assertEquals(MpptFlag.NO_DATA, missing.statuses.first().flag)
        val mismatch = MpptAnalyzer.analyze(listOf(MpptReading(1, 82.0, 9.0, 740.0), MpptReading(2, 66.0, 11.0, 726.0)), configs, mapOf(1 to 740.0, 2 to 740.0))
        assertTrue(mismatch.stringMismatch)
        assertEquals(mapOf(1 to 600.0, 2 to 400.0), MpptAnalyzer.splitByPeak(1000.0, listOf(MpptConfig(1, 1200.0), MpptConfig(2, 800.0))))
    }

    @Test
    fun performanceHistoryUsesOnlyClearCleanSamples() {
        val zone = java.time.ZoneId.of("UTC")
        fun obs(day: Int, minute: Int, real: Double, csi: Double, link: Boolean = true, nearLimit: Boolean = false) = CalibrationObservation(
            java.time.Instant.parse("2026-05-0${day}T10:00:00Z").plusSeconds(minute * 60L), real, 1.0, 40.0, nearLimit = nearLimit,
            sunElevationDeg = 50.0, clearSkyIndex = csi, condition = if (csi >= 0.85) SkyCondition.CLEAR else SkyCondition.OVERCAST, shadingFactor = 1.0, linkOk = link)
        val o = (0 until 240).map { obs(1, it, 0.95, 0.95) } + (0 until 240).map { obs(2, it, 0.5, 0.4) } +
            (0 until 240).map { obs(3, it, 0.9, 0.95, link = it % 2 == 0) } + listOf(obs(3, 300, 0.1, 0.95, nearLimit = true))
        val d = PerformanceHistory.daily(o, zone, mapOf(LocalDate.of(2026, 5, 2) to 4.0))
        assertEquals(3, d.size)
        assertEquals(0.95, d[0].performanceIndex!!, 1e-9)
        assertEquals(4.0, d[0].clearSkyHours, 1e-9)
        assertNull("rain unknown stays null", d[0].rainMm)
        assertEquals(4.0, d[1].rainMm!!, 0.0)
        assertEquals(0.0, d[1].clearSkyHours, 0.0)
        assertEquals(2.0, d[2].clearSkyHours, 1e-9) // link problems and near-limit samples excluded
        assertEquals(0.9, d[2].performanceIndex!!, 1e-9)
        val m = PerformanceHistory.monthly(d)
        assertEquals(1, m.size)
        assertEquals(1, m[0].clearDays) // day 3 has only 2 clear hours
    }
}

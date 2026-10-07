package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.diagnostics.MpptAnalyzer
import com.solartracker.pro.core.energy.BatteryType
import com.solartracker.pro.core.export.DeepReportExport
import com.solartracker.pro.core.fixtures.AnalyzerFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeepAnalyzerTest {
    private val zone = ZoneId.of("UTC")
    private val month = AnalyzerFixtures.month()
    private val s = month.samples
    private val end = s.last().time
    /** 16S LiFePO4 (51.2 V nominal) → full resting voltage 16 × 3.40 = 54.4 V. */
    private val battery = BatteryContext(BatteryType.LIFEPO4, 51.2, AnalyzerFixtures.CAPACITY_AH)
    /** Model expectation = the fixture's clear-sky curve; cloud cover reported on the cloudy days. */
    private val context = ContextProvider { t ->
        val day = Duration.between(AnalyzerFixtures.START, t).toDays().toInt()
        val h = (t.epochSecond % 86_400) / 3600.0
        val clear = if (h in 6.0..18.0) AnalyzerFixtures.PV_PEAK_W * kotlin.math.sin(Math.PI * (h - 6) / 12) else 0.0
        AnalysisContext(expectedPvW = clear * if (AnalyzerFixtures.cloudy(day)) 0.3 else 1.0, cloudCoverPercent = if (AnalyzerFixtures.cloudy(day)) 90.0 else 5.0,
            clearSkyIndex = if (AnalyzerFixtures.cloudy(day)) 0.3 else 0.95, sunElevationDeg = if (h in 6.0..18.0) 40.0 else -10.0)
    }

    private fun snapshot(at: Instant, maxCharge: Double, bulk: Double? = null) = AnenjiSettingsSnapshot.build(at, "Anenji 6.2 kW",
        imported = buildMap { put(SettingKey.MAX_CHARGE_CURRENT, maxCharge); put(SettingKey.BATTERY_CAPACITY, 230.0); bulk?.let { put(SettingKey.BULK_VOLTAGE, it) } })

    @Test
    fun eventLogFindsLowBatteryPatternWithCauseAndCommunicationAndRestart() {
        val events = AnenjiEventLog.build(s, month.comm)
        val low = events.filter { it.code == 8 }
        assertTrue("low battery events: ${low.size}", low.size >= 4)
        assertTrue(low.all { it.category == EventCategory.BATTERY && it.severity == EventSeverity.WARNING })
        assertTrue(low.all { it.duration == null || it.duration!! > Duration.ZERO })
        assertTrue(events.any { it.category == EventCategory.COMMUNICATION && it.description.startsWith("Utrata komunikacji") })
        assertTrue(events.any { it.description.startsWith("Restart") && it.description.contains("licznik czasu pracy") })
        assertTrue(events.any { it.category == EventCategory.GRID })
        val pattern = AnenjiEventLog.patterns(events, s, zone).first { it.code == 8 }
        assertTrue(pattern.likelyCause, pattern.likelyCause.startsWith("Niewystarczająca energia"))
        assertTrue(pattern.precedingSocMedian!! < 30)
        assertNotNull(pattern.dominantWindow)
        assertTrue(pattern.confidence in 0.3..0.95)
    }

    @Test
    fun communicationReportCountsTimeoutsGapsAndExplainsTheScore() {
        val c = AnenjiCommunicationAnalyzer.analyze(s, month.comm)
        assertEquals(2, c.timeouts)
        assertTrue(c.missingSamples >= 1)
        assertTrue(c.longestOutage >= Duration.ofMinutes(5))
        assertTrue(c.score!! in 80..99)
        assertEquals(100 - c.penalties.sumOf { it.second }, c.score)
        assertEquals(1, c.reconnects)
        val none = AnenjiCommunicationAnalyzer.analyze(emptyList(), emptyList())
        assertNull(none.score)
    }

    @Test
    fun settingsSnapshotDiffAndNotAvailable() {
        val a = snapshot(AnalyzerFixtures.START, 80.0)
        val b = snapshot(AnalyzerFixtures.START.plus(Duration.ofDays(15)), 60.0)
        val diff = AnenjiSettingsDiff.between(a, b)
        val change = diff.changes.single()
        assertEquals(SettingKey.MAX_CHARGE_CURRENT, change.key)
        assertEquals("80 A", change.old.display)
        assertEquals("60 A", change.new.display)
        assertTrue(diff.describe(zone).contains("Zmieniono między"))
        assertTrue(AnenjiSettingsDiff.between(a, a).describe(zone).contains("Nie wykryto zmian"))
        assertEquals(SettingStatus.NOT_AVAILABLE, a.value(SettingKey.FLOAT_VOLTAGE)!!.status)
        assertEquals("NOT_AVAILABLE", a.value(SettingKey.FLOAT_VOLTAGE)!!.display)
        assertEquals(SettingStatus.UNVERIFIED, a.value(SettingKey.MAX_CHARGE_CURRENT)!!.status)
        assertEquals(SnapshotVerification.NONE, a.verification) // nothing read from a verified register
        assertTrue(a.format(zone).startsWith("ANENJI CONFIGURATION SNAPSHOT"))
        assertTrue("no settings register is claimed as known", SettingKey.entries.all { it.register == null })
        // A setting NOT_AVAILABLE on one side is not reported as a change.
        val c = AnenjiSettingsSnapshot.build(a.timestamp.plusSeconds(60), "x")
        assertTrue(AnenjiSettingsDiff.between(a, c).noChanges)
    }

    @Test
    fun trendsRespectCoverageAndComputeStatistics() {
        val t = TrendAnalyzer.analyze(s, end, listOf(Channel.SOC, Channel.PV_POWER))
        val windows = t.filter { it.channel == Channel.SOC }.map { it.window }
        assertTrue(TrendWindow.H24 in windows && TrendWindow.D7 in windows && TrendWindow.D30 in windows)
        assertFalse("30 days do not cover a year", TrendWindow.Y1 in windows)
        val soc = t.first { it.channel == Channel.SOC && it.window == TrendWindow.D30 }
        assertTrue(soc.min >= 10.0 && soc.max <= 100.0 && soc.p95 >= soc.median)
        assertTrue(TrendAnalyzer.analyze(emptyList(), end).isEmpty())
        val rising = TrendAnalyzer.stats(Channel.BATTERY_TEMPERATURE, TrendWindow.D7, (0 until 20).map { end.minus(Duration.ofHours(20L - it)) to 20.0 + it }, 1.0)
        assertEquals(TrendDirection.RISING, rising.direction)
        val spike = TrendAnalyzer.stats(Channel.BATTERY_TEMPERATURE, TrendWindow.D7, (0 until 20).map { end.minus(Duration.ofHours(20L - it)) to if (it == 10) 90.0 else 25.0 + (it % 3) }, 1.0)
        assertEquals(1, spike.anomalies.size)
    }

    @Test
    fun advisorFindsClippingCapacityAndVoltageProblems() {
        val f = AnenjiConfigurationAdvisor.analyze(s, snapshot(end, 60.0, bulk = 52.0), battery, AnalyzerFixtures.PV_LIMIT_W, zone) { t -> context.at(t)?.expectedPvW }
        val clip = f.single { it.title.startsWith("PV osiąga limit") }
        assertTrue(clip.impactKwh!! > 1.0)
        assertEquals(FindingSeverity.WARNING, clip.severity)
        val cap = f.single { it.title.contains("ojemność") }
        assertTrue(cap.evidence.joinToString(), cap.evidence.any { it.contains("Zaobserwowana: ~2") }) // ~230 Ah
        val bulk = f.single { it.title.contains("napięcie ładowania") }
        assertTrue(bulk.evidence.any { it.contains("54.4") || it.contains("54,4") })
        f.forEach { x -> assertTrue(x.confidence in 0.0..1.0); assertTrue(x.reason.isNotBlank()); assertTrue(x.impact.isNotBlank()) }
        // Wrong configured capacity is detected as a discrepancy.
        val wrong = AnenjiConfigurationAdvisor.capacity(s, null, battery.copy(capacityAh = 400.0))!!
        assertEquals(FindingSeverity.WARNING, wrong.severity)
        assertTrue(wrong.possibleCauses.isNotEmpty())
        // Without a known limit there is no clipping claim.
        assertNull(AnenjiConfigurationAdvisor.clipping(s, null, zone, null))
    }

    @Test
    fun incidentReconstructsTheMorningDepletion() {
        val events = AnenjiEventLog.build(s, month.comm)
        val day15 = LocalDate.of(2026, 9, 16)
        val at = day15.atTime(6, 45).atZone(zone).toInstant()
        val r = IncidentAnalyzer.analyze(at, s, events, zone, context)
        assertTrue(r.conclusion, r.conclusion.startsWith("Rozładowanie baterii"))
        assertTrue(r.timeline.any { it.text.contains("Obciążenie wzrosło") })
        assertTrue(r.confidence >= 0.5)
        val empty = IncidentAnalyzer.analyze(Instant.parse("2020-01-01T00:00:00Z"), s, events, zone)
        assertEquals(0.0, empty.confidence, 0.0)
    }

    @Test
    fun whyQuestionsAreParsedAndAnsweredFromData() {
        val today = LocalDate.of(2026, 9, 30)
        assertEquals(WhyQuestion.LOW_ENERGY_DAY to today.minusDays(1), WhyAnalyzer.parse("Dlaczego wczoraj było mało energii?", today))
        assertEquals(WhyQuestion.BATTERY_LOW, WhyAnalyzer.parse("Dlaczego bateria spadła do 20%?", today)!!.first)
        assertEquals(WhyQuestion.PV_LOW, WhyAnalyzer.parse("Dlaczego PV dawało tylko 1.2 kW?", today)!!.first)
        assertEquals(WhyQuestion.GRID_ACTIVATED, WhyAnalyzer.parse("Dlaczego włączył się grid?", today)!!.first)
        assertEquals(WhyQuestion.ALARM, WhyAnalyzer.parse("Dlaczego Anenji zgłosił alarm?", today)!!.first)
        assertEquals(WhyQuestion.LOW_MORNING_SOC, WhyAnalyzer.parse("Dlaczego rano SOC było niższe niż zwykle?", today)!!.first)
        assertEquals(WhyQuestion.MPPT_LOW, WhyAnalyzer.parse("Dlaczego MPPT 2 produkuje mniej?", today)!!.first)
        assertEquals(WhyQuestion.CLIPPING, WhyAnalyzer.parse("Dlaczego wystąpił clipping?", today)!!.first)
        assertNull(WhyAnalyzer.parse("Jaka jest pogoda?", today))

        val events = AnenjiEventLog.build(s, month.comm)
        val cloudyDay = LocalDate.of(2026, 9, 15) // day 14
        val low = WhyAnalyzer.answer(WhyQuestion.LOW_ENERGY_DAY, cloudyDay, s, events, zone, context)
        assertTrue(low.conclusion, low.conclusion.startsWith("Pochmurny"))
        val morning = WhyAnalyzer.answer(WhyQuestion.LOW_MORNING_SOC, LocalDate.of(2026, 9, 16), s, events, zone, context)
        assertTrue(morning.conclusion, morning.conclusion.contains("wieczorem") || morning.conclusion.contains("nocy"))
        val bat = WhyAnalyzer.answer(WhyQuestion.BATTERY_LOW, LocalDate.of(2026, 9, 16), s, events, zone, context)
        assertNotNull(bat.incident)
        assertTrue(WhyAnalyzer.answer(WhyQuestion.MPPT_LOW, cloudyDay, s, events, zone).conclusion.startsWith("N/A"))
        assertTrue(WhyAnalyzer.answer(WhyQuestion.CLIPPING, LocalDate.of(2026, 9, 2), s, events, zone, pvLimitW = AnalyzerFixtures.PV_LIMIT_W).conclusion.contains("1800"))
        assertEquals(0.0, WhyAnalyzer.answer(WhyQuestion.PV_LOW, LocalDate.of(2020, 1, 1), s, events, zone).confidence, 0.0)
    }

    @Test
    fun fullReportSeparatesSimulatorDataAndExplainsHealth() {
        val sim = AnalyzerFixtures.month(3, com.solartracker.pro.core.anenji.DataOrigin.SIMULATOR).samples
        val r = AnenjiDeepAnalyzer.analyze(DeepAnalysisInput("Anenji 6.2 kW", s + sim, month.comm,
            listOf(snapshot(AnalyzerFixtures.START, 80.0), snapshot(AnalyzerFixtures.START.plus(Duration.ofDays(15)), 60.0)), battery,
            AnalyzerFixtures.PV_LIMIT_W, zone, end, context, forecastAccuracyPercent = 88.0))
        assertFalse(r.simulated)
        assertEquals(sim.size, r.excludedSimulatorSamples)
        assertEquals(DataOrigin.DEVICE, r.origin)
        assertEquals(1, r.configurationChanges.changes.size)
        val h = r.health
        assertNotNull(h.overall)
        assertNull("MPPT not reported → N/A, not a default", h.of(HealthCategory.MPPT).score)
        h.categories.filter { it.score != null && it.category != HealthCategory.FORECAST }.forEach { c -> assertEquals(c.category.name, 100 - c.deductions.sumOf { it.points }, c.score!!.coerceAtLeast(100 - c.deductions.sumOf { it.points })) }
        assertTrue(h.of(HealthCategory.BATTERY).deductions.isNotEmpty())
        assertEquals(88, h.of(HealthCategory.FORECAST).score)
        assertTrue(r.unresolved.any { it.contains("REAL DEVICE VALIDATION") })
        assertTrue(r.energy.pvKwh > 100)
        assertEquals(24, r.loadProfileW.size)
        assertTrue(r.findings.any { it.title.startsWith("PV osiąga limit") })

        val json = Json.parseToJsonElement(DeepReportExport.json(r, "0.14.0")).jsonObject
        assertEquals("ANENJI_FULL_DIAGNOSTIC_REPORT/1", json["format"]!!.jsonPrimitive.content)
        listOf("device", "period", "configuration", "summaries", "events", "communication", "anomalies", "trends", "health", "recommendations", "dataQuality", "unresolved")
            .forEach { assertNotNull(it, json[it]) }
        assertTrue(json["recommendations"]!!.jsonArray.all { it.jsonObject["confidence"] != null })
        val csv = DeepReportExport.csv(r, zone).lines()
        assertEquals("section,item,value,detail,confidence", csv.first())
        assertTrue(csv.any { it.startsWith("health,OVERALL") })
        val lines = DeepReportExport.lines(r, zone)
        assertTrue(lines.any { it.startsWith("SYSTEM HEALTH:") })
        assertTrue(lines.any { it.contains("tylko do odczytu") })
    }

    @Test
    fun onlySimulatorDataIsLabelledAndEmptyInputIsHandled() {
        val sim = AnalyzerFixtures.month(2, DataOrigin.SIMULATOR)
        val r = AnenjiDeepAnalyzer.analyze(DeepAnalysisInput("sym", sim.samples, sim.comm, zone = zone, now = sim.samples.last().time))
        assertTrue(r.simulated)
        assertTrue(r.unresolved.any { it.contains("symulatora") })
        val e = AnenjiDeepAnalyzer.analyze(DeepAnalysisInput("none", emptyList(), zone = zone, now = end))
        assertNull(e.health.overall)
        assertEquals(0, e.dataQuality.samples)
    }

    @Test
    fun missingDataAndImpossibleValuesShowUpInDataQualityAndAnomalies() {
        val gappy = s.filterIndexed { i, _ -> i % 50 != 0 }.filter { it.time.isBefore(AnalyzerFixtures.START.plus(Duration.ofDays(3))) }
            .map { if (it.time == AnalyzerFixtures.START.plus(Duration.ofHours(30))) it.copy(values = it.values + (Channel.SOC to 150.0)) else it }
            .map { it.copy(values = it.values - Channel.AC_VOLTAGE) }
        val q = AnenjiDeepAnalyzer.dataQuality(gappy, DataOrigin.IMPORTED, com.solartracker.pro.core.inverter.PlausibilityLimits())
        assertTrue(Channel.AC_VOLTAGE in q.missingChannels)
        assertTrue(q.invalidShare > 0)
        val r = AnenjiDeepAnalyzer.analyze(DeepAnalysisInput("x", gappy, zone = zone, now = gappy.last().time))
        assertTrue(r.anomalies.any { it.title.startsWith("Anomalia danych") })
        assertTrue(r.health.of(HealthCategory.DATA_QUALITY).deductions.isNotEmpty())
    }

    @Test
    fun mpptFeedsHealthWhenAvailable() {
        val m = MpptAnalyzer.analyze(listOf(com.solartracker.pro.core.inverter.MpptReading(1, 80.0, 11.0, 901.0), com.solartracker.pro.core.inverter.MpptReading(2, 80.0, 8.9, 650.0)),
            listOf(com.solartracker.pro.core.diagnostics.MpptConfig(1, 1070.0, 2), com.solartracker.pro.core.diagnostics.MpptConfig(2, 1070.0, 2)), mapOf(1 to 920.0, 2 to 910.0))
        val h = SystemHealthEngine.assess(HealthEvidence(mppt = m))
        assertEquals(75, h.of(HealthCategory.MPPT).score)
        assertEquals(75, h.overall) // only MPPT assessable → weighted mean of one
    }
}

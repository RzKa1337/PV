package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.export.ForensicPackageExport
import com.solartracker.pro.core.fixtures.ForensicScenarios
import com.solartracker.pro.core.fixtures.ForensicScenarios.Scenario
import com.solartracker.pro.core.inverter.ManualReference
import com.solartracker.pro.core.inverter.ReferenceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** The nine SIMULATED scenarios with their expected diagnoses (ground truth by construction). */
class ForensicScenarioTest {
    private val zone = ZoneId.of("UTC")

    private class Run(val data: ForensicScenarios.Data, val f: ForensicContext, val report: PeriodReport) {
        fun types() = report.diagnoses.map { it.anomaly.type }.toSet()
        fun of(t: AnomalyType) = report.diagnoses.filter { it.anomaly.type == t }
        fun summary() = data.scenario.name + ": " + report.diagnoses.joinToString { "${it.anomaly.type}/${it.observation}/${it.certainty}/${it.severity}" }
    }

    private fun run(s: Scenario): Run {
        val d = ForensicScenarios.build(s)
        val ctx = ForensicScenarios.context
        val changes = AnenjiSettingsDiff.timeline(d.settings).changes
        val f = ForensicContext(d.samples, AnenjiEventLog.build(d.samples, d.comm, changes), d.comm, zone, ctx, SystemBaselineEngine(d.samples, zone, ctx),
            d.settings.lastOrNull(), null, com.solartracker.pro.core.fixtures.AnalyzerFixtures.PV_LIMIT_W, 1.0)
        return Run(d, f, ForensicPeriodAnalyzer.analyze(f, ForensicPeriod.CUSTOM, ForensicScenarios.dayStart, ForensicScenarios.dayEnd))
    }

    @Test
    fun allScenariosAreMarkedSimulated() {
        Scenario.entries.forEach { s -> val r = run(s); assertTrue(s.name, r.report.simulated); assertEquals(s.name, DataStatus.OK, r.report.status.status) }
    }

    @Test
    fun normalDayHasNoWarnings() {
        val r = run(Scenario.SCENARIO_NORMAL)
        assertTrue(r.summary(), r.report.diagnoses.none { it.severity != DiagnosisSeverityLevel.INFO })
        assertFalse(r.summary(), AnomalyType.LOW_PV in r.types())
    }

    @Test
    fun lowPvIsFoundWeatherEliminated() {
        val r = run(Scenario.SCENARIO_LOW_PV)
        val d = r.of(AnomalyType.LOW_PV).firstOrNull() ?: error(r.summary())
        assertEquals(Certainty.LIKELY, d.observation)
        assertTrue(d.rootCause.eliminated.any { it.cause == "Zachmurzenie" })
        assertEquals(DiagnosisSeverityLevel.WARNING, d.severity)
        assertTrue(r.report.lostKwh!! > 1.0)
        assertEquals(AnomalyType.LOW_PV, r.report.mostImportant!!.type)
    }

    @Test
    fun mpptFaultPointsAtMppt() {
        val r = run(Scenario.SCENARIO_MPPT_FAULT)
        assertTrue(r.summary(), AnomalyType.MPPT_IMBALANCE in r.types())
        val low = r.of(AnomalyType.LOW_PV).firstOrNull() ?: error(r.summary())
        assertEquals(r.summary(), "Anomalia MPPT", low.rootCause.mostLikely)
        assertTrue(low.recommendation.contains("MPPT"))
        assertTrue(low.evidence.nodes.any { it.type == EvidenceType.MPPT && it.status == "nieprawidłowy" })
    }

    @Test
    fun lowBatteryExplainedByHighLoad() {
        val r = run(Scenario.SCENARIO_LOW_BATTERY)
        val d = r.of(AnomalyType.LOW_BATTERY).firstOrNull() ?: error(r.summary())
        assertEquals(r.summary(), "Duże obciążenie", d.rootCause.mostLikely)
        assertNotNull(d.impact.batteryImpact ?: d.impact.energyKwh)
        val why = WhyAnalyzer.answer(WhyQuestion.HIGH_CONSUMPTION, ForensicScenarios.dayStart.atZone(zone).toLocalDate(), r.data.samples, r.f.events, zone)
        assertTrue(why.conclusion, why.conclusion.contains("godzinach"))
        assertTrue(why.findings.any { it.startsWith("18:00") || it.startsWith("19:00") || it.startsWith("20:00") || it.startsWith("21:00") })
    }

    @Test
    fun highLoadIsConfirmedOverloadCritical() {
        val r = run(Scenario.SCENARIO_HIGH_LOAD)
        val d = r.of(AnomalyType.OVERLOAD).firstOrNull() ?: error(r.summary())
        assertEquals(Certainty.CONFIRMED, d.observation)
        assertEquals(DiagnosisSeverityLevel.CRITICAL, d.severity)
        assertEquals(1, r.report.bySeverity[DiagnosisSeverityLevel.CRITICAL])
    }

    @Test
    fun gridOutageHasDowntimeAndBatteryImpact() {
        val r = run(Scenario.SCENARIO_GRID_OUTAGE)
        val d = r.of(AnomalyType.GRID_OUTAGE).singleOrNull() ?: error(r.summary())
        assertTrue(d.impact.downtime!! >= Duration.ofMinutes(55))
        assertFalse(r.summary(), AnomalyType.GRID_FREQUENCY in r.types()) // frequency is not judged without voltage
        val rec = IncidentReconstructor.reconstruct(ForensicScenarios.at(15.5), r.f, r.report.diagnoses)
        assertEquals(IncidentReconstructor.OFFSETS.size, rec.snapshots.size)
        assertEquals(0.0, rec.snapshots.single { it.offsetMinutes == 0 }.sample!![Channel.GRID_VOLTAGE]!!, 0.0)
        assertEquals(230.0, rec.snapshots.single { it.offsetMinutes == -60 }.sample!![Channel.GRID_VOLTAGE]!!, 0.0)
        assertTrue(rec.diagnoses.first().anomaly.type == AnomalyType.GRID_OUTAGE)
    }

    @Test
    fun communicationFailureIsNotMistakenForPvLoss() {
        val r = run(Scenario.SCENARIO_COMMUNICATION_FAILURE)
        val d = r.of(AnomalyType.COMM_TIMEOUT).firstOrNull() ?: error(r.summary())
        assertTrue(d.impact.downtime!! >= Duration.ofMinutes(35))
        assertNull(d.impact.energyKwh)
        assertFalse(r.summary(), AnomalyType.LOW_PV in r.types())
        val rec = IncidentReconstructor.reconstruct(ForensicScenarios.at(12.3), r.f, r.report.diagnoses)
        assertNull("no data at the outage – not interpolated", rec.snapshots.single { it.offsetMinutes == 0 }.sample)
        assertEquals(DataStatus.NO_DATA, rec.snapshots.single { it.offsetMinutes == 0 }.status)
        assertEquals(DataStatus.PARTIAL_ANALYSIS, rec.status.status)
    }

    @Test
    fun restartIsConfirmedCauseUnknown() {
        val r = run(Scenario.SCENARIO_INVERTER_RESTART)
        val d = r.of(AnomalyType.RESTART).singleOrNull() ?: error(r.summary())
        assertEquals(Certainty.CONFIRMED, d.observation)
        assertEquals(DiagnosisSeverityLevel.WARNING, d.severity)
        assertTrue(d.certainty != Certainty.CONFIRMED)
        val why = WhyAnalyzer.answer(WhyQuestion.INVERTER_RESTART, ForensicScenarios.dayStart.atZone(zone).toLocalDate(), r.data.samples, r.f.events, zone)
        assertTrue(why.conclusion, why.conclusion.startsWith("Przyczyna nieznana"))
        assertEquals(WhyQuestion.INVERTER_RESTART, WhyAnalyzer.parse("Dlaczego falownik się zrestartował wczoraj?", ForensicScenarios.dayStart.atZone(zone).toLocalDate())!!.first)
    }

    @Test
    fun configurationChangePrecedesUnexpectedGrid() {
        val r = run(Scenario.SCENARIO_CONFIGURATION_CHANGE)
        val d = r.of(AnomalyType.UNEXPECTED_GRID).firstOrNull() ?: error(r.summary())
        assertEquals("Ustawienie priorytetu źródła", d.rootCause.mostLikely)
        val changes = AnenjiSettingsDiff.timeline(r.data.settings).changes
        assertEquals(1, changes.size)
        val impact = ConfigurationForensics.analyze(changes, r.f, r.report.diagnoses).single()
        assertTrue(impact.incidentsAfter.any { it.anomaly.type == AnomalyType.UNEXPECTED_GRID })
        assertTrue(impact.note.contains("nie dowód"))
        assertTrue(r.f.events.any { it.description.startsWith("Zmiana konfiguracji") })
    }

    @Test
    fun longTrendsNeedEnoughWeeks() {
        val r = run(Scenario.SCENARIO_NORMAL)
        val now = ForensicScenarios.dayEnd
        val trends = LongTrendAnalyzer.analyze(r.f, now)
        val cap = trends.single { it.metric == "Pozorna pojemność baterii" }
        assertTrue(cap.note, cap.sufficient)
        val expected = com.solartracker.pro.core.fixtures.AnalyzerFixtures.CAPACITY_AH * com.solartracker.pro.core.fixtures.AnalyzerFixtures.VOLTAGE / 1000
        assertEquals(expected, cap.weekly.last().second, expected * 0.05)
        assertEquals(TrendDirection.STABLE, cap.direction)
        val short = LongTrendAnalyzer.analyze(r.f.copy(samples = r.f.samples.filter { it.time.isAfter(now.minus(Duration.ofDays(10))) }), now)
        assertTrue(short.all { !it.sufficient && it.note.startsWith("ZA MAŁO DANYCH") })
        assertTrue(trends.single { it.metric == "Zużycie dobowe" }.sufficient)
    }

    @Test
    fun forensicPackageContainsAllFilesAndTraceability() {
        val r = run(Scenario.SCENARIO_LOW_PV)
        val refs = listOf(ManualReference(0x0100, 520, 52.0, 52.0, 52.1, "V", Instant.parse("2026-09-28T10:00:00Z"), true))
        assertEquals(refs, ReferenceCodec.decode(ReferenceCodec.encode(refs)))
        assertTrue(ReferenceCodec.decode("{broken").isEmpty())
        val input = ForensicPackageExport.Input(r.report, r.data.samples.filter { !it.time.isBefore(ForensicScenarios.dayStart) }, r.f.events, r.data.settings, emptyList(), refs,
            emptyList(), emptyList(), "test", "SIMULATED", zone, Instant.parse("2026-10-08T00:00:00Z"))
        val files = ForensicPackageExport.files(input)
        assertEquals(listOf("report.json", "diagnoses.json", "evidence.json", "events.csv", "telemetry.csv", "registers.csv", "settings.csv", "metadata.json"), files.keys.toList())
        val evidence = files["evidence.json"]!!.toString(Charsets.UTF_8)
        assertTrue(evidence.contains("SCENARIO_LOW_PV.csv")) // conclusion → evidence → raw source
        assertTrue(files["telemetry.csv"]!!.toString(Charsets.UTF_8).lines()[1].contains("SIMULATOR"))
        assertTrue(files["metadata.json"]!!.toString(Charsets.UTF_8).contains("UNVERIFIED"))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ForensicPackageExport.zip(files))).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertEquals(files.size, names.size)
        assertTrue(names.all { it.startsWith("ANENJI_FORENSIC_PACKAGE/") })
        val lines = ForensicPackageExport.lines(input)
        assertTrue(lines.any { it.contains("DANE SYMULOWANE") })
        assertTrue(lines.any { it.startsWith("DIAGNOZA:") })
    }

    @Test
    fun periodAnalysisOf30DaysIsFastEnough() {
        val d = ForensicScenarios.build(Scenario.SCENARIO_NORMAL)
        val ctx = ForensicScenarios.context
        val f = ForensicContext(d.samples, AnenjiEventLog.build(d.samples, d.comm), d.comm, zone, ctx, SystemBaselineEngine(d.samples, zone, ctx), null, null, 1800.0, null)
        val t0 = System.nanoTime()
        val report = ForensicPeriodAnalyzer.analyze(f, ForensicPeriod.D30, d.samples.first().time, d.samples.last().time)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("30 days took $ms ms", ms < 20_000)
        assertTrue(report.ranking.isNotEmpty())
        assertNull("no price → cost N/A", report.cost)
    }
}

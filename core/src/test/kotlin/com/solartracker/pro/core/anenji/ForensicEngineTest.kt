package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.fixtures.AnalyzerFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Forensic engine on SIMULATED data (AnalyzerFixtures) with injected faults – ground truth is known by construction. */
class ForensicEngineTest {
    private val zone = ZoneId.of("UTC")
    private val month = AnalyzerFixtures.month()
    private val faultDay = 26
    private val faultFrom = AnalyzerFixtures.START.plus(Duration.ofDays(faultDay.toLong())).plus(Duration.ofHours(10))
    private val faultTo = faultFrom.plus(Duration.ofHours(2))

    /** Sunny day 26, 10:00–12:00: PV at 40 % of normal with a raw source attached (simulated panel fault). */
    private val samples = month.samples.map { s ->
        if (!s.time.isBefore(faultFrom) && s.time.isBefore(faultTo)) {
            val pv = s[Channel.PV_POWER]!! * 0.4
            s.copy(values = s.values + (Channel.PV_POWER to pv), raw = RawRef("SIMULATED.csv", "linia ${s.time.epochSecond}", null, mapOf("pv_w" to "%.0f".format(pv)), emptyMap()))
        } else s
    }

    private val context = ContextProvider { t ->
        val day = Duration.between(AnalyzerFixtures.START, t).toDays().toInt()
        val h = (t.epochSecond % 86_400) / 3600.0
        val clear = if (h in 6.0..18.0) AnalyzerFixtures.PV_PEAK_W * kotlin.math.sin(Math.PI * (h - 6) / 12) else 0.0
        AnalysisContext(expectedPvW = clear * if (AnalyzerFixtures.cloudy(day)) 0.3 else 1.0, cloudCoverPercent = if (AnalyzerFixtures.cloudy(day)) 90.0 else 5.0,
            clearSkyIndex = if (AnalyzerFixtures.cloudy(day)) 0.3 else 0.95, ambientC = 18.0, sunElevationDeg = if (h in 6.0..18.0) 15 + 30 * kotlin.math.sin(Math.PI * (h - 6) / 12) else -10.0,
            shadingFactor = 1.0, snowExpected = false)
    }

    private fun forensic(s: List<AnalyzerSample> = samples, ctx: ContextProvider? = context) = ForensicContext(
        s, AnenjiEventLog.build(s, month.comm), month.comm, zone, ctx, SystemBaselineEngine(s, zone, ctx), null, null, AnalyzerFixtures.PV_LIMIT_W, 1.2,
    )

    @Test
    fun baselineComparesOnlySimilarConditions() {
        val f = forensic()
        val noon = AnalyzerFixtures.START.plus(Duration.ofDays(27)).plus(Duration.ofHours(11))
        val b = f.baseline.pvAt(noon)!!
        assertTrue("typical PV ${b.median}", b.median in 1500.0..2000.0)
        assertTrue(b.matchedOn.contains("±"))
        assertTrue(b.samples >= SystemBaselineEngine.MIN_SAMPLES)
        assertNull("night → INSUFFICIENT DATA", f.baseline.pvAt(noon.plus(Duration.ofHours(12))))
        assertNull("no model → no PV baseline", SystemBaselineEngine(samples, zone, null).pvAt(noon))
        val load = f.baseline.hourly(Channel.LOAD_POWER, noon)!!
        assertEquals(400.0, load.median, 1e-6)
        assertNotNull(f.baseline.dailyEnergy(Channel.PV_POWER, noon.atZone(zone).toLocalDate()))
    }

    @Test
    fun detectsInjectedLowPvButNotCloudyDays() {
        val anomalies = ForensicAnomalyDetector.detect(forensic())
        val low = anomalies.filter { it.type == AnomalyType.LOW_PV }
        assertTrue("LOW_PV at fault: $low", low.any { !it.start.isAfter(faultTo) && !it.end.isBefore(faultFrom) })
        assertTrue("no LOW_PV on cloudy days (weather explains it)", low.none { AnalyzerFixtures.cloudy(Duration.between(AnalyzerFixtures.START, it.start).toDays().toInt()) })
        assertTrue(anomalies.any { it.type == AnomalyType.RESTART })
        assertTrue(anomalies.any { it.type.category == AnomalyCategory.COMMUNICATION })
        assertTrue(anomalies.any { it.type == AnomalyType.CLIPPING })
    }

    @Test
    fun lowPvDiagnosisEliminatesWeatherAndTracesToRawSource() {
        val f = forensic()
        val diagnoses = ForensicDiagnosisBuilder.build(ForensicAnomalyDetector.detect(f), f)
        val d = diagnoses.first { it.anomaly.type == AnomalyType.LOW_PV && !it.anomaly.start.isAfter(faultTo) && !it.anomaly.end.isBefore(faultFrom) }
        assertTrue("weather eliminated: ${d.rootCause.eliminated}", d.rootCause.eliminated.any { it.cause == "Zachmurzenie" })
        assertTrue(d.rootCause.eliminated.any { it.cause == "Zacienienie" })
        assertTrue("dirt stays possible without evidence", d.rootCause.possible.any { it.cause == "Zabrudzenie" && it.status == "możliwa" })
        assertTrue("never CONFIRMED with an unknown cause left", d.certainty != Certainty.CONFIRMED || d.rootCause.possible.size == 1)
        assertTrue(d.severity != DiagnosisSeverityLevel.CRITICAL)
        assertTrue(d.evidence.nodes.any { it.type == EvidenceType.BASELINE && it.value != null })
        assertTrue(d.evidence.contradicting.isNotEmpty())
        val impact = d.impact
        assertTrue("lost energy ${impact.energyKwh}", (impact.energyKwh ?: 0.0) > 0.5)
        assertEquals(impact.energyKwh!! * 1.2, impact.cost!!, 1e-9)
        val c = d.confidence
        assertTrue(c.value in 0.05..0.95)
        assertTrue(c.describe().size >= 5)
        val trace = ForensicDiagnosisBuilder.trace(d, zone)
        assertTrue(trace.any { it.contains("ŹRÓDŁO: SIMULATED.csv") })
        assertTrue(trace.any { it.contains("pv_w=") })
        assertTrue(d.evidence.render().contains("└──"))
    }

    @Test
    fun withoutModelImpactIsNotAvailable() {
        val f = forensic(ctx = null)
        val a = Anomaly(AnomalyType.LOW_PV, faultFrom, faultTo, 700.0, 1800.0, "W", "test", samples.filter { it.time in faultFrom..faultTo })
        val impact = EnergyImpactCalculator.impact(a, f)
        assertNull(impact.energyKwh)
        assertTrue(impact.basis.contains("brak"))
        val d = ForensicDiagnosisBuilder.build(listOf(a), f).single()
        assertTrue(d.evidence.nodes.any { it.type == EvidenceType.EXPECTATION && it.role == EvidenceRole.UNKNOWN })
        assertTrue("no model, no weather → not confirmed", d.certainty != Certainty.CONFIRMED)
    }

    @Test
    fun communicationOutageHasDowntimeNotEnergy() {
        val f = forensic()
        val d = ForensicDiagnosisBuilder.build(ForensicAnomalyDetector.detect(f), f).first { it.anomaly.type.category == AnomalyCategory.COMMUNICATION && it.impact.downtime != null }
        assertNull(d.impact.energyKwh)
        assertTrue(d.impact.downtime!! >= Duration.ofMinutes(5))
        assertTrue(d.recommendation.contains("RS232") || d.recommendation.contains("RS485"))
    }

    @Test
    fun confidenceEngineIsExplicit() {
        assertTrue(ConfidenceEngine.sourceReliability(DataOrigin.SIMULATOR) < ConfidenceEngine.sourceReliability(DataOrigin.DEVICE))
        assertTrue(ConfidenceEngine.sourceReliability(DataOrigin.DEVICE, verifiedRegisters = true) > ConfidenceEngine.sourceReliability(DataOrigin.DEVICE))
        val strong = ConfidenceBreakdown(0.9, 1.0, 0.8, ConfidenceEngine.agreement(4, 0), 0.9, 0.8).value
        val weak = ConfidenceBreakdown(0.9, 1.0, 0.8, ConfidenceEngine.agreement(1, 3), 0.9, 0.8).value
        assertTrue(strong > weak)
        assertTrue("one weak factor pulls down", ConfidenceBreakdown(0.9, 0.05, 0.8, 0.9, null, null).value < 0.5)
    }
}

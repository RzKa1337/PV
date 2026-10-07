package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.inverter.BatteryReading
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.GridReading
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.IssueType
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.LoadReading
import com.solartracker.pro.core.inverter.MpptReading
import com.solartracker.pro.core.inverter.PvReading
import com.solartracker.pro.core.inverter.TelemetryField
import com.solartracker.pro.core.inverter.TelemetryIssue
import com.solartracker.pro.core.pv.Irradiance
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PvDiagnosticEngineTest {
    private val site = GeoLocation(37.3453, 27.2646, 50.0)
    private val zone = ZoneId.of("Europe/Istanbul")
    private val now = LocalDate.of(2026, 6, 21).atTime(13, 0).atZone(zone).toInstant()
    private val array = PvArrayConfig(4, 535.0, 0.0, 180.0)
    private val peak = array.peakPowerW
    private val sunny = PvConditions(Irradiance(850.0, 110.0), ambientC = 28.0, windMs = 2.0)

    private fun reality(actualW: Double?, kind: DataKind = DataKind.MEASURED, conditions: PvConditions = sunny) =
        PvRealityEngine.assess(array, LossProfile(), site, now, conditions, actualW?.let { Quantity(it, "W", kind, "Anenji", now) },
            IrradianceBasis.FORECAST, shadingConfidence = 0.8, clearSkyIndex = 1.0)

    private val expected = reality(null).expectedW

    /** Samples every 5 min over the last [minutes] at [ratio] of the expectation. */
    private fun recent(ratio: Double, minutes: Long = 30) = (0..minutes step 5).map {
        RealitySample(now.minus(Duration.ofMinutes(it)), expected, expected * ratio, 1.0)
    }

    private fun input(r: PvReality?, block: DiagnosticInput.() -> DiagnosticInput = { this }) =
        DiagnosticInput(now, zone, peak, r, link = LinkStatus.ONLINE, freshness = Freshness.LIVE).block()

    @Test
    fun normalWhenWithinUncertainty() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.98)) { copy(recent = recent(0.98)) })
        assertEquals(PvHealthStatus.OK, d.status)
        assertEquals(DiagnosisType.NORMAL, d.primary.type)
        assertTrue(d.primary.recommendation.isNotBlank())
    }

    @Test
    fun sustainedLowOutputIsReportedWithEvidenceAndImpact() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.5)) { copy(recent = recent(0.5)) })
        assertEquals(DiagnosisType.UNEXPECTED_LOW_OUTPUT, d.primary.type)
        assertTrue(d.status == PvHealthStatus.ATTENTION || d.status == PvHealthStatus.PROBLEM)
        assertTrue(d.primary.evidence.isNotEmpty())
        assertTrue(d.primary.impactW!! > 0)
        // A single low reading is not enough.
        val once = PvDiagnosticEngine.diagnose(input(reality(expected * 0.5)) { copy(recent = recent(0.5, minutes = 5)) })
        assertFalse(once.all.any { it.type == DiagnosisType.UNEXPECTED_LOW_OUTPUT })
    }

    @Test
    fun highOutputIsInformationalOnly() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 1.5)) { copy(recent = recent(1.5)) })
        val high = d.all.single { it.type == DiagnosisType.UNEXPECTED_HIGH_OUTPUT }
        assertEquals(DiagnosisSeverity.INFO, high.severity)
    }

    @Test
    fun communicationTimeoutSuppressesOutputAlarms() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.4)) {
            copy(link = LinkStatus.OFFLINE, freshness = Freshness.LAST_KNOWN, linkFailures = 5, recent = recent(0.4))
        })
        assertEquals(DiagnosisType.COMMUNICATION_PROBLEM, d.primary.type)
        assertFalse(d.all.any { it.type == DiagnosisType.UNEXPECTED_LOW_OUTPUT })
    }

    @Test
    fun missingInverterDataGivesUnknownStatus() {
        val d = PvDiagnosticEngine.diagnose(DiagnosticInput(now, zone, peak, reality(null)))
        assertEquals(PvHealthStatus.UNKNOWN, d.status)
        val noReality = PvDiagnosticEngine.diagnose(DiagnosticInput(now, zone, peak, null))
        assertEquals(PvHealthStatus.UNKNOWN, noReality.status)
    }

    @Test
    fun batteryFullCurtailmentIsNotAFault() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.4)) {
            copy(recent = recent(0.4), flow = FlowContext(99.0, 100.0, 400.0, 20.0, canExport = false))
        })
        assertTrue(d.all.any { it.type == DiagnosisType.CURTAILMENT_BATTERY_FULL })
        assertFalse(d.all.any { it.type == DiagnosisType.UNEXPECTED_LOW_OUTPUT })
        assertEquals(PvHealthStatus.OK, d.status)
        // Grid-tied: a full battery does not explain low output.
        val grid = PvDiagnosticEngine.diagnose(input(reality(expected * 0.4)) {
            copy(recent = recent(0.4), flow = FlowContext(99.0, 100.0, 400.0, 20.0, canExport = true))
        })
        assertTrue(grid.all.any { it.type == DiagnosisType.UNEXPECTED_LOW_OUTPUT })
    }

    @Test
    fun emptyBatteryDoesNotTriggerCurtailment() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.4)) {
            copy(recent = recent(0.4), flow = FlowContext(12.0, 100.0, 400.0, 900.0, canExport = false))
        })
        assertFalse(d.all.any { it.type == DiagnosisType.CURTAILMENT_BATTERY_FULL })
    }

    @Test
    fun snowIsConfirmedByNearZeroOutput() {
        val snowy = PvConditions(Irradiance(850.0, 110.0), -4.0, 1.0, snowDepthM = 0.15)
        val r = reality(30.0, conditions = snowy)
        val d = PvDiagnosticEngine.diagnose(input(r) { copy(snowExpected = true, recent = recent(0.02)) })
        val snow = d.all.single { it.type == DiagnosisType.SNOW_SUSPECTED }
        assertEquals(DiagnosisSeverity.WARNING, snow.severity)
        assertFalse(d.all.any { it.type == DiagnosisType.UNEXPECTED_LOW_OUTPUT })
    }

    @Test
    fun heatProducesTemperatureInformation() {
        val hot = reality(null, conditions = PvConditions(Irradiance(900.0, 100.0), 44.0, 0.0))
        val d = PvDiagnosticEngine.diagnose(input(hot))
        assertTrue(d.all.any { it.type == DiagnosisType.TEMPERATURE_LOSS && it.severity == DiagnosisSeverity.INFO })
    }

    @Test
    fun rejectedReadingsBecomeSensorAnomaly() {
        val d = PvDiagnosticEngine.diagnose(input(reality(expected)) {
            copy(validationIssues = listOf(TelemetryIssue(IssueType.INCONSISTENT, TelemetryField.PV_POWER, "P ≠ V×I")))
        })
        assertTrue(d.all.any { it.type == DiagnosisType.SENSOR_ANOMALY })
        assertEquals(PvHealthStatus.ATTENTION, d.status)
    }

    @Test
    fun simulatedDataIsMarkedAndLessConfident() {
        val real = PvDiagnosticEngine.diagnose(input(reality(expected)) { copy(recent = recent(1.0)) })
        val sim = PvDiagnosticEngine.diagnose(input(reality(expected, DataKind.SIMULATED)) { copy(simulated = true, recent = recent(1.0)) })
        assertTrue(sim.confidence < real.confidence)
        assertTrue(sim.primary.evidence.any { it.contains("symulator") })
    }

    @Test
    fun repeatedMorningDeficitOnClearDaysPointsToShading() {
        val samples = (1..3).flatMap { day ->
            (8..16).map { h ->
                val t = LocalDate.of(2026, 6, 20 - day).atTime(h, 0).atZone(zone).toInstant()
                RealitySample(t, 1500.0, if (h in 9..10) 900.0 else 1490.0, 0.95)
            }
        }
        val d = PvDiagnosticEngine.diagnose(input(reality(expected)) { copy(recent = samples) })
        val shade = d.all.single { it.type == DiagnosisType.SHADING_SUSPECTED }
        assertTrue(shade.evidence.first().contains("09:00"))
        // Cloudy days do not create a pattern.
        val cloudy = samples.map { it.copy(clearSkyIndex = 0.4) }
        assertNull(PvDiagnosticEngine.shadingPattern(input(reality(expected)) { copy(recent = cloudy) }))
    }

    @Test
    fun soilingDegradationAndMpptFeedTheDoctor() {
        val soiling = SoilingAssessment(SoilingState.CONFIRMED, 9.2, 0.1, 0.84, listOf("x"), null, 12)
        val degradation = DegradationAssessment(1.2, 0.7, DegradationTrend.ELEVATED, 10, 30, emptyList(), listOf("yoy"))
        val mppt = MpptAnalyzer.analyze(
            listOf(MpptReading(1, 250.0, 3.6, 901.0), MpptReading(2, 240.0, 2.9, 711.0)),
            listOf(MpptConfig(1, 1070.0, 4), MpptConfig(2, 1070.0, 4)), mapOf(1 to 920.0, 2 to 910.0),
        )
        val d = PvDiagnosticEngine.diagnose(input(reality(expected * 0.9)) { copy(soiling = soiling, degradation = degradation, mppt = mppt, recent = recent(0.9)) })
        val types = d.all.map { it.type }
        assertTrue(DiagnosisType.SOILING_SUSPECTED in types)
        assertTrue(DiagnosisType.PV_DEGRADATION in types)
        assertTrue(DiagnosisType.MPPT_ANOMALY in types)
        assertEquals(DiagnosisSeverity.WARNING, d.primary.severity)
    }

    @Test
    fun conversionEfficiencyNeedsPvAsOnlySource() {
        fun t(pv: Double, load: Double, batt: Double, grid: Double?) = InverterTelemetry(Instant.EPOCH, "x",
            pv = PvReading(powerW = pv), load = LoadReading(powerW = load), battery = BatteryReading(powerW = batt), grid = GridReading(powerW = grid))
        assertEquals(0.9, ConversionEfficiency.estimate(t(1000.0, 500.0, 400.0, null))!!, 1e-9)
        assertNull(ConversionEfficiency.estimate(t(1000.0, 500.0, -300.0, null))) // battery discharging
        assertNull(ConversionEfficiency.estimate(t(1000.0, 500.0, 0.0, 200.0))) // grid import
        assertNull(ConversionEfficiency.estimate(t(100.0, 50.0, 0.0, null))) // too little PV
        val low = (1..12).map { t(1000.0, 500.0, 200.0, null) }
        val (median, n) = ConversionEfficiency.median(low)
        val d = PvDiagnosticEngine.diagnose(input(reality(expected)) { copy(conversionEfficiency = median, conversionSamples = n) })
        assertTrue(d.all.any { it.type == DiagnosisType.INVERTER_EFFICIENCY_ANOMALY })
    }
}

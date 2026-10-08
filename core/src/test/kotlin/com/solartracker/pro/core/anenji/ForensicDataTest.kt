package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.export.RegisterLogExport
import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.inverter.ManualReference
import com.solartracker.pro.core.inverter.ReferenceVerdict
import com.solartracker.pro.core.inverter.RegisterDiagnostics
import com.solartracker.pro.core.inverter.RegisterLogRecord
import com.solartracker.pro.core.inverter.RegisterQuality
import com.solartracker.pro.core.inverter.SmgRegisterMap
import com.solartracker.pro.core.inverter.SmgRegisters
import com.solartracker.pro.core.inverter.ValidationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ForensicDataTest {
    private val zone = ZoneId.of("UTC")
    private val t = Instant.parse("2026-10-07T10:00:00Z")

    @Test
    fun importerKeepsRawLinesFieldsAndRegisters() {
        val csv = "# komentarz\ntimestamp,PV Power (kW),soc\n2026-10-07 10:00:00,1.5,70\n\n2026-10-07 10:05:00,1.6,71\n"
        val log = LogImporter.import(csv, zone, "anenji.csv")
        val second = log.samples[1]
        assertEquals("linia 5", second.raw!!.locator) // original line number, blank/comment lines counted
        assertEquals("anenji.csv", second.raw!!.source)
        assertEquals("1.6", second.raw!!.fields["PV Power (kW)"])
        assertEquals("2026-10-07 10:05:00", second.raw!!.rawTimestamp)
        assertEquals(1600.0, second[Channel.PV_POWER]!!, 1e-9) // normalised layer on top
        val points = TelemetryView.points(second)
        val pv = points.single { it.parameter == Channel.PV_POWER }
        assertEquals("1.6", pv.rawValue)
        assertEquals(TelemetrySource.ANENJI_IMPORTED, pv.source)
        assertEquals(SampleQuality.UNVERIFIED, pv.quality)

        val regs = RegisterLogExport.csv(listOf(RegisterLogRecord(t, true, "OK", RegisterDiagnostics.smgSamples(AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON), t))))
        val r = LogImporter.import(regs, zone, "regs.csv").samples.single().raw!!
        assertEquals(3800, r.registers[SmgRegisterMap.PV_VOLTAGE])
        assertTrue(r.locator.startsWith("linia 2"))
        val json = LogImporter.import("""[{"time":"2026-10-07T10:00:00Z","pv_w":100},{"time":"2026-10-07T10:05:00Z","pv_w":"N/A"},{"time":"2026-10-07T10:10:00Z","pv_w":120}]""", zone)
        assertEquals("rekord JSON 3", json.samples.last().raw!!.locator)
    }

    @Test
    fun registerQualityNeverVerifiedWithoutDeviceEvidence() {
        val spec = SmgRegisters.LIVE.single { it.address == SmgRegisterMap.BATTERY_VOLTAGE }
        assertEquals(RegisterQuality.UNVERIFIED, RegisterDiagnostics.quality(spec, 520).first)
        assertEquals(RegisterQuality.VERIFIED, RegisterDiagnostics.quality(spec, 520, verified = setOf(spec.address)).first)
        assertEquals(RegisterQuality.INVALID, RegisterDiagnostics.quality(spec, 0xFFFF, verified = setOf(spec.address)).first) // evidence never overrides physics
        val s = RegisterDiagnostics.smgSamples(AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON), t).single { it.address == spec.address }
        assertEquals(0.1, s.scale!!, 0.0)
        assertFalse(s.signed!!)
    }

    private fun ref(address: Int, raw: Int, display: Double, unit: String = "V", real: Boolean = true, at: Instant = t): ManualReference {
        val spec = SmgRegisters.LIVE.single { it.address == address }
        val decoded = RegisterDiagnostics.decode(spec, raw)
        return ManualReference(address, raw, decoded, decoded, display, unit, at, real)
    }

    @Test
    fun validationModeVerdictsAndHints() {
        val spec = SmgRegisters.LIVE.single { it.address == SmgRegisterMap.BATTERY_VOLTAGE }
        assertEquals(ReferenceVerdict.MATCH, ValidationMode.compare(spec, ref(spec.address, 527, 52.7))!!.verdict)
        assertEquals(ReferenceVerdict.CLOSE, ValidationMode.compare(spec, ref(spec.address, 527, 50.0))!!.verdict)
        val wrong = ValidationMode.compare(spec, ref(spec.address, 527, 5.27))!!
        assertEquals(ReferenceVerdict.WRONG_DECODING, wrong.verdict)
        assertTrue(wrong.hint!!.contains("10×"))
        assertEquals(ReferenceVerdict.SUSPECT, ValidationMode.compare(spec, ref(spec.address, 527, 30.0))!!.verdict)
        val pv = SmgRegisters.LIVE.single { it.address == SmgRegisterMap.PV_POWER }
        assertEquals(ReferenceVerdict.MATCH, ValidationMode.compare(pv, ref(pv.address, 1500, 1.5, "kW"))!!.verdict) // unit conversion
        assertNull(ValidationMode.compare(pv, ref(pv.address, 1500, 1.5, "°C")))
    }

    @Test
    fun verifiedOnlyWithEnoughRealMatchesAtDifferentValues() {
        val a = SmgRegisterMap.BATTERY_VOLTAGE
        val spec = SmgRegisters.LIVE.single { it.address == a }
        val sim = (0 until 5).map { ref(a, 520 + it * 5, 52.0 + it * 0.5, real = false) }
        assertEquals(RegisterQuality.UNVERIFIED, ValidationMode.evidence(spec, sim).status) // SIMULATED never → VERIFIED
        val same = (0 until 3).map { ref(a, 520, 52.0) }
        assertEquals(RegisterQuality.UNVERIFIED, ValidationMode.evidence(spec, same).status) // one value only
        val good = listOf(ref(a, 520, 52.0), ref(a, 535, 53.5), ref(a, 498, 49.8))
        assertEquals(RegisterQuality.VERIFIED, ValidationMode.evidence(spec, good).status)
        assertEquals(setOf(a), ValidationMode.verifiedAddresses(good))
        val contradicted = good + ref(a, 520, 5.2)
        assertEquals(RegisterQuality.SUSPECTED, ValidationMode.evidence(spec, contradicted).status)
    }

    @Test
    fun timeSeriesCountsMissingAndNeverInterpolates() {
        val base = Instant.parse("2026-10-07T00:00:00Z")
        val s = (0 until 24).filter { it !in 6..8 }.map { AnalyzerSample(base.plus(Duration.ofMinutes(5L * it)), mapOf(Channel.SOC to 50.0 + it), DataOrigin.DEVICE) }
        val b = TimeSeriesEngine.resample(s, Channel.SOC, Resolution.M15, base, base.plus(Duration.ofHours(2)), Duration.ofMinutes(5))
        assertEquals(8, b.size)
        assertEquals(3, b[0].originalSamples)
        assertEquals(1.0, b[0].confidence, 0.0)
        val gap = b[2] // 00:30–00:45 holds samples 6,7,8 → all missing
        assertFalse(gap.hasData)
        assertNull("NO DATA, not interpolated", gap.mean)
        assertEquals(3, gap.missingSamples)
        assertTrue(b.none { it.interpolated })
        assertTrue(b[1].aggregated)
        val day = TimeSeriesEngine.resample(s, Channel.SOC, Resolution.D1, base, base.plus(Duration.ofDays(1)), Duration.ofMinutes(5))
        assertEquals(21, day.single().originalSamples)
        assertEquals(288, day.single().expectedSamples)
        assertNotNull(TimeSeriesEngine.hourlyMedian(s, Channel.SOC, zone)[1])
    }
}

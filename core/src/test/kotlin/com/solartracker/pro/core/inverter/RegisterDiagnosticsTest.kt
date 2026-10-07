package com.solartracker.pro.core.inverter

import com.solartracker.pro.core.export.RegisterLogExport
import com.solartracker.pro.core.fixtures.AnenjiFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterDiagnosticsTest {
    private val t = Instant.parse("2026-10-07T10:00:00Z")

    @Test
    fun plausibleRegistersAreUnverifiedNeverVerified() {
        val samples = RegisterDiagnostics.smgSamples(AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON), t)
        val live = samples.filter { it.address >= SmgRegisterMap.LIVE_START }
        assertEquals(SmgRegisters.LIVE.size, live.size)
        assertTrue(live.all { it.quality == RegisterQuality.UNVERIFIED })
        val pvV = samples.single { it.address == SmgRegisterMap.PV_VOLTAGE }
        assertEquals(380.0, pvV.decoded!!, 1e-9)
        assertEquals(3800, pvV.raw)
        assertEquals(0.0, pvV.expectedMin!!, 0.0)
        assertTrue(samples.any { it.address == SmgRegisterMap.FAULT_CODE })
    }

    @Test
    fun markersAndOutOfRangeAreInvalidAndIssuesMakeSuspected() {
        val blocks = AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON)
        blocks.live[SmgRegisterMap.PV_VOLTAGE - SmgRegisterMap.LIVE_START] = 0xFFFF
        blocks.live[SmgRegisterMap.BATTERY_PERCENT - SmgRegisterMap.LIVE_START] = 150
        val issues = listOf(TelemetryIssue(IssueType.INCONSISTENT, TelemetryField.PV_POWER, "P ≠ V×I"))
        val s = RegisterDiagnostics.smgSamples(blocks, t, issues).associateBy { it.address }
        assertEquals(RegisterQuality.INVALID, s[SmgRegisterMap.PV_VOLTAGE]!!.quality)
        assertNull("marker is not decoded as a value", s[SmgRegisterMap.PV_VOLTAGE]!!.decoded)
        assertEquals(RegisterQuality.INVALID, s[SmgRegisterMap.BATTERY_PERCENT]!!.quality)
        assertEquals(RegisterQuality.SUSPECTED, s[SmgRegisterMap.PV_POWER]!!.quality)
        val signed = SmgRegisters.LIVE.single { it.address == SmgRegisterMap.GRID_POWER }
        assertEquals(-200.0, RegisterDiagnostics.decode(signed, 0x10000 - 200), 0.0)
        assertEquals(RegisterQuality.INVALID, RegisterDiagnostics.quality(signed, 0x8000).first)
        assertEquals(RegisterQuality.VERIFIED, RegisterDiagnostics.quality(signed.copy(validation = RegisterValidation.VERIFIED), 100).first)
    }

    @Test
    fun csvAndJsonExportKeepRawWordsAndCommunicationFailures() {
        val ok = RegisterLogRecord(t, true, "OK", RegisterDiagnostics.smgSamples(AnenjiFixtures.blocks(AnenjiFixtures.CLOUDY_SUMMER), t))
        val failed = RegisterLogRecord(t.plusSeconds(5), false, "Przekroczony czas: brak odpowiedzi", emptyList())
        val csv = RegisterLogExport.csv(listOf(ok, failed)).lines().filter { it.isNotBlank() }
        assertEquals(RegisterLogExport.COLUMNS.joinToString(","), csv.first())
        assertEquals(1 + ok.samples.size + 1, csv.size)
        assertTrue(csv.any { it.contains("0x0DAC") }) // 3500 → PV 350.0 V
        assertTrue(csv.last().contains("false") && csv.last().contains("brak odpowiedzi"))
        assertTrue(csv.all { it.split(",").size >= RegisterLogExport.COLUMNS.size })

        val json = Json.parseToJsonElement(RegisterLogExport.json(listOf(ok, failed), t, "0.13.0", "Anenji SMG")).jsonObject
        assertEquals("solar-tracker-pro-register-log/1", json["format"]!!.jsonPrimitive.content)
        assertEquals("false", json["simulated"]!!.jsonPrimitive.content)
        val records = json["records"]!!.jsonArray
        assertEquals(2, records.size)
        assertEquals(ok.samples.size, records[0].jsonObject["registers"]!!.jsonArray.size)
        assertTrue(json["note"]!!.jsonPrimitive.content.contains("REAL DEVICE VALIDATION REQUIRED"))
    }

    @Test
    fun managerRecordsEveryPollIncludingTimeoutsAndFlagsTheSimulator() = runTest {
        var failing = false
        val manager = InverterConnectionManager(FakeAnenjiProvider(failNext = { failing }), PollSettings(), { t }, StandardTestDispatcher(testScheduler))
        manager.pollOnce()
        val first = manager.registerLog.value!!
        assertTrue(first.communicationOk)
        assertTrue("simulator log is labelled", first.simulated)
        assertTrue("the simulator has no registers", first.samples.isEmpty())
        failing = true
        manager.pollOnce()
        val second = manager.registerLog.value!!
        assertFalse(second.communicationOk)
        assertTrue(second.communication.isNotBlank())
        assertEquals(mapOf<RegisterQuality, Int>(), RegisterDiagnostics.summary(listOf(first, second)))
    }
}

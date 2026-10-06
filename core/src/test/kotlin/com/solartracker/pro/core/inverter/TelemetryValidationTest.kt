package com.solartracker.pro.core.inverter

import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.quality.DataKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

class TelemetryValidationTest {
    private val t0 = Instant.parse("2026-06-21T11:00:00Z")
    private val mapper = SmgModbusMapper("anenji-smg")
    private val validator = TelemetryValidator()
    private fun read(s: AnenjiFixtures.Snapshot, t: Instant = t0) = mapper.map(AnenjiFixtures.blocks(s), t)

    @Test
    fun realisticFixturesPassValidation() {
        listOf(AnenjiFixtures.CLEAR_SUMMER_NOON, AnenjiFixtures.CLOUDY_SUMMER, AnenjiFixtures.WINTER_NOON, AnenjiFixtures.BATTERY_LOW,
            AnenjiFixtures.BATTERY_FULL, AnenjiFixtures.HIGH_LOAD).forEach { s ->
            val v = validator.validate(read(s), null)
            assertTrue("$s: ${v.issues}", v.issues.isEmpty())
            assertTrue(v.values.values.all { it.kind == DataKind.MEASURED && it.validity == Validity.VALID })
            assertEquals("anenji-smg", v.values.getValue(TelemetryField.PV_POWER).source)
        }
        val clear = validator.validate(read(AnenjiFixtures.CLEAR_SUMMER_NOON), null).sanitized
        assertEquals(3914.0, clear.pv.powerW!!, 0.0)
        assertEquals(72.0, clear.battery.socPercent!!, 0.0)
        assertEquals(3288.0, clear.battery.powerW!!, 0.0)
    }

    @Test
    fun garbageIsRejectedAndRemoved() {
        val v = validator.validate(read(AnenjiFixtures.GARBAGE), null)
        assertTrue(TelemetryField.PV_VOLTAGE in v.invalidFields)
        assertTrue(TelemetryField.PV_POWER in v.invalidFields)
        assertTrue(TelemetryField.BATTERY_VOLTAGE in v.invalidFields)
        assertEquals(DataKind.INVALID, v.values.getValue(TelemetryField.PV_POWER).kind)
        assertNull(v.sanitized.pv.powerW)
        assertNull(v.sanitized.battery.voltageV)
        assertEquals(400.0, v.sanitized.load.powerW!!, 0.0)
        assertFalse(v.valid)
    }

    @Test
    fun inconsistencyJumpsZeroPvAndStale() {
        val inconsistent = read(AnenjiFixtures.CLEAR_SUMMER_NOON.copy(pvW = 1200))
        assertTrue(TelemetryField.PV_POWER in validator.validate(inconsistent, null).invalidFields)

        val prev = read(AnenjiFixtures.CLOUDY_SUMMER)
        val jump = read(AnenjiFixtures.CLOUDY_SUMMER.copy(soc = 90), t0.plusSeconds(5))
        val vj = validator.validate(jump, prev)
        assertTrue(vj.issues.any { it.type == IssueType.JUMP && it.field == TelemetryField.BATTERY_SOC })
        assertNull(vj.sanitized.battery.socPercent)
        // A slow, plausible SOC change over 10 minutes is fine.
        assertTrue(validator.validate(read(AnenjiFixtures.CLOUDY_SUMMER.copy(soc = 60), t0.plusSeconds(600)), prev).issues.isEmpty())

        val zero = validator.validate(read(AnenjiFixtures.ZERO_PV_DAY), null, expectedPvW = 2500.0)
        assertTrue(zero.issues.any { it.type == IssueType.ZERO_PV })
        assertTrue(zero.valid) // possible fault, not invalid data
        assertTrue(validator.validate(read(AnenjiFixtures.BATTERY_LOW), null, expectedPvW = 0.0).issues.isEmpty())

        val stale = validator.validate(read(AnenjiFixtures.CLOUDY_SUMMER), null, stale = true)
        assertEquals(DataKind.STALE, stale.values.getValue(TelemetryField.PV_POWER).kind)
        assertTrue(stale.issues.any { it.type == IssueType.STALE })
    }

    @Test
    fun rawRegisterChecksAndCatalogue() {
        val blocks = AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON)
        assertTrue(SmgRegisters.checkRaw(blocks).isEmpty())
        blocks.live[SmgRegisterMap.PV_VOLTAGE - SmgRegisterMap.LIVE_START] = 0xFFFF
        assertEquals(SmgRegisterMap.PV_VOLTAGE, SmgRegisters.checkRaw(blocks).single().spec.address)
        assertTrue(SmgRegisters.LIVE.all { it.validation == RegisterValidation.REAL_DEVICE_VALIDATION_REQUIRED })
        assertTrue(SmgRegisters.LIVE.all { it.address in SmgRegisterMap.LIVE_START until SmgRegisterMap.LIVE_START + SmgRegisterMap.LIVE_COUNT })
        assertEquals(SmgRegisters.LIVE.size, SmgRegisters.LIVE.map { it.address }.toSet().size)
    }

    @Test
    fun errorClassification() {
        assertEquals(LinkErrorKind.PROTOCOL, LinkErrorKind.classify(MalformedFrameException("Błędna suma CRC")))
        assertEquals(LinkErrorKind.DEVICE_REJECTED, LinkErrorKind.classify(MalformedFrameException("Falownik odrzucił polecenie (NAK)")))
        assertEquals(LinkErrorKind.TIMEOUT, LinkErrorKind.classify(TimeoutException("x")))
        assertEquals(LinkErrorKind.TIMEOUT, LinkErrorKind.classify(SocketTimeoutException()))
        assertEquals(LinkErrorKind.DEVICE_REJECTED, LinkErrorKind.classify(ModbusException(3, 2)))
        assertEquals(LinkErrorKind.CONNECTION, LinkErrorKind.classify(IOException("closed")))
    }

    @Test
    fun managerCountsErrorsReconnectsAndSanitizes() = runTest {
        var mode = 0
        var t = t0
        val provider = object : InverterProvider {
            override val id = "anenji-smg"
            override val info = InverterInfo("Anenji", "test", "SMG", "fixture")
            override val capabilities = SmgRegisterMap.CAPABILITIES
            override fun connect() {}
            override fun read(now: Instant): InverterTelemetry = when (mode) {
                1 -> throw MalformedFrameException("Błędna suma CRC")
                2 -> throw TimeoutException("timeout")
                3 -> mapper.map(AnenjiFixtures.blocks(AnenjiFixtures.GARBAGE), now)
                else -> mapper.map(AnenjiFixtures.blocks(AnenjiFixtures.CLOUDY_SUMMER), now)
            }
            override fun disconnect() {}
            override fun close() {}
        }
        val m = InverterConnectionManager(provider, PollSettings(offlineAfterFailures = 2), clock = { t.also { t = t.plusSeconds(5) } },
            io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(m.pollOnce())
        mode = 1; assertFalse(m.pollOnce())
        mode = 2; assertFalse(m.pollOnce())
        assertEquals(LinkStatus.OFFLINE, m.state.value.status)
        assertEquals(1, m.state.value.errorCounts[LinkErrorKind.PROTOCOL])
        assertEquals(1, m.state.value.errorCounts[LinkErrorKind.TIMEOUT])
        mode = 3; assertTrue(m.pollOnce())
        assertEquals(1, m.state.value.reconnects)
        assertNull("invalid PV removed", m.telemetry.value!!.pv.powerW)
        assertTrue(m.validation.value!!.invalidFields.isNotEmpty())
        assertEquals(2L, m.state.value.readsOk)
        assertEquals(2L, m.state.value.readsFailed)
    }
}

package com.solartracker.pro.core.inverter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.time.Instant
import kotlin.concurrent.thread

/** Scripted transport: answers each request with the next response, optionally in chunks. */
class ScriptedTransport(private val responses: MutableList<List<ByteArray>>) : ByteTransport {
    val written = mutableListOf<ByteArray>()
    private var pending = ArrayDeque<ByteArray>()
    var opened = 0
    override val description = "scripted"
    override var isOpen = false
    override fun open() { isOpen = true; opened++ }
    override fun clearInput() {}
    override fun write(data: ByteArray) {
        written += data
        pending = ArrayDeque(if (responses.isEmpty()) emptyList() else responses.removeAt(0))
    }
    override fun read(max: Int, timeoutMs: Int): ByteArray = pending.removeFirstOrNull() ?: ByteArray(0)
    override fun close() { isOpen = false }
}

fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun rtuResponse(slave: Int, registers: IntArray, function: Int = 3): ByteArray {
    val body = byteArrayOf(slave.toByte(), function.toByte(), (registers.size * 2).toByte()) +
        registers.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()
    val crc = ModbusCodec.crc16(body)
    return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
}

class InverterProtocolTest {

    @Test
    fun modbusWriteFunctionsAreRefused() {
        // Read-only safety: write coil/register function codes must never be encoded (RTU or TCP).
        listOf(0x05, 0x06, 0x0F, 0x10, 0x16, 0x17).forEach { fn ->
            expect<IllegalArgumentException>("only read") { ModbusCodec.rtuReadRequest(1, fn, 0, 1) }
            expect<IllegalArgumentException>("only read") { ModbusCodec.tcpReadRequest(1, 1, fn, 0, 1) }
        }
        listOf("PBCV48.0", "PCP00", "MCHGC010", "F50").forEach { cmd ->
            expect<IllegalArgumentException>("query") { Pi30Protocol.command(cmd) }
        }
    }

    @Test
    fun modbusRtuRequestMatchesKnownVector() {
        assertArrayEquals(hex("01 03 00 00 00 0A C5 CD"), ModbusCodec.rtuReadRequest(1, 3, 0, 10))
        val req = ModbusCodec.rtuReadRequest(1, 3, 201, 34)
        assertEquals(8, req.size)
        assertEquals(0, ModbusCodec.crc16(req)) // CRC over frame incl. CRC is 0
    }

    @Test
    fun parsesResponseAndRejectsMalformedFrames() {
        val ok = rtuResponse(1, intArrayOf(0x1234, 0xFFFE))
        assertArrayEquals(intArrayOf(0x1234, 0xFFFE), ModbusCodec.parseRtuReadResponse(ok, 1, 3, 2))
        assertEquals(-2, ModbusCodec.toSigned16(0xFFFE))

        val badCrc = ok.copyOf().also { it[4] = (it[4] + 1).toByte() }
        expect<MalformedFrameException>("CRC") { ModbusCodec.parseRtuReadResponse(badCrc, 1, 3, 2) }
        expect<MalformedFrameException>("innego adresu") { ModbusCodec.parseRtuReadResponse(rtuResponse(2, intArrayOf(1, 2)), 1, 3, 2) }
        expect<MalformedFrameException>("liczba danych") { ModbusCodec.parseRtuReadResponse(rtuResponse(1, intArrayOf(1)), 1, 3, 2) }
        expect<MalformedFrameException>("krótka") { ModbusCodec.parseRtuReadResponse(byteArrayOf(1, 3), 1, 3, 2) }

        val exceptionBody = byteArrayOf(1, 0x83.toByte(), 2)
        val crc = ModbusCodec.crc16(exceptionBody)
        val exception = exceptionBody + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        val e = expect<ModbusException>("adres") { ModbusCodec.parseRtuReadResponse(exception, 1, 3, 2) }
        assertEquals(2, e.exceptionCode)
    }

    @Test
    fun clientReassemblesChunkedFramesAndTimesOut() {
        val frame = rtuResponse(1, IntArray(34) { it })
        val transport = ScriptedTransport(mutableListOf(listOf(frame.copyOfRange(0, 2), frame.copyOfRange(2, 30), frame.copyOfRange(30, frame.size))))
        val client = ModbusClient(transport, ModbusFraming.RTU, timeoutMs = 200)
        assertArrayEquals(IntArray(34) { it }, client.readRegisters(201, 34))
        assertEquals(1, transport.opened)

        var t = 0L
        val silent = ScriptedTransport(mutableListOf(emptyList()))
        val timing = ModbusClient(silent, ModbusFraming.RTU, timeoutMs = 100) { t.also { t += 30 } }
        expect<TimeoutException>("Brak odpowiedzi") { timing.readRegisters(201, 34) }
    }

    @Test
    fun modbusTcpOverRealSocket() {
        val server = ServerSocket(0)
        val worker = thread {
            server.accept().use { s ->
                val req = ByteArray(12)
                var n = 0
                while (n < 12) n += s.getInputStream().read(req, n, 12 - n)
                // Echo transaction id, answer 2 registers.
                val pdu = byteArrayOf(3, 4, 0x01, 0x02, 0x03, 0x04)
                val resp = byteArrayOf(req[0], req[1], 0, 0, 0, (pdu.size + 1).toByte(), req[6]) + pdu
                s.getOutputStream().write(resp)
                s.getOutputStream().flush()
            }
        }
        val transport = TcpTransport("127.0.0.1", server.localPort)
        val client = ModbusClient(transport, ModbusFraming.TCP, slave = 1, timeoutMs = 2000)
        assertArrayEquals(intArrayOf(0x0102, 0x0304), client.readRegisters(100, 2))
        transport.close()
        worker.join(2000)
        server.close()
        assertFalse(transport.isOpen)
    }

    @Test
    fun tcpTransportReportsRefusedConnection() {
        val port = ServerSocket(0).use { it.localPort } // closed again → refused
        expect<java.io.IOException>("") { TcpTransport("127.0.0.1", port, connectTimeoutMs = 1000).open() }
    }

    @Test
    fun smgMapperDecodesRegisters() {
        val live = IntArray(SmgRegisterMap.LIVE_COUNT)
        fun set(reg: Int, v: Int) { live[reg - SmgRegisterMap.LIVE_START] = v and 0xFFFF }
        set(SmgRegisterMap.OPERATING_MODE, 3)
        set(SmgRegisterMap.GRID_VOLTAGE, 2301)
        set(SmgRegisterMap.GRID_FREQUENCY, 4998)
        set(SmgRegisterMap.GRID_POWER, -150)
        set(SmgRegisterMap.OUTPUT_POWER, 1170)
        set(SmgRegisterMap.OUTPUT_VA, 1250)
        set(SmgRegisterMap.BATTERY_VOLTAGE, 572)
        set(SmgRegisterMap.BATTERY_AVERAGE_CURRENT, 292)
        set(SmgRegisterMap.BATTERY_AVERAGE_POWER, 1670)
        set(SmgRegisterMap.PV_VOLTAGE, 3405)
        set(SmgRegisterMap.PV_CURRENT, 83)
        set(SmgRegisterMap.PV_POWER, 2840)
        set(SmgRegisterMap.LOAD_PERCENT, 19)
        set(SmgRegisterMap.INVERTER_TEMPERATURE, 41)
        set(SmgRegisterMap.DCDC_TEMPERATURE, -5)
        set(SmgRegisterMap.BATTERY_PERCENT, 76)
        val status = IntArray(SmgRegisterMap.STATUS_COUNT)
        status[1] = (1 shl 7) // fault bit 7: output overload (low word of u32)
        status[9] = (1 shl 10) // warning bit 10: overload
        val t = SmgModbusMapper("x").map(SmgRawBlocks(status, live), Instant.EPOCH)
        assertEquals(2840.0, t.pv.powerW!!, 0.0)
        assertEquals(340.5, t.pv.voltageV!!, 1e-9)
        assertEquals(57.2, t.battery.voltageV!!, 1e-9)
        assertEquals(76.0, t.battery.socPercent!!, 0.0)
        assertEquals(1670.0, t.battery.chargePowerW!!, 0.0)
        assertEquals(BatteryFlowState.CHARGING, t.battery.state)
        assertEquals(-150.0, t.grid.powerW!!, 0.0)
        assertEquals(150.0, t.grid.exportPowerW!!, 0.0)
        assertEquals(49.98, t.grid.frequencyHz!!, 1e-9)
        assertEquals(1170.0, t.load.powerW!!, 0.0)
        assertEquals(OperatingMode.OFF_GRID, t.inverter.mode)
        assertEquals(-5.0, t.inverter.auxTemperatureC!!, 0.0)
        assertEquals(listOf("Przeciążenie wyjścia"), t.inverter.faults.map { it.description })
        assertEquals(listOf("Przeciążenie"), t.inverter.warnings.map { it.description })
        assertNull("not in this register map", t.pv.energyTodayKwh)
    }

    @Test
    fun smgMapperHidesBatteryWhenNotConnected() {
        val live = IntArray(SmgRegisterMap.LIVE_COUNT)
        live[SmgRegisterMap.BATTERY_VOLTAGE - SmgRegisterMap.LIVE_START] = 520
        live[SmgRegisterMap.BATTERY_PERCENT - SmgRegisterMap.LIVE_START] = 250 // invalid anyway
        val status = IntArray(SmgRegisterMap.STATUS_COUNT)
        status[9] = 1 shl SmgRegisterMap.WARNING_BATTERY_NOT_CONNECTED_BIT
        val t = SmgModbusMapper("x").map(SmgRawBlocks(status, live), Instant.EPOCH)
        assertEquals(false, t.battery.connected)
        assertNull(t.battery.voltageV)
        assertNull(t.battery.socPercent)
        assertEquals(BatteryFlowState.DISCONNECTED, t.battery.state)
    }

    @Test
    fun pi30CrcCommandAndQpigs() {
        assertArrayEquals(byteArrayOf('Q'.code.toByte(), 'P'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(), 0xB7.toByte(), 0xA9.toByte(), 0x0D),
            Pi30Protocol.command("QPIGS"))
        expect<IllegalArgumentException>("query") { Pi30Protocol.command("POP02") } // writes are refused
        val payload = "(230.0 50.0 230.0 50.0 1150 1080 018 380 52.40 012 076 0035 0008 341.2 52.30 00000 00010110 00 00 02728 010"
        val body = payload.toByteArray()
        val crc = Pi30Protocol.crc(body)
        val frame = body + byteArrayOf((crc ushr 8).toByte(), crc.toByte(), 0x0D)
        val q = Pi30Protocol.parseQpigs(Pi30Protocol.payload(frame))
        assertEquals(52.4, q.batteryVoltage, 1e-9)
        assertEquals(76.0, q.batteryPercent, 0.0)
        assertEquals(2728.0, q.pvChargingPowerW!!, 0.0)
        val t = Pi30Mapper("p").map(Pi30Raw(q, 'B'), Instant.EPOCH)
        assertEquals(OperatingMode.OFF_GRID, t.inverter.mode)
        assertEquals(12.0 * 52.4, t.battery.powerW!!, 1e-9)
        assertEquals(1080.0, t.load.powerW!!, 0.0)

        val corrupted = frame.copyOf().also { it[5] = '9'.code.toByte() }
        expect<MalformedFrameException>("CRC") { Pi30Protocol.payload(corrupted) }
        expect<MalformedFrameException>("pól") { Pi30Protocol.parseQpigs("230.0 50.0") }
        expect<MalformedFrameException>("pole") { Pi30Protocol.parseQpigs(payload.drop(1).replace("52.40", "xx")) }
    }

    @Test
    fun configValidationAndRepository() {
        assertTrue(InverterConfig(host = "192.168.1.50").validate().isEmpty())
        assertTrue(InverterConfig(host = "").validate().any { it.contains("adres IP") })
        assertTrue(InverterConfig(host = "h", link = InverterLink.MODBUS_TCP_GATEWAY, protocol = InverterProtocol.PI30).validate().any { it.contains("PI30") })
        assertTrue(InverterConfig(link = InverterLink.USB_SERIAL, slaveId = 0).validate().any { it.contains("Modbus") })
        val repo = InverterRepository()
        assertTrue(repo.createProvider(InverterConfig(link = InverterLink.SIMULATOR)).info.simulated)
        assertTrue(repo.createProvider(InverterConfig(host = "10.0.0.2")) is AnenjiSmgModbusProvider)
        expect<IllegalStateException>("USB") { repo.createProvider(InverterConfig(link = InverterLink.USB_SERIAL)) }
    }

    private inline fun <reified T : Throwable> expect(messagePart: String, block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) {
                assertTrue("${e.message} should contain '$messagePart'", e.message.orEmpty().contains(messagePart))
                return e
            }
            throw e
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }
}

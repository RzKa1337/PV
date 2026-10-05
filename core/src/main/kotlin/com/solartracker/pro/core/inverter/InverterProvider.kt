package com.solartracker.pro.core.inverter

import java.io.IOException
import java.time.Instant

/** Static description of a connected inverter integration. */
data class InverterInfo(
    val manufacturer: String,
    val model: String,
    val protocol: String,
    val interfaceDescription: String,
    /** True only for test/demo providers – their data must never be shown as a real measurement. */
    val simulated: Boolean = false,
)

/**
 * One inverter integration (manufacturer + protocol + transport). Blocking; called from a
 * background thread by [InverterConnectionManager]. New brands (Victron, Deye, Growatt, ...)
 * implement this interface – nothing else in the app depends on a specific brand.
 */
interface InverterProvider : AutoCloseable {
    val id: String
    val info: InverterInfo

    /** Fields the protocol can deliver; others are shown as N/A with an explanation. */
    val capabilities: Set<TelemetryField>

    @Throws(IOException::class)
    fun connect()

    /** Reads a complete snapshot. [now] is the receive time used as the measurement timestamp. */
    @Throws(IOException::class)
    fun read(now: Instant): InverterTelemetry

    fun disconnect()

    override fun close() = disconnect()
}

/** Anenji 6.2 kW over the RS232 port, SMG Modbus register map. */
class AnenjiSmgModbusProvider(
    private val transport: ByteTransport,
    framing: ModbusFraming,
    slave: Int = 1,
    timeoutMs: Int = 1_500,
    override val id: String = "anenji-smg",
) : InverterProvider {
    private val client = ModbusClient(transport, framing, slave, timeoutMs)
    private val mapper = SmgModbusMapper(id)

    override val info = InverterInfo(
        manufacturer = "Anenji",
        model = "6.2 kW 48 V (ANJ-6200W-48V)",
        protocol = "Modbus ${framing.name} – mapa rejestrów SMG",
        interfaceDescription = transport.description,
    )
    override val capabilities: Set<TelemetryField> = SmgRegisterMap.CAPABILITIES

    override fun connect() = transport.open()

    override fun read(now: Instant): InverterTelemetry {
        val live = client.readRegisters(SmgRegisterMap.LIVE_START, SmgRegisterMap.LIVE_COUNT)
        val status = client.readRegisters(SmgRegisterMap.STATUS_START, SmgRegisterMap.STATUS_COUNT)
        return mapper.map(SmgRawBlocks(status, live), now)
    }

    override fun disconnect() = transport.close()
}

/** Inverters of the same family that answer the PI30 ASCII protocol on RS232. */
class Pi30Provider(
    private val transport: ByteTransport,
    private val timeoutMs: Int = 2_000,
    override val id: String = "pi30",
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : InverterProvider {
    private val mapper = Pi30Mapper(id)

    override val info = InverterInfo(
        manufacturer = "Anenji / Voltronic",
        model = "6.2 kW 48 V (wariant PI30)",
        protocol = "PI30 ASCII (QPIGS, QMOD)",
        interfaceDescription = transport.description,
    )
    override val capabilities: Set<TelemetryField> = Pi30Protocol.CAPABILITIES

    override fun connect() = transport.open()

    private fun query(command: String): String {
        if (!transport.isOpen) transport.open()
        transport.clearInput()
        transport.write(Pi30Protocol.command(command))
        val buffer = java.io.ByteArrayOutputStream()
        val deadline = clock() + timeoutMs
        while (true) {
            val left = (deadline - clock()).toInt()
            if (left <= 0) throw TimeoutException("Brak odpowiedzi na $command")
            val chunk = transport.read(256, left)
            buffer.write(chunk)
            if (chunk.isNotEmpty() && chunk.last() == Pi30Protocol.CR) break
            if (buffer.size() > 1024) throw MalformedFrameException("Za długa odpowiedź")
        }
        return Pi30Protocol.payload(buffer.toByteArray())
    }

    override fun read(now: Instant): InverterTelemetry {
        val qpigs = Pi30Protocol.parseQpigs(query("QPIGS"))
        val mode = runCatching { query("QMOD").firstOrNull() }.getOrNull()
        return mapper.map(Pi30Raw(qpigs, mode), now)
    }

    override fun disconnect() = transport.close()
}

package com.solartracker.pro.core.inverter

import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Voltronic "PI30" ASCII protocol (QPIGS/QMOD/QPIWS) used by many hybrid inverters of the same family
 * (some Anenji/PowMr/EASUN variants). Frame: "(" + fields + CRC16-XMODEM (2 bytes) + CR.
 * Whether a given Anenji unit answers PI30 or Modbus must be checked on the real device.
 */
object Pi30Protocol {
    const val CR: Byte = 0x0D

    fun crc(data: ByteArray, length: Int = data.size): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        }
        crc = crc and 0xFFFF
        // Bytes equal to '(' CR LF are avoided by the protocol (incremented).
        var hi = crc ushr 8
        var lo = crc and 0xFF
        if (hi == 0x28 || hi == 0x0D || hi == 0x0A) hi++
        if (lo == 0x28 || lo == 0x0D || lo == 0x0A) lo++
        return (hi shl 8) or lo
    }

    fun command(name: String): ByteArray {
        require(name.matches(Regex("^Q[A-Z0-9]{1,8}$"))) { "only query commands are allowed" }
        val body = name.toByteArray(StandardCharsets.US_ASCII)
        val c = crc(body)
        return body + byteArrayOf((c ushr 8).toByte(), c.toByte(), CR)
    }

    /** Validates a response frame (including CR) and returns the payload after "(". */
    fun payload(frame: ByteArray): String {
        if (frame.size < 4 || frame.last() != CR) throw MalformedFrameException("Brak zakończenia ramki")
        if (frame[0] != '('.code.toByte()) throw MalformedFrameException("Ramka nie zaczyna się od '('")
        val dataEnd = frame.size - 3
        val expected = crc(frame, dataEnd)
        val got = ((frame[dataEnd].toInt() and 0xFF) shl 8) or (frame[dataEnd + 1].toInt() and 0xFF)
        if (expected != got) throw MalformedFrameException("Błędna suma CRC")
        val text = String(frame, 1, dataEnd - 1, StandardCharsets.US_ASCII)
        if (text.startsWith("NAK")) throw MalformedFrameException("Falownik odrzucił polecenie (NAK)")
        return text
    }

    data class Qpigs(
        val gridVoltage: Double, val gridFrequency: Double, val outputVoltage: Double, val outputFrequency: Double,
        val outputVa: Double, val outputW: Double, val loadPercent: Double, val batteryVoltage: Double,
        val batteryChargeA: Double, val batteryPercent: Double, val heatSinkC: Double, val pvCurrentA: Double,
        val pvVoltage: Double, val batteryDischargeA: Double, val statusBits: String, val pvChargingPowerW: Double?,
    )

    fun parseQpigs(payload: String): Qpigs {
        val f = payload.trim().split(Regex("\\s+"))
        if (f.size < 17) throw MalformedFrameException("QPIGS: za mało pól (${f.size})")
        fun d(i: Int): Double = f[i].toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw MalformedFrameException("QPIGS: nieprawidłowe pole ${i + 1}: '${f[i]}'")
        return Qpigs(
            gridVoltage = d(0), gridFrequency = d(1), outputVoltage = d(2), outputFrequency = d(3),
            outputVa = d(4), outputW = d(5), loadPercent = d(6), batteryVoltage = d(8),
            batteryChargeA = d(9), batteryPercent = d(10), heatSinkC = d(11), pvCurrentA = d(12),
            pvVoltage = d(13), batteryDischargeA = d(15), statusBits = f[16],
            pvChargingPowerW = f.getOrNull(19)?.toDoubleOrNull(),
        )
    }

    val MODES = mapOf(
        'P' to OperatingMode.POWER_ON, 'S' to OperatingMode.STANDBY, 'L' to OperatingMode.GRID,
        'B' to OperatingMode.OFF_GRID, 'F' to OperatingMode.FAULT, 'H' to OperatingMode.STANDBY, 'D' to OperatingMode.STANDBY,
    )

    val CAPABILITIES: Set<TelemetryField> = setOf(
        TelemetryField.PV_VOLTAGE, TelemetryField.PV_CURRENT, TelemetryField.PV_POWER, TelemetryField.PV_CHARGING_POWER,
        TelemetryField.BATTERY_VOLTAGE, TelemetryField.BATTERY_CURRENT, TelemetryField.BATTERY_POWER, TelemetryField.BATTERY_SOC,
        TelemetryField.GRID_VOLTAGE, TelemetryField.GRID_FREQUENCY,
        TelemetryField.LOAD_POWER, TelemetryField.LOAD_APPARENT_POWER, TelemetryField.LOAD_PERCENT,
        TelemetryField.OUTPUT_VOLTAGE, TelemetryField.OUTPUT_FREQUENCY, TelemetryField.INVERTER_TEMPERATURE,
        TelemetryField.OPERATING_MODE,
    )
}

/** Raw PI30 answers. */
class Pi30Raw(val qpigs: Pi30Protocol.Qpigs, val mode: Char?)

class Pi30Mapper(private val providerId: String) : InverterDataMapper<Pi30Raw> {
    override fun map(raw: Pi30Raw, timestamp: Instant): InverterTelemetry {
        val q = raw.qpigs
        // PV power: QPIGS has no direct PV power in older firmware; V × A is CALCULATED by us.
        val pvPower = q.pvChargingPowerW ?: (q.pvVoltage * q.pvCurrentA)
        val batteryCurrent = q.batteryChargeA - q.batteryDischargeA
        return InverterTelemetry(
            timestamp = timestamp,
            providerId = providerId,
            pv = PvReading(voltageV = q.pvVoltage, currentA = q.pvCurrentA, powerW = pvPower, chargingPowerW = q.pvChargingPowerW),
            battery = BatteryReading(
                voltageV = q.batteryVoltage.takeIf { it > 0 },
                currentA = batteryCurrent,
                powerW = batteryCurrent * q.batteryVoltage,
                socPercent = q.batteryPercent.takeIf { it in 0.0..100.0 },
                connected = q.batteryVoltage > 0,
            ),
            grid = GridReading(voltageV = q.gridVoltage, frequencyHz = q.gridFrequency),
            load = LoadReading(powerW = q.outputW, apparentPowerVa = q.outputVa, percent = q.loadPercent),
            inverter = InverterStatusReading(
                outputVoltageV = q.outputVoltage,
                outputFrequencyHz = q.outputFrequency,
                temperatureC = q.heatSinkC,
                mode = raw.mode?.let { Pi30Protocol.MODES[it] } ?: OperatingMode.UNKNOWN,
                rawMode = raw.mode?.toString(),
            ),
        )
    }
}

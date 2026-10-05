package com.solartracker.pro.core.inverter

import java.io.IOException

/** Modbus error reported by the device (exception response). */
class ModbusException(val function: Int, val exceptionCode: Int) :
    IOException("Modbus: urządzenie zwróciło wyjątek $exceptionCode (${describe(exceptionCode)}) dla funkcji $function") {
    companion object {
        fun describe(code: Int) = when (code) {
            1 -> "niedozwolona funkcja"
            2 -> "niedozwolony adres rejestru"
            3 -> "niedozwolona wartość"
            4 -> "błąd urządzenia"
            6 -> "urządzenie zajęte"
            else -> "kod $code"
        }
    }
}

/** A frame that cannot be valid (bad CRC, wrong length, wrong slave/function). */
class MalformedFrameException(message: String) : IOException(message)

class TimeoutException(message: String) : IOException(message)

enum class ModbusFraming { RTU, TCP }

object ModbusCodec {
    const val READ_HOLDING = 0x03
    const val READ_INPUT = 0x04
    const val MAX_REGISTERS = 125

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    private fun pdu(function: Int, start: Int, count: Int): ByteArray {
        require(function == READ_HOLDING || function == READ_INPUT) { "only read functions are supported" }
        require(start in 0..0xFFFF) { "start out of range" }
        require(count in 1..MAX_REGISTERS) { "count out of range" }
        return byteArrayOf(
            function.toByte(),
            (start ushr 8).toByte(), start.toByte(),
            (count ushr 8).toByte(), count.toByte(),
        )
    }

    fun rtuReadRequest(slave: Int, function: Int, start: Int, count: Int): ByteArray {
        require(slave in 1..247) { "slave out of range" }
        val body = byteArrayOf(slave.toByte()) + pdu(function, start, count)
        val crc = crc16(body)
        return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    fun tcpReadRequest(transactionId: Int, unitId: Int, function: Int, start: Int, count: Int): ByteArray {
        val p = pdu(function, start, count)
        val len = p.size + 1
        return byteArrayOf(
            (transactionId ushr 8).toByte(), transactionId.toByte(),
            0, 0,
            (len ushr 8).toByte(), len.toByte(),
            unitId.toByte(),
        ) + p
    }

    /**
     * How many bytes a complete RTU response has, judged from the bytes received so far;
     * null when not enough bytes to know yet.
     */
    fun rtuExpectedLength(buffer: ByteArray, length: Int): Int? {
        if (length < 3) return null
        val fn = buffer[1].toInt() and 0xFF
        return if (fn and 0x80 != 0) 5 else 3 + (buffer[2].toInt() and 0xFF) + 2
    }

    /** Parses an RTU read response; returns unsigned 16-bit register values. */
    fun parseRtuReadResponse(frame: ByteArray, slave: Int, function: Int, count: Int): IntArray {
        if (frame.size < 5) throw MalformedFrameException("Za krótka ramka (${frame.size} B)")
        val crc = crc16(frame, frame.size - 2)
        val got = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        if (crc != got) throw MalformedFrameException("Błędna suma CRC")
        if ((frame[0].toInt() and 0xFF) != slave) throw MalformedFrameException("Odpowiedź od innego adresu ${frame[0].toInt() and 0xFF}")
        return parsePdu(frame.copyOfRange(1, frame.size - 2), function, count)
    }

    fun parseTcpReadResponse(frame: ByteArray, transactionId: Int, unitId: Int, function: Int, count: Int): IntArray {
        if (frame.size < 9) throw MalformedFrameException("Za krótka ramka (${frame.size} B)")
        val tid = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (tid != transactionId) throw MalformedFrameException("Nieoczekiwany identyfikator transakcji $tid")
        if (frame[2].toInt() != 0 || frame[3].toInt() != 0) throw MalformedFrameException("To nie jest protokół Modbus")
        val len = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        if (len != frame.size - 6) throw MalformedFrameException("Nieprawidłowa długość ramki")
        if ((frame[6].toInt() and 0xFF) != unitId) throw MalformedFrameException("Odpowiedź od innej jednostki")
        return parsePdu(frame.copyOfRange(7, frame.size), function, count)
    }

    private fun parsePdu(pdu: ByteArray, function: Int, count: Int): IntArray {
        val fn = pdu[0].toInt() and 0xFF
        if (fn == (function or 0x80)) throw ModbusException(function, pdu.getOrNull(1)?.toInt()?.and(0xFF) ?: -1)
        if (fn != function) throw MalformedFrameException("Nieoczekiwana funkcja $fn")
        val bytes = pdu.getOrNull(1)?.toInt()?.and(0xFF) ?: throw MalformedFrameException("Brak liczby bajtów")
        if (bytes != count * 2 || pdu.size != 2 + bytes) throw MalformedFrameException("Nieprawidłowa liczba danych ($bytes B, oczekiwano ${count * 2})")
        return IntArray(count) { i -> ((pdu[2 + 2 * i].toInt() and 0xFF) shl 8) or (pdu[3 + 2 * i].toInt() and 0xFF) }
    }

    fun toSigned16(value: Int): Int = if (value >= 0x8000) value - 0x10000 else value
}

/** Blocking Modbus master for read requests over a [ByteTransport]. Not thread-safe. */
class ModbusClient(
    private val transport: ByteTransport,
    private val framing: ModbusFraming,
    private val slave: Int = 1,
    private val timeoutMs: Int = 1_500,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var transactionId = 0

    fun readRegisters(start: Int, count: Int, function: Int = ModbusCodec.READ_HOLDING): IntArray {
        if (!transport.isOpen) transport.open()
        transport.clearInput()
        return when (framing) {
            ModbusFraming.RTU -> {
                transport.write(ModbusCodec.rtuReadRequest(slave, function, start, count))
                val frame = readFrame { buf, len -> ModbusCodec.rtuExpectedLength(buf, len) }
                ModbusCodec.parseRtuReadResponse(frame, slave, function, count)
            }
            ModbusFraming.TCP -> {
                transactionId = (transactionId + 1) and 0xFFFF
                transport.write(ModbusCodec.tcpReadRequest(transactionId, slave, function, start, count))
                val frame = readFrame { buf, len ->
                    if (len < 6) null else 6 + (((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF))
                }
                ModbusCodec.parseTcpReadResponse(frame, transactionId, slave, function, count)
            }
        }
    }

    private fun readFrame(expected: (ByteArray, Int) -> Int?): ByteArray {
        val buffer = ByteArray(512)
        var len = 0
        val deadline = clock() + timeoutMs
        while (true) {
            val need = expected(buffer, len)
            if (need != null) {
                if (need > buffer.size) throw MalformedFrameException("Za długa ramka ($need B)")
                if (len >= need) return buffer.copyOf(need)
            }
            val left = (deadline - clock()).toInt()
            if (left <= 0) throw TimeoutException("Brak odpowiedzi falownika w ${timeoutMs} ms (odebrano $len B)")
            val chunk = transport.read(buffer.size - len, left)
            chunk.copyInto(buffer, len)
            len += chunk.size
        }
    }
}

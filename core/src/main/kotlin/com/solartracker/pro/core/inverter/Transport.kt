package com.solartracker.pro.core.inverter

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Byte channel to the inverter (TCP bridge, USB serial adapter, ...). Implementations are blocking. */
interface ByteTransport : AutoCloseable {
    val description: String
    val isOpen: Boolean

    @Throws(IOException::class)
    fun open()

    /** Discards bytes left over from a previous (e.g. timed-out) exchange. */
    fun clearInput()

    @Throws(IOException::class)
    fun write(data: ByteArray)

    /**
     * Reads up to [max] bytes, waiting at most [timeoutMs]. Returns an empty array on timeout.
     * @throws IOException when the connection is broken
     */
    @Throws(IOException::class)
    fun read(max: Int, timeoutMs: Int): ByteArray

    override fun close()
}

/**
 * TCP client transport: a transparent RS232/RS485 ↔ Wi-Fi/Ethernet bridge (Modbus RTU over TCP)
 * or a Modbus TCP gateway. Only an outgoing connection is made; no port is opened on the phone.
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 5_000,
    private val socketFactory: () -> Socket = { Socket() },
) : ByteTransport {
    init {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "port out of range" }
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val description: String get() = "TCP $host:$port"
    override val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    override fun open() {
        if (isOpen) return
        val s = socketFactory()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun clearInput() {
        val s = socket ?: return
        val inp = input ?: return
        runCatching {
            s.soTimeout = 1
            val buf = ByteArray(256)
            while (inp.available() > 0) if (inp.read(buf) < 0) break
        }
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw IOException("Nie połączono")
        out.write(data)
        out.flush()
    }

    override fun read(max: Int, timeoutMs: Int): ByteArray {
        val s = socket ?: throw IOException("Nie połączono")
        val inp = input ?: throw IOException("Nie połączono")
        s.soTimeout = timeoutMs.coerceAtLeast(1)
        val buf = ByteArray(max)
        return try {
            val n = inp.read(buf)
            if (n < 0) throw IOException("Połączenie zamknięte przez urządzenie")
            buf.copyOf(n)
        } catch (_: SocketTimeoutException) {
            ByteArray(0)
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}

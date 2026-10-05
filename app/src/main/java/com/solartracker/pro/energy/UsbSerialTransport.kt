package com.solartracker.pro.energy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.solartracker.pro.core.inverter.ByteTransport
import java.io.IOException

/**
 * RS232 → USB adapter (FTDI, CH340, CP210x, PL2303, CDC) on the phone's USB-OTG port.
 * Default line settings of the Anenji RS232 port: 9600 baud, 8N1.
 */
class UsbSerialTransport(
    context: Context,
    private val baudRate: Int = 9600,
) : ByteTransport {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    override var description: String = "USB (RS232 $baudRate 8N1)"
        private set
    override val isOpen: Boolean get() = port?.isOpen == true

    override fun open() {
        if (isOpen) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: throw IOException("Nie znaleziono adaptera USB-RS232 – podłącz kabel przez OTG")
        if (!manager.hasPermission(driver.device)) {
            // The result is not read (the next poll simply retries), so an immutable intent is enough.
            val intent = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(driver.device, intent)
            throw IOException("Zezwól aplikacji na dostęp do adaptera USB (okno systemu), potem spróbuj ponownie")
        }
        val connection = manager.openDevice(driver.device) ?: throw IOException("Nie można otworzyć urządzenia USB")
        val p = driver.ports.firstOrNull() ?: throw IOException("Adapter USB nie ma portu szeregowego")
        try {
            p.open(connection)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            runCatching { p.close() }
            throw IOException("Błąd otwarcia portu USB: ${e.message}", e)
        }
        port = p
        description = "USB ${driver.javaClass.simpleName.removeSuffix("SerialDriver")} ($baudRate 8N1)"
    }

    override fun clearInput() {
        val p = port ?: return
        runCatching { p.purgeHwBuffers(true, false) }
        val buf = ByteArray(256)
        repeat(4) { if (runCatching { p.read(buf, 5) }.getOrDefault(0) <= 0) return }
    }

    override fun write(data: ByteArray) {
        val p = port ?: throw IOException("Port USB zamknięty")
        p.write(data, 1000)
    }

    override fun read(max: Int, timeoutMs: Int): ByteArray {
        val p = port ?: throw IOException("Port USB zamknięty")
        val buf = ByteArray(max.coerceAtMost(4096))
        val n = p.read(buf, timeoutMs.coerceAtLeast(1))
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    override fun close() {
        runCatching { port?.close() }
        port = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.solartracker.pro.USB_PERMISSION"
    }
}

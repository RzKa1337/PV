package com.solartracker.pro.core.inverter

import java.time.Duration

enum class InverterBrand(val label: String) { ANENJI("Anenji") }

enum class InverterProtocol(val label: String) {
    MODBUS_SMG("Modbus (mapa SMG)"),
    PI30("PI30 ASCII"),
}

enum class InverterLink(val label: String) {
    /** Transparent RS232 ↔ Wi-Fi/Ethernet converter, raw serial bytes over TCP. */
    TCP_SERIAL_BRIDGE("Most RS232 → Wi-Fi/LAN (TCP)"),
    /** Modbus TCP gateway (MBAP framing). */
    MODBUS_TCP_GATEWAY("Bramka Modbus TCP"),
    /** RS232 → USB adapter on the phone (USB OTG). */
    USB_SERIAL("Kabel RS232 → USB (OTG)"),
    /** Simulated inverter for demos/tests – clearly labelled, never a real measurement. */
    SIMULATOR("Symulator (dane testowe)"),
}

/** User configuration of the inverter connection (no passwords needed by these interfaces). */
data class InverterConfig(
    val enabled: Boolean = false,
    val brand: InverterBrand = InverterBrand.ANENJI,
    val model: String = "ANJ-6200W-48V",
    val ratedPowerW: Double = 6200.0,
    val mpptCount: Int = 1,
    val protocol: InverterProtocol = InverterProtocol.MODBUS_SMG,
    val link: InverterLink = InverterLink.TCP_SERIAL_BRIDGE,
    val host: String = "",
    val port: Int = 8899,
    val slaveId: Int = 1,
    val pollIntervalSeconds: Int = 5,
) {
    fun validate(): List<String> = buildList {
        if (ratedPowerW !in 100.0..100_000.0) add("Moc falownika poza zakresem")
        if (mpptCount !in 1..8) add("Liczba MPPT 1–8")
        if (slaveId !in 1..247) add("Adres Modbus 1–247")
        if (pollIntervalSeconds !in 1..300) add("Odczyt co 1–300 s")
        if (link == InverterLink.TCP_SERIAL_BRIDGE || link == InverterLink.MODBUS_TCP_GATEWAY) {
            if (!HOST.matches(host)) add("Podaj adres IP lub nazwę hosta mostka")
            if (port !in 1..65535) add("Port 1–65535")
        }
        if (link == InverterLink.MODBUS_TCP_GATEWAY && protocol == InverterProtocol.PI30) {
            add("PI30 nie działa przez bramkę Modbus TCP – wybierz most TCP")
        }
    }

    val pollSettings: PollSettings
        get() = Duration.ofSeconds(pollIntervalSeconds.toLong()).let {
            PollSettings(interval = it, staleAfter = it.multipliedBy(3).coerceAtLeast(Duration.ofSeconds(10)))
        }

    companion object {
        private val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")
    }
}

/**
 * Creates the provider for a configuration. Platform transports (USB) are injected by the app;
 * protocols are never hard-coded in the UI.
 */
class InverterRepository(
    private val usbTransportFactory: (() -> ByteTransport)? = null,
    private val simulatorFactory: () -> InverterProvider = { FakeAnenjiProvider() },
) {
    fun createProvider(config: InverterConfig): InverterProvider {
        val errors = config.validate()
        require(errors.isEmpty()) { errors.joinToString() }
        if (config.link == InverterLink.SIMULATOR) return simulatorFactory()
        val transport: ByteTransport = when (config.link) {
            InverterLink.TCP_SERIAL_BRIDGE, InverterLink.MODBUS_TCP_GATEWAY -> TcpTransport(config.host, config.port)
            InverterLink.USB_SERIAL -> usbTransportFactory?.invoke()
                ?: throw IllegalStateException("USB nie jest dostępne na tym urządzeniu")
            InverterLink.SIMULATOR -> error("unreachable")
        }
        return when (config.protocol) {
            InverterProtocol.MODBUS_SMG -> AnenjiSmgModbusProvider(
                transport,
                if (config.link == InverterLink.MODBUS_TCP_GATEWAY) ModbusFraming.TCP else ModbusFraming.RTU,
                config.slaveId,
            )
            InverterProtocol.PI30 -> Pi30Provider(transport)
        }
    }
}

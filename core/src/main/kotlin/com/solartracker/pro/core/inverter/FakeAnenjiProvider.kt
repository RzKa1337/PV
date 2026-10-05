package com.solartracker.pro.core.inverter

import java.io.IOException
import java.time.Instant

/**
 * Simulated Anenji inverter for tests and demo mode. It is labelled [InverterInfo.simulated] so its
 * values are never presented as real measurements. Values follow a smooth, physically consistent
 * profile (PV = load + battery − grid) generated from the time of day.
 */
class FakeAnenjiProvider(
    private val script: (Instant) -> InverterTelemetry = ::defaultReading,
    /** Return true to make the next read fail (timeout simulation). */
    var failNext: (Instant) -> Boolean = { false },
) : InverterProvider {
    override val id = "fake-anenji"
    override val info = InverterInfo("Anenji", "6.2 kW 48 V (symulator)", "symulacja", "brak – dane testowe", simulated = true)
    override val capabilities: Set<TelemetryField> = SmgRegisterMap.CAPABILITIES

    var connected = false
        private set
    var connectCount = 0
        private set

    override fun connect() {
        connected = true
        connectCount++
    }

    override fun read(now: Instant): InverterTelemetry {
        if (!connected) throw IOException("Nie połączono")
        if (failNext(now)) throw TimeoutException("Symulowany brak odpowiedzi")
        return script(now)
    }

    override fun disconnect() {
        connected = false
    }

    companion object {
        fun defaultReading(now: Instant): InverterTelemetry {
            val hour = now.atZone(java.time.ZoneOffset.UTC).let { it.hour + it.minute / 60.0 }
            val pv = (kotlin.math.sin((hour - 6.0) / 12.0 * Math.PI) * 3000.0).coerceAtLeast(0.0)
            val load = 600.0
            val battery = (pv - load).coerceIn(-2000.0, 2000.0)
            val grid = load + battery - pv
            return InverterTelemetry(
                timestamp = now,
                providerId = "fake-anenji",
                pv = PvReading(voltageV = if (pv > 0) 320.0 else 0.0, currentA = pv / 320.0, powerW = pv),
                battery = BatteryReading(voltageV = 52.0, currentA = battery / 52.0, powerW = battery, socPercent = 60.0, connected = true),
                grid = GridReading(voltageV = 230.0, powerW = grid, frequencyHz = 50.0),
                load = LoadReading(powerW = load, percent = load / 6200.0 * 100.0),
                inverter = InverterStatusReading(temperatureC = 35.0, mode = OperatingMode.OFF_GRID, outputVoltageV = 230.0, outputFrequencyHz = 50.0),
            )
        }
    }
}

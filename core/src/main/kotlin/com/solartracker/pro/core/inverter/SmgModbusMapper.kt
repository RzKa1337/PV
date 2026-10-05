package com.solartracker.pro.core.inverter

import java.time.Instant

/**
 * Register map of the "SMG" Modbus family used by Anenji 6.2 kW hybrid inverters
 * (ANJ-6200W-48V and rebrands) on the RS232 port: Modbus RTU, 9600 8N1, slave 1, function 03.
 *
 * Source: community documentation (open Home Assistant integrations) – not an official vendor
 * document; **REAL DEVICE VALIDATION REQUIRED**. Sign conventions follow that documentation:
 * grid power + = import, battery power + = charging.
 */
object SmgRegisterMap {
    const val STATUS_START = 100
    const val STATUS_COUNT = 10
    const val LIVE_START = 201
    const val LIVE_COUNT = 34

    // status block (absolute addresses)
    const val FAULT_CODE = 100 // u32, high word first
    const val WARNING_CODE = 108 // u32, high word first

    // live block
    const val OPERATING_MODE = 201
    const val GRID_VOLTAGE = 202 // /10 V
    const val GRID_FREQUENCY = 203 // /100 Hz
    const val GRID_POWER = 204 // signed W
    const val INVERTER_VOLTAGE = 205 // /10 V
    const val INVERTER_CURRENT = 206 // /10 A
    const val INVERTER_FREQUENCY = 207 // /100 Hz
    const val INVERTER_POWER = 208 // signed W
    const val INVERTER_CHARGING_POWER = 209 // signed W
    const val OUTPUT_VOLTAGE = 210 // /10 V
    const val OUTPUT_CURRENT = 211 // /10 A
    const val OUTPUT_FREQUENCY = 212 // /100 Hz
    const val OUTPUT_POWER = 213 // signed W (load)
    const val OUTPUT_VA = 214 // VA
    const val BATTERY_VOLTAGE = 215 // /10 V
    const val BATTERY_AVERAGE_CURRENT = 216 // signed /10 A
    const val BATTERY_AVERAGE_POWER = 217 // signed W
    const val PV_VOLTAGE = 219 // /10 V
    const val PV_CURRENT = 220 // /10 A
    const val PV_POWER = 223 // signed W
    const val PV_CHARGING_POWER = 224 // signed W
    const val LOAD_PERCENT = 225 // %
    const val DCDC_TEMPERATURE = 226 // signed °C
    const val INVERTER_TEMPERATURE = 227 // signed °C
    const val BATTERY_PERCENT = 229 // %
    const val BATTERY_CURRENT = 232 // signed /10 A

    const val WARNING_BATTERY_NOT_CONNECTED_BIT = 9

    val MODES = mapOf(
        0 to OperatingMode.POWER_ON, 1 to OperatingMode.STANDBY, 2 to OperatingMode.GRID,
        3 to OperatingMode.OFF_GRID, 4 to OperatingMode.BYPASS, 5 to OperatingMode.CHARGING, 6 to OperatingMode.FAULT,
    )

    val FAULTS = mapOf(
        1 to "Przegrzanie modułu falownika", 2 to "Przegrzanie modułu DC/DC", 3 to "Przepięcie baterii",
        4 to "Przegrzanie modułu PV", 5 to "Zwarcie wyjścia", 6 to "Przepięcie falownika", 7 to "Przeciążenie wyjścia",
        8 to "Przepięcie szyny DC", 9 to "Przekroczony czas startu szyny DC", 10 to "Przetężenie PV", 11 to "Przepięcie PV",
        12 to "Przetężenie baterii", 13 to "Przetężenie falownika", 14 to "Za niskie napięcie szyny DC",
        16 to "Za duża składowa stała falownika", 18 to "Za duży offset prądu wyjścia", 19 to "Za duży offset prądu falownika",
        20 to "Za duży offset prądu baterii", 21 to "Za duży offset prądu PV", 22 to "Za niskie napięcie falownika",
        23 to "Ochrona przed mocą ujemną", 24 to "Utrata jednostki nadrzędnej (praca równoległa)",
        25 to "Błąd sygnału synchronizacji (praca równoległa)", 26 to "Niezgodny typ baterii", 27 to "Niezgodne oprogramowanie (praca równoległa)",
    )

    val WARNINGS = mapOf(
        1 to "Nieprawidłowy przebieg sieci", 3 to "Za niskie napięcie sieci", 4 to "Za wysoka częstotliwość sieci",
        5 to "Za niska częstotliwość sieci", 6 to "Za niskie napięcie PV", 7 to "Przegrzanie", 8 to "Niskie napięcie baterii",
        9 to "Bateria niepodłączona", 10 to "Przeciążenie", 11 to "Wyrównywanie ładowania baterii",
        12 to "Bateria rozładowana poniżej punktu powrotu", 13 to "Ograniczenie mocy wyjściowej", 14 to "Zablokowany wentylator",
        15 to "Za mało energii PV", 16 to "Przerwana komunikacja równoległa", 17 to "Niezgodny tryb wyjścia (praca równoległa)",
        18 to "Za duża różnica napięć baterii (praca równoległa)", 19 to "Błąd komunikacji z BMS baterii litowej",
        20 to "Prąd rozładowania przekracza nastawę", 22 to "Przeciążenie wyjścia 2",
    )

    /** Fields this register map can deliver; everything else is N/A on this interface. */
    val CAPABILITIES: Set<TelemetryField> = setOf(
        TelemetryField.PV_VOLTAGE, TelemetryField.PV_CURRENT, TelemetryField.PV_POWER, TelemetryField.PV_CHARGING_POWER,
        TelemetryField.BATTERY_VOLTAGE, TelemetryField.BATTERY_CURRENT, TelemetryField.BATTERY_POWER, TelemetryField.BATTERY_SOC,
        TelemetryField.GRID_VOLTAGE, TelemetryField.GRID_POWER, TelemetryField.GRID_FREQUENCY,
        TelemetryField.LOAD_POWER, TelemetryField.LOAD_APPARENT_POWER, TelemetryField.LOAD_PERCENT,
        TelemetryField.INVERTER_POWER, TelemetryField.OUTPUT_VOLTAGE, TelemetryField.OUTPUT_CURRENT,
        TelemetryField.OUTPUT_FREQUENCY, TelemetryField.INVERTER_TEMPERATURE, TelemetryField.OPERATING_MODE,
        TelemetryField.WARNINGS, TelemetryField.FAULTS,
    )
}

/** Converts raw register blocks to telemetry. */
fun interface InverterDataMapper<R> {
    fun map(raw: R, timestamp: Instant): InverterTelemetry
}

/** Raw SMG blocks: [status] = registers 100..109, [live] = registers 201..234. */
class SmgRawBlocks(val status: IntArray, val live: IntArray) {
    init {
        require(status.size >= SmgRegisterMap.STATUS_COUNT) { "status block too short" }
        require(live.size >= SmgRegisterMap.LIVE_COUNT) { "live block too short" }
    }
}

class SmgModbusMapper(private val providerId: String) : InverterDataMapper<SmgRawBlocks> {

    override fun map(raw: SmgRawBlocks, timestamp: Instant): InverterTelemetry {
        fun u(reg: Int): Int = raw.live[reg - SmgRegisterMap.LIVE_START]
        fun s(reg: Int): Int = ModbusCodec.toSigned16(u(reg))
        fun u32(reg: Int): Long {
            val i = reg - SmgRegisterMap.STATUS_START
            return (raw.status[i].toLong() shl 16) or raw.status[i + 1].toLong()
        }

        val faultBits = u32(SmgRegisterMap.FAULT_CODE)
        val warningBits = u32(SmgRegisterMap.WARNING_CODE)
        val batteryConnected = warningBits and (1L shl SmgRegisterMap.WARNING_BATTERY_NOT_CONNECTED_BIT) == 0L

        val batteryVoltage = (u(SmgRegisterMap.BATTERY_VOLTAGE) / 10.0).takeIf { batteryConnected && it > 0.0 }
        val soc = u(SmgRegisterMap.BATTERY_PERCENT).toDouble().takeIf { batteryConnected && it in 0.0..100.0 }
        val modeCode = u(SmgRegisterMap.OPERATING_MODE)

        return InverterTelemetry(
            timestamp = timestamp,
            providerId = providerId,
            pv = PvReading(
                voltageV = u(SmgRegisterMap.PV_VOLTAGE) / 10.0,
                currentA = u(SmgRegisterMap.PV_CURRENT) / 10.0,
                powerW = s(SmgRegisterMap.PV_POWER).toDouble().coerceAtLeast(0.0),
                chargingPowerW = s(SmgRegisterMap.PV_CHARGING_POWER).toDouble(),
            ),
            battery = BatteryReading(
                voltageV = batteryVoltage,
                currentA = (s(SmgRegisterMap.BATTERY_AVERAGE_CURRENT) / 10.0).takeIf { batteryConnected },
                powerW = s(SmgRegisterMap.BATTERY_AVERAGE_POWER).toDouble().takeIf { batteryConnected },
                socPercent = soc,
                connected = batteryConnected,
            ),
            grid = GridReading(
                voltageV = u(SmgRegisterMap.GRID_VOLTAGE) / 10.0,
                powerW = s(SmgRegisterMap.GRID_POWER).toDouble(),
                frequencyHz = u(SmgRegisterMap.GRID_FREQUENCY) / 100.0,
            ),
            load = LoadReading(
                powerW = s(SmgRegisterMap.OUTPUT_POWER).toDouble().coerceAtLeast(0.0),
                apparentPowerVa = u(SmgRegisterMap.OUTPUT_VA).toDouble(),
                percent = u(SmgRegisterMap.LOAD_PERCENT).toDouble(),
            ),
            inverter = InverterStatusReading(
                powerW = s(SmgRegisterMap.INVERTER_POWER).toDouble(),
                outputVoltageV = u(SmgRegisterMap.OUTPUT_VOLTAGE) / 10.0,
                outputCurrentA = u(SmgRegisterMap.OUTPUT_CURRENT) / 10.0,
                outputFrequencyHz = u(SmgRegisterMap.OUTPUT_FREQUENCY) / 100.0,
                temperatureC = s(SmgRegisterMap.INVERTER_TEMPERATURE).toDouble(),
                auxTemperatureC = s(SmgRegisterMap.DCDC_TEMPERATURE).toDouble(),
                mode = SmgRegisterMap.MODES[modeCode] ?: OperatingMode.UNKNOWN,
                rawMode = modeCode.toString(),
                warnings = bits(warningBits, SmgRegisterMap.WARNINGS, InverterEvent.Severity.WARNING),
                faults = bits(faultBits, SmgRegisterMap.FAULTS, InverterEvent.Severity.FAULT),
            ),
        )
    }

    private fun bits(value: Long, names: Map<Int, String>, severity: InverterEvent.Severity): List<InverterEvent> =
        (0 until 32).filter { value and (1L shl it) != 0L }
            .map { InverterEvent(it, names[it] ?: "Kod producenta (bit $it)", severity) }
}

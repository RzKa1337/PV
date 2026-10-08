package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.inverter.BatteryReading
import com.solartracker.pro.core.inverter.GridReading
import com.solartracker.pro.core.inverter.InverterStatusReading
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.LoadReading
import com.solartracker.pro.core.inverter.MpptReading
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.core.inverter.PvReading
import java.time.Instant

/** Where a sample comes from. SIMULATOR data is analysed separately and never presented as a device result. */
enum class DataOrigin(val label: String) {
    DEVICE("pomiar z urządzenia"),
    IMPORTED("zaimportowany log"),
    SIMULATOR("symulator"),
}

/** Measured quantities the analyzer understands (SI units: V, A, W, VA, Hz, %, °C, kWh, s). */
enum class Channel(val label: String, val unit: String, val group: ChannelGroup) {
    PV_VOLTAGE("Napięcie PV", "V", ChannelGroup.PV),
    PV_CURRENT("Prąd PV", "A", ChannelGroup.PV),
    PV_POWER("Moc PV", "W", ChannelGroup.PV),
    PV_CHARGING_POWER("Moc ładowania z PV", "W", ChannelGroup.PV),
    PV_ENERGY_DAY("Energia PV dziś", "kWh", ChannelGroup.PV),
    PV_ENERGY_TOTAL("Energia PV łącznie", "kWh", ChannelGroup.PV),
    AC_VOLTAGE("Napięcie wyjścia AC", "V", ChannelGroup.AC),
    AC_CURRENT("Prąd wyjścia AC", "A", ChannelGroup.AC),
    AC_FREQUENCY("Częstotliwość wyjścia", "Hz", ChannelGroup.AC),
    INVERTER_POWER("Moc falownika", "W", ChannelGroup.INVERTER),
    LOAD_POWER("Moc obciążenia", "W", ChannelGroup.LOAD),
    LOAD_APPARENT("Moc pozorna obciążenia", "VA", ChannelGroup.LOAD),
    LOAD_PERCENT("Obciążenie", "%", ChannelGroup.LOAD),
    LOAD_ENERGY("Energia obciążenia", "kWh", ChannelGroup.LOAD),
    BATTERY_VOLTAGE("Napięcie baterii", "V", ChannelGroup.BATTERY),
    BATTERY_CURRENT("Prąd baterii", "A", ChannelGroup.BATTERY),
    BATTERY_POWER("Moc baterii", "W", ChannelGroup.BATTERY),
    SOC("SOC", "%", ChannelGroup.BATTERY),
    BATTERY_TEMPERATURE("Temperatura baterii", "°C", ChannelGroup.BATTERY),
    INVERTER_TEMPERATURE("Temperatura falownika", "°C", ChannelGroup.INVERTER),
    DCDC_TEMPERATURE("Temperatura DC/DC", "°C", ChannelGroup.INVERTER),
    UPTIME("Czas pracy", "s", ChannelGroup.INVERTER),
    GRID_VOLTAGE("Napięcie sieci", "V", ChannelGroup.GRID),
    GRID_FREQUENCY("Częstotliwość sieci", "Hz", ChannelGroup.GRID),
    GRID_POWER("Moc sieci (+ pobór)", "W", ChannelGroup.GRID),
    GRID_IMPORT_ENERGY("Energia pobrana z sieci", "kWh", ChannelGroup.GRID),
    GRID_EXPORT_ENERGY("Energia oddana do sieci", "kWh", ChannelGroup.GRID),
}

enum class ChannelGroup(val label: String) { PV("PV"), AC("AC"), BATTERY("Bateria"), INVERTER("Falownik"), GRID("Sieć"), LOAD("Obciążenie") }

/**
 * One normalized row of Anenji data. Missing values are absent from [values] (never 0 by default).
 * Battery current/power: + charging; grid power: + import.
 */
data class AnalyzerSample(
    val time: Instant,
    val values: Map<Channel, Double>,
    val origin: DataOrigin,
    val mode: OperatingMode? = null,
    val warnings: Set<Int> = emptySet(),
    val faults: Set<Int> = emptySet(),
    val mppts: List<MpptReading> = emptyList(),
    /** Where the row came from, untouched (file line, original fields, raw register words) – for forensics. */
    val raw: RawRef? = null,
) {
    operator fun get(c: Channel): Double? = values[c]

    /** Real power / apparent power of the load (CALCULATED); null when not computable. */
    val powerFactor: Double?
        get() {
            val w = values[Channel.LOAD_POWER] ?: return null
            val va = values[Channel.LOAD_APPARENT]?.takeIf { it > 50 } ?: return null
            return (w / va).takeIf { it in 0.0..1.05 }?.coerceAtMost(1.0)
        }
}

/**
 * Untouched source of a sample: file + line/record, the original text fields (header → cell), the raw timestamp and,
 * for register logs, the raw 16-bit words by address. Normalised values are an extra layer on top of this.
 */
data class RawRef(
    val source: String,
    val locator: String,
    val rawTimestamp: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val registers: Map<Int, Int> = emptyMap(),
)

/** One communication attempt (from the live connection log or an imported log). */
data class CommRecord(
    val time: Instant,
    val ok: Boolean,
    val error: CommError? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)

enum class CommError(val label: String) {
    TIMEOUT("przekroczony czas"),
    CRC("błąd CRC"),
    INVALID_FRAME("nieprawidłowa ramka"),
    DISCONNECTED("rozłączenie"),
    OTHER("inny błąd"),
    ;

    companion object {
        /** Classifies a free-text error (connection log, imported logs). */
        fun classify(text: String?): CommError {
            val t = text?.lowercase() ?: return OTHER
            return when {
                "crc" in t -> CRC
                "timeout" in t || "czas" in t || "odpowiedzi" in t -> TIMEOUT
                "ramk" in t || "frame" in t || "malformed" in t || "nieprawidłow" in t -> INVALID_FRAME
                "rozłącz" in t || "disconnect" in t || "refused" in t || "reset" in t || "unreachable" in t -> DISCONNECTED
                else -> OTHER
            }
        }
    }
}

object AnalyzerSamples {
    /** Live telemetry → sample (values already validated/sanitized by the connection manager). */
    fun fromTelemetry(t: InverterTelemetry, origin: DataOrigin): AnalyzerSample {
        val v = mutableMapOf<Channel, Double>()
        fun put(c: Channel, x: Double?) { if (x != null && x.isFinite()) v[c] = x }
        put(Channel.PV_VOLTAGE, t.pv.voltageV); put(Channel.PV_CURRENT, t.pv.currentA); put(Channel.PV_POWER, t.pv.powerW)
        put(Channel.PV_CHARGING_POWER, t.pv.chargingPowerW); put(Channel.PV_ENERGY_DAY, t.pv.energyTodayKwh); put(Channel.PV_ENERGY_TOTAL, t.pv.energyTotalKwh)
        put(Channel.BATTERY_VOLTAGE, t.battery.voltageV); put(Channel.BATTERY_CURRENT, t.battery.currentA); put(Channel.BATTERY_POWER, t.battery.powerW)
        put(Channel.SOC, t.battery.socPercent); put(Channel.BATTERY_TEMPERATURE, t.battery.temperatureC)
        put(Channel.GRID_VOLTAGE, t.grid.voltageV); put(Channel.GRID_FREQUENCY, t.grid.frequencyHz); put(Channel.GRID_POWER, t.grid.powerW)
        put(Channel.GRID_IMPORT_ENERGY, t.grid.importEnergyKwh); put(Channel.GRID_EXPORT_ENERGY, t.grid.exportEnergyKwh)
        put(Channel.LOAD_POWER, t.load.powerW); put(Channel.LOAD_APPARENT, t.load.apparentPowerVa); put(Channel.LOAD_PERCENT, t.load.percent)
        put(Channel.LOAD_ENERGY, t.load.energyTodayKwh)
        put(Channel.INVERTER_POWER, t.inverter.powerW); put(Channel.AC_VOLTAGE, t.inverter.outputVoltageV); put(Channel.AC_CURRENT, t.inverter.outputCurrentA)
        put(Channel.AC_FREQUENCY, t.inverter.outputFrequencyHz); put(Channel.INVERTER_TEMPERATURE, t.inverter.temperatureC)
        put(Channel.DCDC_TEMPERATURE, t.inverter.auxTemperatureC)
        return AnalyzerSample(t.timestamp, v, origin, t.inverter.mode.takeIf { it != OperatingMode.UNKNOWN },
            t.inverter.warnings.map { it.code }.toSet(), t.inverter.faults.map { it.code }.toSet(), t.mppts)
    }

    /** Stored 30 s / 15 min history row → sample (bucket start as time). */
    fun fromHistory(h: HistorySample, origin: DataOrigin): AnalyzerSample {
        val v = mutableMapOf<Channel, Double>()
        fun put(c: Channel, x: Double?) { if (x != null && x.isFinite()) v[c] = x }
        put(Channel.PV_POWER, h.pvW); put(Channel.LOAD_POWER, h.loadW); put(Channel.BATTERY_POWER, h.batteryW); put(Channel.GRID_POWER, h.gridW)
        put(Channel.BATTERY_VOLTAGE, h.batteryVoltageV); put(Channel.BATTERY_CURRENT, h.batteryCurrentA); put(Channel.SOC, h.socPercent)
        put(Channel.INVERTER_TEMPERATURE, h.inverterTemperatureC); put(Channel.BATTERY_TEMPERATURE, h.batteryTemperatureC)
        return AnalyzerSample(h.start, v, origin, h.mode, h.warningCodes, h.faultCodes)
    }

    /** Sample → telemetry, so the existing [com.solartracker.pro.core.inverter.TelemetryValidator] can be reused on history. */
    fun toTelemetry(s: AnalyzerSample): InverterTelemetry = InverterTelemetry(
        timestamp = s.time,
        providerId = "analyzer",
        pv = PvReading(s[Channel.PV_VOLTAGE], s[Channel.PV_CURRENT], s[Channel.PV_POWER], s[Channel.PV_ENERGY_DAY], s[Channel.PV_ENERGY_TOTAL], s[Channel.PV_CHARGING_POWER]),
        battery = BatteryReading(s[Channel.BATTERY_VOLTAGE], s[Channel.BATTERY_CURRENT], s[Channel.BATTERY_POWER], s[Channel.SOC], s[Channel.BATTERY_TEMPERATURE]),
        grid = GridReading(s[Channel.GRID_VOLTAGE], null, s[Channel.GRID_POWER], s[Channel.GRID_FREQUENCY], s[Channel.GRID_IMPORT_ENERGY], s[Channel.GRID_EXPORT_ENERGY]),
        load = LoadReading(s[Channel.LOAD_POWER], s[Channel.LOAD_APPARENT], s[Channel.LOAD_PERCENT], s[Channel.LOAD_ENERGY]),
        inverter = InverterStatusReading(s[Channel.INVERTER_POWER], s[Channel.AC_VOLTAGE], s[Channel.AC_CURRENT], s[Channel.AC_FREQUENCY],
            s[Channel.INVERTER_TEMPERATURE], s[Channel.DCDC_TEMPERATURE], s.mode ?: OperatingMode.UNKNOWN),
        mppts = s.mppts,
    )
}

package com.solartracker.pro.core.inverter

import java.time.Instant

/**
 * One reading of the inverter. Every value is nullable: null means the protocol/firmware does not
 * provide it or the inverter reported it as invalid (shown as N/A, never replaced by a guess).
 * Sign conventions (normalized by the mappers):
 * - [BatteryReading.powerW] / [BatteryReading.currentA]: + charging, − discharging
 * - [GridReading.powerW]: + import from the grid, − export to the grid
 */
data class InverterTelemetry(
    /** When the data was read (device clock is not trusted; the phone's receive time). */
    val timestamp: Instant,
    val providerId: String,
    val pv: PvReading = PvReading(),
    val battery: BatteryReading = BatteryReading(),
    val grid: GridReading = GridReading(),
    val load: LoadReading = LoadReading(),
    val inverter: InverterStatusReading = InverterStatusReading(),
    /** Per-MPPT values, empty when the protocol only reports a total. */
    val mppts: List<MpptReading> = emptyList(),
)

data class PvReading(
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val energyTodayKwh: Double? = null,
    val energyTotalKwh: Double? = null,
    /** PV power going to battery charging, when reported separately. */
    val chargingPowerW: Double? = null,
)

data class BatteryReading(
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val socPercent: Double? = null,
    val temperatureC: Double? = null,
    /** False when the inverter reports no battery connected. */
    val connected: Boolean? = null,
) {
    val chargePowerW: Double? get() = powerW?.coerceAtLeast(0.0)
    val dischargePowerW: Double? get() = powerW?.let { (-it).coerceAtLeast(0.0) }
    val state: BatteryFlowState
        get() = when {
            connected == false -> BatteryFlowState.DISCONNECTED
            powerW == null -> BatteryFlowState.UNKNOWN
            powerW >= IDLE_THRESHOLD_W -> BatteryFlowState.CHARGING
            powerW <= -IDLE_THRESHOLD_W -> BatteryFlowState.DISCHARGING
            else -> BatteryFlowState.IDLE
        }

    companion object {
        const val IDLE_THRESHOLD_W = 20.0
    }
}

enum class BatteryFlowState { CHARGING, DISCHARGING, IDLE, DISCONNECTED, UNKNOWN }

data class GridReading(
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val frequencyHz: Double? = null,
    val importEnergyKwh: Double? = null,
    val exportEnergyKwh: Double? = null,
) {
    val importPowerW: Double? get() = powerW?.coerceAtLeast(0.0)
    val exportPowerW: Double? get() = powerW?.let { (-it).coerceAtLeast(0.0) }
}

data class LoadReading(
    val powerW: Double? = null,
    val apparentPowerVa: Double? = null,
    val percent: Double? = null,
    val energyTodayKwh: Double? = null,
)

data class InverterStatusReading(
    val powerW: Double? = null,
    val outputVoltageV: Double? = null,
    val outputCurrentA: Double? = null,
    val outputFrequencyHz: Double? = null,
    val temperatureC: Double? = null,
    /** Second temperature sensor (e.g. DC/DC module). */
    val auxTemperatureC: Double? = null,
    val mode: OperatingMode = OperatingMode.UNKNOWN,
    /** Mode as reported by the device (for display/diagnostics). */
    val rawMode: String? = null,
    val warnings: List<InverterEvent> = emptyList(),
    val faults: List<InverterEvent> = emptyList(),
)

data class MpptReading(val index: Int, val voltageV: Double?, val currentA: Double?, val powerW: Double?)

enum class OperatingMode { POWER_ON, STANDBY, GRID, OFF_GRID, BYPASS, CHARGING, FAULT, UNKNOWN }

/** A warning or fault reported by the inverter (bit index / code + human readable text). */
data class InverterEvent(val code: Int, val description: String, val severity: Severity) {
    enum class Severity { WARNING, FAULT }
}

/** Which telemetry fields a protocol can provide at all (others are shown as N/A with this reason). */
enum class TelemetryField {
    PV_VOLTAGE, PV_CURRENT, PV_POWER, PV_ENERGY_TODAY, PV_ENERGY_TOTAL, PV_CHARGING_POWER,
    BATTERY_VOLTAGE, BATTERY_CURRENT, BATTERY_POWER, BATTERY_SOC, BATTERY_TEMPERATURE,
    GRID_VOLTAGE, GRID_CURRENT, GRID_POWER, GRID_FREQUENCY, GRID_IMPORT_ENERGY, GRID_EXPORT_ENERGY,
    LOAD_POWER, LOAD_APPARENT_POWER, LOAD_PERCENT, LOAD_ENERGY,
    INVERTER_POWER, OUTPUT_VOLTAGE, OUTPUT_CURRENT, OUTPUT_FREQUENCY, INVERTER_TEMPERATURE,
    OPERATING_MODE, WARNINGS, FAULTS, MPPT_DETAILS,
}

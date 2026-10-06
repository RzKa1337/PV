package com.solartracker.pro.core.inverter

import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Why a value was rejected or flagged. */
enum class Validity(val label: String) {
    VALID("poprawna"),
    OUT_OF_RANGE("poza zakresem fizycznym"),
    IMPOSSIBLE_JUMP("nierealny skok"),
    INCONSISTENT("niespójna z innymi wartościami"),
    STALE("nieaktualna"),
}

/**
 * One telemetry value with provenance: what it is, when it was read, where it came from, how it may be
 * used ([kind]) and whether it passed validation ([validity]).
 */
data class TelemetryValue(
    val field: TelemetryField,
    val value: Double?,
    val unit: String,
    val timestamp: Instant,
    val source: String,
    val kind: DataKind,
    val validity: Validity,
    val reason: String? = null,
)

enum class IssueType(val label: String) {
    OUT_OF_RANGE("Wartość poza zakresem"),
    JUMP("Nierealny skok wartości"),
    INCONSISTENT("Niespójne odczyty"),
    STALE("Nieaktualne dane"),
    ZERO_PV("Zerowa produkcja PV przy słońcu"),
    FROZEN("Wartości nie zmieniają się"),
}

data class TelemetryIssue(val type: IssueType, val field: TelemetryField?, val detail: String)

/**
 * Plausibility limits. Generic physical bounds for a 48 V hybrid inverter, NOT values from a vendor
 * datasheet: they only reject readings that cannot be real (decoding errors, wrong register map).
 */
data class PlausibilityLimits(
    val batteryNominalV: Double = 48.0,
    val ratedPowerW: Double = 6200.0,
    val pvVoltageMaxV: Double = 600.0,
    val pvCurrentMaxA: Double = 40.0,
    val tempRangeC: ClosedFloatingPointRange<Double> = -40.0..120.0,
    val frequencyRangeHz: ClosedFloatingPointRange<Double> = 40.0..70.0,
    val acVoltageMaxV: Double = 300.0,
    /** Max credible SOC change per minute [%]. */
    val maxSocRatePerMin: Double = 5.0,
    /** Max credible battery voltage change between reads [V], scaled to the nominal voltage. */
    val maxBatteryVoltageJumpV: Double = 6.0,
    /** Relative tolerance for P ≈ V × I checks (protocol rounding, averaging windows). */
    val powerTolerance: Double = 0.35,
) {
    val batteryVoltageRange: ClosedFloatingPointRange<Double> get() = (batteryNominalV * 0.70)..(batteryNominalV * 1.40)
    val powerMaxW: Double get() = ratedPowerW * 2.0
}

/** Result of validating one reading: per-field values, issues and a sanitized telemetry without rejected values. */
data class TelemetryValidation(
    val values: Map<TelemetryField, TelemetryValue>,
    val issues: List<TelemetryIssue>,
    /** Same reading with every rejected value set to null (safe to use downstream). */
    val sanitized: InverterTelemetry,
) {
    val invalidFields: Set<TelemetryField> get() = values.filterValues { it.validity != Validity.VALID }.keys
    val valid: Boolean get() = issues.none { it.type != IssueType.ZERO_PV }
}

/**
 * Read-only validation of inverter telemetry. Values that cannot be physical are rejected (INVALID) and
 * removed from [TelemetryValidation.sanitized], so they never reach statistics, calibration or forecasts.
 */
class TelemetryValidator(private val limits: PlausibilityLimits = PlausibilityLimits()) {

    /**
     * @param previous last accepted (sanitized) reading, for jump checks
     * @param expectedPvW model expectation now (with weather/shading), for the zero-PV check; null = unknown
     * @param stale true when the reading is older than the freshness limit
     */
    fun validate(t: InverterTelemetry, previous: InverterTelemetry?, expectedPvW: Double? = null, stale: Boolean = false): TelemetryValidation {
        val issues = mutableListOf<TelemetryIssue>()
        val values = linkedMapOf<TelemetryField, TelemetryValue>()
        val src = t.providerId

        fun put(field: TelemetryField, v: Double?, unit: String, range: ClosedFloatingPointRange<Double>?) {
            v ?: return
            var validity = Validity.VALID
            var reason: String? = null
            if (!v.isFinite() || (range != null && v !in range)) {
                validity = Validity.OUT_OF_RANGE
                reason = "${fmt(v)} $unit poza zakresem ${range?.let { "${fmt(it.start)}–${fmt(it.endInclusive)}" } ?: ""}".trim()
                issues += TelemetryIssue(IssueType.OUT_OF_RANGE, field, reason)
            }
            val kind = when {
                validity != Validity.VALID -> DataKind.INVALID
                stale -> DataKind.STALE
                else -> DataKind.MEASURED
            }
            values[field] = TelemetryValue(field, v, unit, t.timestamp, src, kind, if (stale && validity == Validity.VALID) Validity.STALE else validity, reason)
        }

        val pw = limits.powerMaxW
        put(TelemetryField.PV_VOLTAGE, t.pv.voltageV, "V", 0.0..limits.pvVoltageMaxV)
        put(TelemetryField.PV_CURRENT, t.pv.currentA, "A", 0.0..limits.pvCurrentMaxA)
        put(TelemetryField.PV_POWER, t.pv.powerW, "W", 0.0..pw)
        put(TelemetryField.PV_CHARGING_POWER, t.pv.chargingPowerW, "W", -pw..pw)
        put(TelemetryField.BATTERY_VOLTAGE, t.battery.voltageV, "V", limits.batteryVoltageRange)
        put(TelemetryField.BATTERY_CURRENT, t.battery.currentA, "A", -(pw / limits.batteryNominalV)..(pw / limits.batteryNominalV))
        put(TelemetryField.BATTERY_POWER, t.battery.powerW, "W", -pw..pw)
        put(TelemetryField.BATTERY_SOC, t.battery.socPercent, "%", 0.0..100.0)
        put(TelemetryField.BATTERY_TEMPERATURE, t.battery.temperatureC, "°C", limits.tempRangeC)
        put(TelemetryField.GRID_VOLTAGE, t.grid.voltageV, "V", 0.0..limits.acVoltageMaxV)
        put(TelemetryField.GRID_POWER, t.grid.powerW, "W", -pw..pw)
        put(TelemetryField.GRID_FREQUENCY, t.grid.frequencyHz?.takeIf { it > 0.0 }, "Hz", limits.frequencyRangeHz)
        put(TelemetryField.LOAD_POWER, t.load.powerW, "W", 0.0..pw)
        put(TelemetryField.LOAD_PERCENT, t.load.percent, "%", 0.0..250.0)
        put(TelemetryField.OUTPUT_VOLTAGE, t.inverter.outputVoltageV, "V", 0.0..limits.acVoltageMaxV)
        put(TelemetryField.INVERTER_TEMPERATURE, t.inverter.temperatureC, "°C", limits.tempRangeC)

        fun reject(field: TelemetryField, validity: Validity, type: IssueType, detail: String) {
            issues += TelemetryIssue(type, field, detail)
            values[field]?.let { values[field] = it.copy(kind = DataKind.INVALID, validity = validity, reason = detail) }
        }
        fun ok(field: TelemetryField) = values[field]?.validity.let { it == Validity.VALID || it == Validity.STALE }

        // Consistency: P ≈ V × I (only above a meaningful power, protocols round and average).
        val pv = t.pv
        if (ok(TelemetryField.PV_POWER) && ok(TelemetryField.PV_VOLTAGE) && ok(TelemetryField.PV_CURRENT) &&
            pv.powerW != null && pv.voltageV != null && pv.currentA != null && pv.powerW > 300
        ) {
            val vi = pv.voltageV * pv.currentA
            if (abs(vi - pv.powerW) > pv.powerW * limits.powerTolerance) {
                reject(TelemetryField.PV_POWER, Validity.INCONSISTENT, IssueType.INCONSISTENT,
                    "PV: ${fmt(pv.powerW)} W, a U×I = ${fmt(vi)} W")
            }
        }
        val b = t.battery
        if (ok(TelemetryField.BATTERY_POWER) && ok(TelemetryField.BATTERY_VOLTAGE) && ok(TelemetryField.BATTERY_CURRENT) &&
            b.powerW != null && b.voltageV != null && b.currentA != null && abs(b.powerW) > 300
        ) {
            val vi = b.voltageV * b.currentA
            if (vi * b.powerW < 0 || abs(vi - b.powerW) > abs(b.powerW) * limits.powerTolerance) {
                reject(TelemetryField.BATTERY_POWER, Validity.INCONSISTENT, IssueType.INCONSISTENT,
                    "Bateria: ${fmt(b.powerW)} W, a U×I = ${fmt(vi)} W")
            }
        }

        // Jumps against the previous accepted reading.
        if (previous != null) {
            val dtMin = Duration.between(previous.timestamp, t.timestamp).toMillis() / 60_000.0
            if (dtMin > 0) {
                val socNow = b.socPercent
                val socPrev = previous.battery.socPercent
                if (ok(TelemetryField.BATTERY_SOC) && socNow != null && socPrev != null &&
                    abs(socNow - socPrev) > limits.maxSocRatePerMin * maxOf(dtMin, 1.0)
                ) {
                    reject(TelemetryField.BATTERY_SOC, Validity.IMPOSSIBLE_JUMP, IssueType.JUMP,
                        "SOC ${fmt(socPrev)} → ${fmt(socNow)}% w ${fmt(dtMin * 60)} s")
                }
                val vNow = b.voltageV
                val vPrev = previous.battery.voltageV
                val vJump = limits.maxBatteryVoltageJumpV * limits.batteryNominalV / 48.0
                if (ok(TelemetryField.BATTERY_VOLTAGE) && vNow != null && vPrev != null && dtMin < 2.0 && abs(vNow - vPrev) > vJump) {
                    reject(TelemetryField.BATTERY_VOLTAGE, Validity.IMPOSSIBLE_JUMP, IssueType.JUMP,
                        "Napięcie baterii ${fmt(vPrev)} → ${fmt(vNow)} V w ${fmt(dtMin * 60)} s")
                }
            }
        }

        if (stale) issues += TelemetryIssue(IssueType.STALE, null, "Odczyt starszy niż limit świeżości")

        // Zero PV while the model expects meaningful production (not a validity error: a possible fault).
        if (expectedPvW != null && expectedPvW > ZERO_PV_MIN_EXPECTED_W && (pv.powerW ?: -1.0) == 0.0) {
            issues += TelemetryIssue(IssueType.ZERO_PV, TelemetryField.PV_POWER, "PV = 0 W, model oczekuje ok. ${fmt(expectedPvW)} W")
        }

        return TelemetryValidation(values, issues, sanitize(t, values))
    }

    private fun sanitize(t: InverterTelemetry, v: Map<TelemetryField, TelemetryValue>): InverterTelemetry {
        fun keep(field: TelemetryField, value: Double?): Double? =
            if (v[field]?.kind == DataKind.INVALID) null else value
        return t.copy(
            pv = t.pv.copy(
                voltageV = keep(TelemetryField.PV_VOLTAGE, t.pv.voltageV), currentA = keep(TelemetryField.PV_CURRENT, t.pv.currentA),
                powerW = keep(TelemetryField.PV_POWER, t.pv.powerW), chargingPowerW = keep(TelemetryField.PV_CHARGING_POWER, t.pv.chargingPowerW),
            ),
            battery = t.battery.copy(
                voltageV = keep(TelemetryField.BATTERY_VOLTAGE, t.battery.voltageV), currentA = keep(TelemetryField.BATTERY_CURRENT, t.battery.currentA),
                powerW = keep(TelemetryField.BATTERY_POWER, t.battery.powerW), socPercent = keep(TelemetryField.BATTERY_SOC, t.battery.socPercent),
                temperatureC = keep(TelemetryField.BATTERY_TEMPERATURE, t.battery.temperatureC),
            ),
            grid = t.grid.copy(
                voltageV = keep(TelemetryField.GRID_VOLTAGE, t.grid.voltageV), powerW = keep(TelemetryField.GRID_POWER, t.grid.powerW),
                frequencyHz = keep(TelemetryField.GRID_FREQUENCY, t.grid.frequencyHz),
            ),
            load = t.load.copy(powerW = keep(TelemetryField.LOAD_POWER, t.load.powerW), percent = keep(TelemetryField.LOAD_PERCENT, t.load.percent)),
            inverter = t.inverter.copy(
                outputVoltageV = keep(TelemetryField.OUTPUT_VOLTAGE, t.inverter.outputVoltageV),
                temperatureC = keep(TelemetryField.INVERTER_TEMPERATURE, t.inverter.temperatureC),
            ),
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    companion object {
        /** Expected PV power above which a reading of exactly 0 W is reported. */
        const val ZERO_PV_MIN_EXPECTED_W = 300.0
    }
}

/** Kind of communication failure, for diagnostics and statistics. */
enum class LinkErrorKind(val label: String) {
    TIMEOUT("brak odpowiedzi (timeout)"),
    PROTOCOL("błąd ramki / CRC"),
    DEVICE_REJECTED("falownik odrzucił zapytanie"),
    CONNECTION("błąd połączenia"),
    OTHER("inny błąd"),
    ;

    companion object {
        fun classify(e: Throwable): LinkErrorKind = when (e) {
            is TimeoutException -> TIMEOUT
            is MalformedFrameException -> if (e.message?.contains("NAK") == true) DEVICE_REJECTED else PROTOCOL
            is ModbusException -> DEVICE_REJECTED
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.io.IOException -> CONNECTION
            else -> OTHER
        }
    }
}

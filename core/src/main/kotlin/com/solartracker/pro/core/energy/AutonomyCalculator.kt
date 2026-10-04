package com.solartracker.pro.core.energy

/**
 * How long a battery can supply a load without any charging.
 *
 * @property deliverableKwh energy the loads get from max SOC down to min SOC, after discharge losses
 * @property hours runtime for the given load, null for zero load
 * @property limitedByDischargePower true when the load exceeds the battery's max discharge power
 *   (then only the allowed part is supplied and the rest must come from elsewhere)
 */
data class AutonomyResult(
    val deliverableKwh: Double,
    val loadKw: Double,
    val hours: Double?,
    val limitedByDischargePower: Boolean,
) {
    val days: Double? get() = hours?.div(24.0)
}

object AutonomyCalculator {

    /** Energy available for the loads from a full battery (max SOC → min SOC). */
    fun deliverableKwh(battery: BatteryStorage): Double {
        require(battery.isValid) { "Invalid battery: ${battery.validate()}" }
        return battery.operatingWindowKwh * battery.dischargeEfficiency
    }

    /** Autonomy for a daily consumption, e.g. 5 kWh/day (assumed evenly spread). */
    fun forDailyConsumption(battery: BatteryStorage, kwhPerDay: Double): AutonomyResult =
        forConstantLoad(battery, kwhPerDay / 24.0)

    /** Autonomy for a constant load, e.g. 0.5 kW. */
    fun forConstantLoad(battery: BatteryStorage, loadKw: Double): AutonomyResult {
        require(loadKw.isFinite() && loadKw >= 0.0) { "Load must be >= 0" }
        val energy = deliverableKwh(battery)
        val limited = loadKw > battery.maxDischargePowerKw
        val supplied = minOf(loadKw, battery.maxDischargePowerKw)
        return AutonomyResult(
            deliverableKwh = energy,
            loadKw = loadKw,
            hours = if (supplied > 0.0) energy / supplied else null,
            limitedByDischargePower = limited,
        )
    }
}

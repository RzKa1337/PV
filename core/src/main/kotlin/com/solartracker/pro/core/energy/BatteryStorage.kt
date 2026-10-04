package com.solartracker.pro.core.energy

enum class BatteryType { LIFEPO4, LI_ION, AGM, GEL, OTHER }

enum class BatteryValidationError {
    CAPACITY_NOT_POSITIVE,
    USABLE_CAPACITY_OUT_OF_RANGE,
    SOC_OUT_OF_RANGE,
    MIN_SOC_NOT_BELOW_MAX,
    CHARGE_POWER_NOT_POSITIVE,
    DISCHARGE_POWER_NOT_POSITIVE,
    EFFICIENCY_OUT_OF_RANGE,
}

/**
 * Energy storage parameters.
 *
 * SOC percentages refer to the usable capacity
 * ([nominalCapacityKwh] × [usableCapacityPercent]); the battery is only cycled
 * between [minSocPercent] and [maxSocPercent].
 *
 * Charge power/efficiency apply to energy taken from PV; discharge power/efficiency
 * apply to energy delivered to the loads.
 */
data class BatteryStorage(
    val nominalCapacityKwh: Double = 10.0,
    val usableCapacityPercent: Double = 90.0,
    val initialSocPercent: Double = 50.0,
    val minSocPercent: Double = 10.0,
    val maxSocPercent: Double = 100.0,
    val maxChargePowerKw: Double = 5.0,
    val maxDischargePowerKw: Double = 5.0,
    val chargeEfficiencyPercent: Double = 95.0,
    val dischargeEfficiencyPercent: Double = 95.0,
    val type: BatteryType = BatteryType.LIFEPO4,
) {
    /** Energy stored at 100% SOC [kWh]. */
    val usableCapacityKwh: Double get() = nominalCapacityKwh * usableCapacityPercent / 100.0

    /** Energy between min and max SOC, as stored in the cells [kWh]. */
    val operatingWindowKwh: Double get() = usableCapacityKwh * (maxSocPercent - minSocPercent) / 100.0

    val chargeEfficiency: Double get() = chargeEfficiencyPercent / 100.0
    val dischargeEfficiency: Double get() = dischargeEfficiencyPercent / 100.0

    fun storedKwh(socPercent: Double): Double = usableCapacityKwh * socPercent / 100.0

    fun validate(): List<BatteryValidationError> = buildList {
        fun Double.ok() = isFinite()
        if (!nominalCapacityKwh.ok() || nominalCapacityKwh <= 0.0 || nominalCapacityKwh > MAX_CAPACITY_KWH) {
            add(BatteryValidationError.CAPACITY_NOT_POSITIVE)
        }
        if (!usableCapacityPercent.ok() || usableCapacityPercent <= 0.0 || usableCapacityPercent > 100.0) {
            add(BatteryValidationError.USABLE_CAPACITY_OUT_OF_RANGE)
        }
        val socs = listOf(initialSocPercent, minSocPercent, maxSocPercent)
        if (socs.any { !it.ok() || it < 0.0 || it > 100.0 }) add(BatteryValidationError.SOC_OUT_OF_RANGE)
        if (!(minSocPercent < maxSocPercent)) add(BatteryValidationError.MIN_SOC_NOT_BELOW_MAX)
        if (!maxChargePowerKw.ok() || maxChargePowerKw <= 0.0) add(BatteryValidationError.CHARGE_POWER_NOT_POSITIVE)
        if (!maxDischargePowerKw.ok() || maxDischargePowerKw <= 0.0) add(BatteryValidationError.DISCHARGE_POWER_NOT_POSITIVE)
        val effs = listOf(chargeEfficiencyPercent, dischargeEfficiencyPercent)
        if (effs.any { !it.ok() || it <= 0.0 || it > 100.0 }) add(BatteryValidationError.EFFICIENCY_OUT_OF_RANGE)
    }

    val isValid: Boolean get() = validate().isEmpty()

    companion object {
        const val MAX_CAPACITY_KWH = 10_000.0
    }
}

package com.solartracker.pro.core.energy

enum class BackupSource { GRID, GENERATOR }

/** Optional prices; null means "not set". */
data class EnergyPrices(
    val gridPricePerKwh: Double? = null,
    val generatorPricePerKwh: Double? = null,
    val feedInPricePerKwh: Double? = null,
    val batteryCost: Double? = null,
    val backupSource: BackupSource = BackupSource.GRID,
) {
    /** Price of energy that PV and battery could not cover, or null if not set. */
    val backupPricePerKwh: Double?
        get() = when (backupSource) {
            BackupSource.GRID -> gridPricePerKwh
            BackupSource.GENERATOR -> generatorPricePerKwh
        }
}

/** Net cost of a period: bought backup energy minus sold surplus. */
fun EnergyBalance.netCost(prices: EnergyPrices): Double? {
    val buy = prices.backupPricePerKwh ?: return null
    return gridKwh * buy - surplusKwh * (prices.feedInPricePerKwh ?: 0.0)
}

data class CostComparison(
    val days: Int,
    val costWithoutBattery: Double,
    val costWithBattery: Double,
    val batteryCost: Double?,
) {
    val savings: Double get() = costWithoutBattery - costWithBattery
    val dailySavings: Double get() = if (days > 0) savings / days else 0.0
    val monthlySavings: Double get() = dailySavings * 365.0 / 12.0
    val yearlySavings: Double get() = dailySavings * 365.0

    /** Years until the battery pays for itself, null if it never does or cost is unknown. */
    val paybackYears: Double?
        get() {
            val cost = batteryCost ?: return null
            return if (yearlySavings > 0.0) cost / yearlySavings else null
        }

    companion object {
        /** Compares two simulations of the same period (same PV and consumption). */
        fun of(withoutBattery: SimulationResult, withBattery: SimulationResult, prices: EnergyPrices): CostComparison? {
            val without = withoutBattery.balance.netCost(prices) ?: return null
            val with = withBattery.balance.netCost(prices) ?: return null
            return CostComparison(
                days = withBattery.days.size,
                costWithoutBattery = without,
                costWithBattery = with,
                batteryCost = prices.batteryCost,
            )
        }
    }
}

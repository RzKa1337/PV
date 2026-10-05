package com.solartracker.pro.core.economics

import kotlin.math.pow

/** Investment and running costs (currency-agnostic, e.g. PLN). */
data class SystemCosts(
    val pv: Double = 0.0,
    val battery: Double = 0.0,
    val inverter: Double = 0.0,
    val installation: Double = 0.0,
    val maintenancePerYear: Double = 0.0,
    /** Replacement cost of the battery after its life (0 = not counted). */
    val batteryReplacement: Double = 0.0,
    val batteryLifeYears: Int = 15,
) {
    val investment: Double get() = pv + battery + inverter + installation
}

data class TariffAssumptions(
    val gridPricePerKwh: Double,
    val feedInPricePerKwh: Double = 0.0,
    /** Generator energy cost (fuel + wear) for off-grid backup [per kWh]. */
    val generatorPricePerKwh: Double? = null,
    /** Yearly price increase (0.03 = 3%). */
    val priceEscalation: Double = 0.0,
    /** Discount rate for NPV/LCOE (0.05 = 5%). */
    val discountRate: Double = 0.05,
)

data class EnergyYear(
    val productionKwh: Double,
    /** PV energy used on site (directly or through the battery). */
    val selfConsumedKwh: Double,
    val exportedKwh: Double,
    /** Energy delivered by the battery (for storage cost). */
    val batteryThroughputKwh: Double = 0.0,
    /** Energy that would otherwise come from the generator instead of the grid. */
    val generatorAvoidedKwh: Double = 0.0,
)

data class EconomicsResult(
    val investment: Double,
    val firstYearSavings: Double,
    val simplePaybackYears: Double?,
    /** Payback with escalation, degradation and maintenance; null if never within lifetime. */
    val paybackYears: Double?,
    val npv: Double,
    /** (total net savings − investment) / investment over the lifetime [%]. */
    val roiPercent: Double,
    /** Levelized cost of PV energy [per kWh]. */
    val lcoe: Double?,
    /** Levelized cost of stored energy [per kWh discharged]. */
    val storageCostPerKwh: Double?,
    val yearly: List<YearCashflow>,
)

data class YearCashflow(val year: Int, val savings: Double, val costs: Double, val cumulative: Double)

/** Lifetime economics: savings, payback, ROI, NPV, LCOE and cost of storage. */
object EconomicsEngine {
    fun evaluate(costs: SystemCosts, tariff: TariffAssumptions, energy: EnergyYear, lifetimeYears: Int = 25, degradationPerYear: Double = 0.005): EconomicsResult {
        require(lifetimeYears in 1..50)
        val avoidedPrice = { year: Int -> tariff.gridPricePerKwh * (1 + tariff.priceEscalation).pow(year - 1) }
        val genPrice = { year: Int -> (tariff.generatorPricePerKwh ?: 0.0) * (1 + tariff.priceEscalation).pow(year - 1) }
        var cumulative = -costs.investment
        var npv = -costs.investment
        var payback: Double? = null
        var totalNet = 0.0
        var discountedEnergy = 0.0
        var discountedCosts = costs.investment
        val yearly = mutableListOf<YearCashflow>()
        for (y in 1..lifetimeYears) {
            val deg = (1 - degradationPerYear).pow(y - 1)
            val savings = (energy.selfConsumedKwh - energy.generatorAvoidedKwh).coerceAtLeast(0.0) * deg * avoidedPrice(y) +
                energy.generatorAvoidedKwh * deg * genPrice(y) + energy.exportedKwh * deg * tariff.feedInPricePerKwh
            val yearCosts = costs.maintenancePerYear + if (costs.batteryReplacement > 0 && y % costs.batteryLifeYears == 0 && y < lifetimeYears) costs.batteryReplacement else 0.0
            val net = savings - yearCosts
            val before = cumulative
            cumulative += net
            if (payback == null && before < 0 && cumulative >= 0) payback = (y - 1) + (-before / net)
            val df = (1 + tariff.discountRate).pow(y)
            npv += net / df
            totalNet += net
            discountedEnergy += energy.productionKwh * deg / df
            discountedCosts += yearCosts / df
            yearly += YearCashflow(y, savings, yearCosts, cumulative)
        }
        val first = yearly.first().savings - costs.maintenancePerYear
        return EconomicsResult(
            investment = costs.investment,
            firstYearSavings = yearly.first().savings,
            simplePaybackYears = if (first > 0) costs.investment / first else null,
            paybackYears = payback,
            npv = npv,
            roiPercent = if (costs.investment > 0) (totalNet - costs.investment) / costs.investment * 100 else 0.0,
            lcoe = if (discountedEnergy > 0) discountedCosts / discountedEnergy else null,
            storageCostPerKwh = if (energy.batteryThroughputKwh > 0 && costs.battery > 0) {
                val years = minOf(costs.batteryLifeYears, lifetimeYears)
                costs.battery / (energy.batteryThroughputKwh * years)
            } else null,
            yearly = yearly,
        )
    }

    /** Cost of a period's energy from the grid/generator and the value of exports. */
    fun periodCost(importedKwh: Double, generatorKwh: Double, exportedKwh: Double, tariff: TariffAssumptions): Double =
        importedKwh * tariff.gridPricePerKwh + generatorKwh * (tariff.generatorPricePerKwh ?: 0.0) - exportedKwh * tariff.feedInPricePerKwh
}

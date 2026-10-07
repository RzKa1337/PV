package com.solartracker.pro.core.economics

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionPeriod
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.IrradianceModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import java.time.LocalDate
import java.time.ZoneId

/** The installation as it is today. [coldRoom] is added on top of [consumption] when present. */
data class WhatIfBase(
    val system: PvSystem,
    val battery: BatteryStorage?,
    val consumption: ConsumptionProfile,
    val coldRoom: CoolingLoadProfile? = null,
    val coldRoomOn: Boolean = coldRoom != null,
)

/** Changes to try; null = keep the base value. [extraInvestment] is the cost of the change (for payback). */
data class WhatIfChange(
    val peakKw: Double? = null,
    val batteryKwh: Double? = null,
    val tiltDeg: Double? = null,
    val azimuthDeg: Double? = null,
    /** 1.2 = +20 % consumption. */
    val consumptionFactor: Double? = null,
    val coldRoomOn: Boolean? = null,
    val extraInvestment: Double = 0.0,
)

enum class BackupKind { GRID, GENERATOR }

data class AnnualEnergy(
    val pvKwh: Double,
    val loadKwh: Double,
    val selfConsumedKwh: Double,
    /** Energy from the grid or generator [kWh]. */
    val backupKwh: Double,
    val surplusKwh: Double,
    val autarkyPercent: Double,
    val selfConsumptionPercent: Double,
    /** Cost of backup energy minus feed-in value per year. */
    val annualCost: Double,
)

data class WhatIfResult(
    val base: AnnualEnergy,
    val variant: AnnualEnergy,
    val pvChangePercent: Double?,
    val backupChangePercent: Double?,
    /** Variant − base cost per year (negative = saving). */
    val annualCostDelta: Double,
    /** Extra investment / yearly saving; null without investment or without saving. */
    val paybackYears: Double?,
    val basis: String,
)

/**
 * "What if?" on the existing models: [EnergyFlowSimulator] (PV + battery + load, one year) and
 * [EconomicsEngine.periodCost]. With the default clear-sky irradiance the result is an upper bound; pass the
 * climate-aware model for typical weather.
 */
object WhatIfSimulator {
    fun compare(
        base: WhatIfBase,
        change: WhatIfChange,
        location: GeoLocation,
        zone: ZoneId,
        year: Int,
        tariff: TariffAssumptions,
        backup: BackupKind = BackupKind.GRID,
        irradiance: IrradianceModel = ClearSkyModel(),
        basis: String = "model bezchmurnego nieba (górna granica)",
        stepMinutes: Long = 60,
    ): WhatIfResult {
        require(change.consumptionFactor == null || change.consumptionFactor in 0.0..5.0) { "consumption factor 0–5" }
        require(change.batteryKwh == null || change.batteryKwh >= 0.0) { "battery ≥ 0" }
        val sim = EnergyFlowSimulator(PvEstimator(irradiance))
        val b = run(sim, base.system, base.battery, consumption(base, base.coldRoomOn, 1.0), location, zone, year, tariff, backup, stepMinutes)
        val system = base.system.copy(
            peakPowerKw = change.peakKw ?: base.system.peakPowerKw,
            tiltDeg = change.tiltDeg ?: base.system.tiltDeg,
            azimuthDeg = change.azimuthDeg ?: base.system.azimuthDeg,
        )
        val battery = when {
            change.batteryKwh == null -> base.battery
            change.batteryKwh <= 0.0 -> null
            else -> (base.battery ?: BatteryStorage()).copy(nominalCapacityKwh = change.batteryKwh)
        }
        val v = run(sim, system, battery, consumption(base, change.coldRoomOn ?: base.coldRoomOn, change.consumptionFactor ?: 1.0),
            location, zone, year, tariff, backup, stepMinutes)
        val delta = v.annualCost - b.annualCost
        return WhatIfResult(
            base = b,
            variant = v,
            pvChangePercent = if (b.pvKwh > 0) (v.pvKwh / b.pvKwh - 1) * 100 else null,
            backupChangePercent = if (b.backupKwh > 0) (v.backupKwh / b.backupKwh - 1) * 100 else null,
            annualCostDelta = delta,
            paybackYears = if (change.extraInvestment > 0 && delta < 0) change.extraInvestment / -delta else null,
            basis = basis,
        )
    }

    private fun consumption(base: WhatIfBase, coldRoomOn: Boolean, factor: Double): ConsumptionProfile {
        val cold = base.coldRoom?.takeIf { coldRoomOn }
        val ref = LocalDate.of(2026, 7, 1)
        val hourly = (0 until 24).map { h ->
            val coldKw = cold?.averagePowerKw(ref.atTime(h, 30).atZone(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"), null) ?: 0.0
            base.consumption.powerKwAtHour(h) * factor + coldKw
        }
        return ConsumptionProfile.fromPeriods(hourly.mapIndexed { h, kw -> ConsumptionPeriod(h, h + 1, kw) })
    }

    private fun run(
        sim: EnergyFlowSimulator, system: PvSystem, battery: BatteryStorage?, consumption: ConsumptionProfile, location: GeoLocation,
        zone: ZoneId, year: Int, tariff: TariffAssumptions, backup: BackupKind, stepMinutes: Long,
    ): AnnualEnergy {
        val start = LocalDate.of(year, 1, 1)
        val days = start.lengthOfYear()
        val r = sim.simulate(system, location, start, days, zone, consumption, battery, stepMinutes)
        val steps = r.steps
        val pv = steps.sumOf { it.pvKwh }
        val load = steps.sumOf { it.consumptionKwh }
        val grid = steps.sumOf { it.gridKwh }
        val surplus = steps.sumOf { it.surplusKwh }
        val self = (pv - surplus).coerceAtLeast(0.0)
        val cost = when (backup) {
            BackupKind.GRID -> EconomicsEngine.periodCost(grid, 0.0, surplus, tariff)
            BackupKind.GENERATOR -> EconomicsEngine.periodCost(0.0, grid, 0.0, tariff)
        }
        return AnnualEnergy(pv, load, self, grid, surplus,
            if (load > 0) (1 - grid / load) * 100 else 0.0, if (pv > 0) self / pv * 100 else 0.0, cost)
    }
}

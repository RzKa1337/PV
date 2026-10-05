package com.solartracker.pro.core.energy

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * PV production of consecutive time steps, produced by the shared [PvEstimator].
 * [hourOfDay] is the local hour used to look up consumption.
 */
class PvSeries(
    val start: List<Instant>,
    val durationHours: DoubleArray,
    val pvKwh: DoubleArray,
    val hourOfDay: IntArray,
    val date: List<LocalDate>,
) {
    val size: Int get() = pvKwh.size

    init {
        require(start.size == size && durationHours.size == size && hourOfDay.size == size && date.size == size)
    }
}

/** Energy flows in one simulation step [kWh]. SOC is the value at the end of the step. */
data class FlowStep(
    val start: Instant,
    val date: LocalDate,
    val hourOfDay: Int,
    val durationHours: Double,
    val pvKwh: Double,
    val consumptionKwh: Double,
    /** PV energy used directly by the loads. */
    val directUseKwh: Double,
    /** PV energy sent to the battery (before charge losses). */
    val toBatteryKwh: Double,
    /** Energy delivered by the battery to the loads (after discharge losses). */
    val fromBatteryKwh: Double,
    /** Missing energy taken from the grid or a generator. */
    val gridKwh: Double,
    /** PV energy neither used nor stored (export or curtailed). */
    val surplusKwh: Double,
    val batteryLossKwh: Double,
    val socPercent: Double,
) {
    val chargePowerKw: Double get() = toBatteryKwh / durationHours
    val dischargePowerKw: Double get() = fromBatteryKwh / durationHours
}

/** Instantaneous power flow [kW]. Battery: positive [chargeKw] or [dischargeKw]. */
data class InstantFlow(
    val pvKw: Double,
    val loadKw: Double,
    val directKw: Double,
    val chargeKw: Double,
    val dischargeKw: Double,
    /** Missing power taken from the grid or a generator. */
    val gridImportKw: Double,
    /** PV surplus neither used nor stored (export or curtailed). */
    val exportKw: Double,
) {
    /** + charging, − discharging. */
    val batteryKw: Double get() = chargeKw - dischargeKw

    /** PV minus load: + surplus, − deficit. */
    val netKw: Double get() = pvKw - loadKw
}

/** Totals over a period [kWh]. */
data class EnergyBalance(
    val pvKwh: Double,
    val consumptionKwh: Double,
    val directUseKwh: Double,
    val toBatteryKwh: Double,
    val fromBatteryKwh: Double,
    val gridKwh: Double,
    val surplusKwh: Double,
    val batteryLossKwh: Double,
    val startSocPercent: Double?,
    val endSocPercent: Double?,
) {
    companion object {
        fun of(steps: List<FlowStep>, startSocPercent: Double?, hasBattery: Boolean) = EnergyBalance(
            pvKwh = steps.sumOf { it.pvKwh },
            consumptionKwh = steps.sumOf { it.consumptionKwh },
            directUseKwh = steps.sumOf { it.directUseKwh },
            toBatteryKwh = steps.sumOf { it.toBatteryKwh },
            fromBatteryKwh = steps.sumOf { it.fromBatteryKwh },
            gridKwh = steps.sumOf { it.gridKwh },
            surplusKwh = steps.sumOf { it.surplusKwh },
            batteryLossKwh = steps.sumOf { it.batteryLossKwh },
            startSocPercent = if (hasBattery) startSocPercent else null,
            endSocPercent = if (hasBattery) steps.lastOrNull()?.socPercent ?: startSocPercent else null,
        )
    }
}

/** Battery usage statistics over a period. */
data class BatteryStatistics(
    val equivalentFullCycles: Double,
    val averageSocPercent: Double,
    val minSocPercent: Double,
    val maxSocPercent: Double,
    val chargedKwh: Double,
    val dischargedKwh: Double,
    val lossesKwh: Double,
    val hoursEmpty: Double,
    val hoursFull: Double,
)

data class DayResult(val date: LocalDate, val balance: EnergyBalance, val steps: List<FlowStep>)

/** Hourly aggregate of [FlowStep]s, used for charts. Energies in kWh = average kW. */
data class HourlyFlow(
    val start: Instant,
    val pvKwh: Double,
    val consumptionKwh: Double,
    val toBatteryKwh: Double,
    val fromBatteryKwh: Double,
    val gridKwh: Double,
    val surplusKwh: Double,
    val socPercent: Double,
)

class SimulationResult(
    val battery: BatteryStorage?,
    val steps: List<FlowStep>,
    val startSocPercent: Double?,
) {
    val hasBattery: Boolean get() = battery != null

    val balance: EnergyBalance by lazy { EnergyBalance.of(steps, startSocPercent, hasBattery) }

    /** Results per day; each day starts with the previous day's final SOC. */
    val days: List<DayResult> by lazy {
        var soc = startSocPercent
        steps.groupBy { it.date }.map { (date, daySteps) ->
            DayResult(date, EnergyBalance.of(daySteps, soc, hasBattery), daySteps).also {
                soc = daySteps.last().socPercent
            }
        }
    }

    val hourly: List<HourlyFlow> by lazy {
        steps.groupBy { it.date to it.hourOfDay }.values.map { h ->
            HourlyFlow(
                start = h.first().start,
                pvKwh = h.sumOf { it.pvKwh },
                consumptionKwh = h.sumOf { it.consumptionKwh },
                toBatteryKwh = h.sumOf { it.toBatteryKwh },
                fromBatteryKwh = h.sumOf { it.fromBatteryKwh },
                gridKwh = h.sumOf { it.gridKwh },
                surplusKwh = h.sumOf { it.surplusKwh },
                socPercent = h.last().socPercent,
            )
        }
    }

    /** Battery statistics, or null without a battery. */
    val statistics: BatteryStatistics? by lazy {
        val b = battery ?: return@lazy null
        val start = startSocPercent ?: b.initialSocPercent
        val totalHours = steps.sumOf { it.durationHours }
        val socs = steps.map { it.socPercent }
        // Energy drawn from the cells (before discharge losses) per usable window = full cycles.
        val cellDischarge = steps.sumOf { it.fromBatteryKwh } / b.dischargeEfficiency
        BatteryStatistics(
            equivalentFullCycles = if (b.operatingWindowKwh > 0) cellDischarge / b.operatingWindowKwh else 0.0,
            averageSocPercent = if (totalHours > 0) steps.sumOf { it.socPercent * it.durationHours } / totalHours else start,
            minSocPercent = (socs + start).min(),
            maxSocPercent = (socs + start).max(),
            chargedKwh = steps.sumOf { it.toBatteryKwh },
            dischargedKwh = steps.sumOf { it.fromBatteryKwh },
            lossesKwh = steps.sumOf { it.batteryLossKwh },
            hoursEmpty = steps.filter { it.socPercent <= b.minSocPercent + SOC_EPSILON }.sumOf { it.durationHours },
            hoursFull = steps.filter { it.socPercent >= b.maxSocPercent - SOC_EPSILON }.sumOf { it.durationHours },
        )
    }

    /**
     * SOC at [instant], interpolated linearly inside the simulation step that contains it;
     * null without a battery or outside the simulated period.
     */
    fun socAt(instant: Instant): Double? {
        if (battery == null || steps.isEmpty()) return null
        val index = steps.indexOfLast { !it.start.isAfter(instant) }
        if (index < 0) return null
        val step = steps[index]
        val stepMillis = (step.durationHours * 3_600_000.0)
        val elapsed = (instant.toEpochMilli() - step.start.toEpochMilli()).toDouble()
        if (elapsed > stepMillis) return if (index == steps.lastIndex) null else step.socPercent
        val before = if (index == 0) startSocPercent ?: battery.initialSocPercent else steps[index - 1].socPercent
        return before + (step.socPercent - before) * (elapsed / stepMillis).coerceIn(0.0, 1.0)
    }

    /** Returns a result restricted to [fromDate]..[toDate], keeping the carried-over SOC. */
    fun slice(fromDate: LocalDate, toDate: LocalDate): SimulationResult {
        val before = steps.lastOrNull { it.date < fromDate }
        val selected = steps.filter { it.date in fromDate..toDate }
        return SimulationResult(battery, selected, if (battery == null) null else before?.socPercent ?: startSocPercent)
    }

    companion object {
        const val SOC_EPSILON = 0.05
    }
}

/**
 * Time-step simulation of the energy flow
 * PV → consumption → battery charging; battery → consumption → grid/generator.
 *
 * PV production always comes from the shared [PvEstimator], so battery results are
 * consistent with every other estimate in the app.
 */
class EnergyFlowSimulator(private val estimator: PvEstimator = PvEstimator()) {

    /** PV production for [days] consecutive local days starting at [startDate]. */
    fun pvSeries(
        system: PvSystem,
        location: GeoLocation,
        startDate: LocalDate,
        days: Int,
        zone: ZoneId,
        stepMinutes: Long = DEFAULT_STEP_MINUTES,
    ): PvSeries {
        require(days > 0) { "days must be positive" }
        require(stepMinutes in 1..60 && 60 % stepMinutes == 0L) { "stepMinutes must divide an hour" }
        val starts = ArrayList<Instant>()
        val durations = ArrayList<Double>()
        val pv = ArrayList<Double>()
        val hours = ArrayList<Int>()
        val dates = ArrayList<LocalDate>()
        val stepMillis = stepMinutes * 60_000L
        for (d in 0 until days) {
            val date = startDate.plusDays(d.toLong())
            var t = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            while (t < end) {
                val slice = min(stepMillis, end - t)
                val start = Instant.ofEpochMilli(t)
                val hoursLen = slice / 3_600_000.0
                val power = estimator.powerKw(system, location, Instant.ofEpochMilli(t + slice / 2))
                starts += start
                durations += hoursLen
                pv += power * hoursLen
                hours += start.atZone(zone).hour
                dates += date
                t += slice
            }
        }
        return PvSeries(starts, durations.toDoubleArray(), pv.toDoubleArray(), hours.toIntArray(), dates)
    }

    /**
     * Runs the energy flow over [series]. Without a [battery] all surplus is exported and all
     * deficit comes from the grid. [startSocPercent] defaults to the battery's initial SOC.
     *
     * @throws IllegalArgumentException for an invalid battery configuration
     */
    fun run(
        series: PvSeries,
        consumption: ConsumptionProfile,
        battery: BatteryStorage?,
        startSocPercent: Double? = battery?.initialSocPercent,
    ): SimulationResult {
        if (battery != null) {
            val errors = battery.validate()
            require(errors.isEmpty()) { "Invalid battery: $errors" }
        }
        val usable = battery?.usableCapacityKwh ?: 0.0
        val minStored = battery?.storedKwh(battery.minSocPercent) ?: 0.0
        val maxStored = battery?.storedKwh(battery.maxSocPercent) ?: 0.0
        val initialSoc = (startSocPercent ?: 0.0).coerceIn(0.0, 100.0)
        var stored = if (battery != null) battery.storedKwh(initialSoc) else 0.0

        val steps = ArrayList<FlowStep>(series.size)
        for (i in 0 until series.size) {
            val dt = series.durationHours[i]
            val pv = series.pvKwh[i]
            val load = consumption.powerKwAtHour(series.hourOfDay[i]) * dt
            val r = flowStep(pv, load, dt, battery, stored, minStored, maxStored)
            stored = r.storedAfter
            val direct = r.direct
            val toBattery = r.toBattery
            val fromBattery = r.fromBattery
            val deficit = r.grid
            val surplus = r.surplus
            val loss = r.loss

            steps += FlowStep(
                start = series.start[i],
                date = series.date[i],
                hourOfDay = series.hourOfDay[i],
                durationHours = dt,
                pvKwh = pv,
                consumptionKwh = load,
                directUseKwh = direct,
                toBatteryKwh = toBattery,
                fromBatteryKwh = fromBattery,
                gridKwh = deficit,
                surplusKwh = surplus,
                batteryLossKwh = loss,
                socPercent = if (battery != null && usable > 0) stored / usable * 100.0 else 0.0,
            )
        }
        return SimulationResult(battery, steps, if (battery != null) initialSoc else null)
    }

    /**
     * Instantaneous power flow [kW] for the current PV power and load, using the same rules as
     * [run] (evaluated over one second, so power limits and min/max SOC apply exactly as in the
     * simulation). [socPercent] is the battery state right now.
     */
    fun instantFlow(pvKw: Double, loadKw: Double, battery: BatteryStorage?, socPercent: Double?): InstantFlow {
        val pv = pvKw.coerceAtLeast(0.0)
        val load = loadKw.coerceAtLeast(0.0)
        if (battery != null) require(battery.isValid) { "Invalid battery: ${battery.validate()}" }
        val dt = 1.0 / 3600.0
        val stored = battery?.storedKwh((socPercent ?: battery.initialSocPercent).coerceIn(0.0, 100.0)) ?: 0.0
        val r = flowStep(
            pv * dt, load * dt, dt, battery, stored,
            battery?.storedKwh(battery.minSocPercent) ?: 0.0,
            battery?.storedKwh(battery.maxSocPercent) ?: 0.0,
        )
        return InstantFlow(
            pvKw = pv,
            loadKw = load,
            directKw = r.direct / dt,
            chargeKw = r.toBattery / dt,
            dischargeKw = r.fromBattery / dt,
            gridImportKw = r.grid / dt,
            exportKw = r.surplus / dt,
        )
    }

    private class StepResult(
        val direct: Double,
        val toBattery: Double,
        val fromBattery: Double,
        val grid: Double,
        val surplus: Double,
        val loss: Double,
        val storedAfter: Double,
    )

    /** One step of PV → loads → battery → grid/generator; energies in kWh over [dt] hours. */
    private fun flowStep(
        pv: Double,
        load: Double,
        dt: Double,
        battery: BatteryStorage?,
        storedBefore: Double,
        minStored: Double,
        maxStored: Double,
    ): StepResult {
        var stored = storedBefore
        val direct = min(pv, load)
        var surplus = pv - direct
        var deficit = load - direct
        var toBattery = 0.0
        var fromBattery = 0.0
        var loss = 0.0

        if (battery != null) {
            // 1. Surplus PV charges the battery, limited by power and remaining room.
            if (surplus > 0.0 && stored < maxStored) {
                val roomInput = (maxStored - stored) / battery.chargeEfficiency
                toBattery = min(surplus, min(battery.maxChargePowerKw * dt, roomInput))
                stored = min(maxStored, stored + toBattery * battery.chargeEfficiency)
                loss += toBattery * (1.0 - battery.chargeEfficiency)
                surplus -= toBattery
            }
            // 2. Missing energy comes from the battery, limited by power and min SOC.
            if (deficit > 0.0 && stored > minStored) {
                val deliverable = (stored - minStored) * battery.dischargeEfficiency
                fromBattery = min(deficit, min(battery.maxDischargePowerKw * dt, deliverable))
                val drawn = fromBattery / battery.dischargeEfficiency
                stored = max(minStored, stored - drawn)
                loss += drawn - fromBattery
                deficit -= fromBattery
            }
        }
        return StepResult(direct, toBattery, fromBattery, deficit, surplus, loss, stored)
    }

    /** Convenience: PV series + energy flow in one call. */
    fun simulate(
        system: PvSystem,
        location: GeoLocation,
        startDate: LocalDate,
        days: Int,
        zone: ZoneId,
        consumption: ConsumptionProfile,
        battery: BatteryStorage?,
        stepMinutes: Long = DEFAULT_STEP_MINUTES,
    ): SimulationResult = run(pvSeries(system, location, startDate, days, zone, stepMinutes), consumption, battery)

    companion object {
        const val DEFAULT_STEP_MINUTES = 15L
    }
}

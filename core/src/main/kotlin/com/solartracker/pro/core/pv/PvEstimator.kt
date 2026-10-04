package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

/** Estimated PV power at a point in time. */
data class PowerPoint(val time: Instant, val powerKw: Double)

/** Estimated energy for one panel tilt. */
data class TiltEstimate(val tiltDeg: Double, val energyKwh: Double)

/** Estimated energy per month for one panel tilt. */
data class MonthlyEstimate(val tiltDeg: Double, val energyByMonthKwh: Map<Month, Double>) {
    val yearlyKwh: Double get() = energyByMonthKwh.values.sum()
}

/**
 * Local PV production estimator. All results are ESTIMATES derived from a model
 * ([IrradianceModel], clear sky by default) – never measurements.
 */
class PvEstimator(
    private val irradianceModel: IrradianceModel = ClearSkyModel(),
) {

    /** Estimated AC power [kW] at [instant]. */
    fun powerKw(system: PvSystem, location: GeoLocation, instant: Instant): Double =
        powerKw(listOf(system.sanitized()), location, instant)[0]

    /**
     * Power curve for the local calendar [date] in [zone], one point every [stepMinutes],
     * from 00:00 to 24:00 inclusive.
     */
    fun dailyProfile(
        system: PvSystem,
        location: GeoLocation,
        date: LocalDate,
        zone: ZoneId,
        stepMinutes: Long = 15,
    ): List<PowerPoint> {
        require(stepMinutes > 0) { "stepMinutes must be positive" }
        val sanitized = listOf(system.sanitized())
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val step = Duration.ofMinutes(stepMinutes)
        val points = ArrayList<PowerPoint>()
        var t = start
        while (!t.isAfter(end)) {
            points += PowerPoint(t, powerKw(sanitized, location, t)[0])
            t = t.plus(step)
        }
        return points
    }

    /** Estimated energy [kWh] for the whole local calendar [date] in [zone]. */
    fun dailyEnergyKwh(
        system: PvSystem,
        location: GeoLocation,
        date: LocalDate,
        zone: ZoneId,
        stepMinutes: Long = DEFAULT_STEP_MINUTES,
    ): Double = energyKwh(system, location, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(), stepMinutes)

    /** Estimated energy [kWh] produced between [from] and [to]. */
    fun energyKwh(
        system: PvSystem,
        location: GeoLocation,
        from: Instant,
        to: Instant,
        stepMinutes: Long = DEFAULT_STEP_MINUTES,
    ): Double = integrateKwh(listOf(system.sanitized()), location, from, to, stepMinutes)[0]

    /** Daily energy for each tilt in [tilts]; all other parameters come from [system]. */
    fun compareTilts(
        system: PvSystem,
        location: GeoLocation,
        date: LocalDate,
        zone: ZoneId,
        tilts: List<Double> = COMPARISON_TILTS,
    ): List<TiltEstimate> {
        val systems = tilts.map { system.copy(tiltDeg = it).sanitized() }
        val energy = integrateKwh(
            systems,
            location,
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
            DEFAULT_STEP_MINUTES,
        )
        return systems.mapIndexed { i, s -> TiltEstimate(s.tiltDeg, energy[i]) }
    }

    /** Energy for every month of [year], for each tilt in [tilts]. */
    fun monthlyEnergy(
        system: PvSystem,
        location: GeoLocation,
        year: Int,
        zone: ZoneId,
        tilts: List<Double> = MONTHLY_TILTS,
        stepMinutes: Long = MONTHLY_STEP_MINUTES,
    ): List<MonthlyEstimate> {
        val systems = tilts.map { system.copy(tiltDeg = it).sanitized() }
        val perMonth = Month.entries.associateWith { month ->
            val ym = YearMonth.of(year, month)
            integrateKwh(
                systems,
                location,
                ym.atDay(1).atStartOfDay(zone).toInstant(),
                ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
                stepMinutes,
            )
        }
        return systems.mapIndexed { i, s ->
            MonthlyEstimate(s.tiltDeg, Month.entries.associateWith { perMonth.getValue(it)[i] })
        }
    }

    /** Midpoint-rule integration; the sun position is computed once per step for all systems. */
    private fun integrateKwh(
        systems: List<PvSystem>,
        location: GeoLocation,
        from: Instant,
        to: Instant,
        stepMinutes: Long,
    ): DoubleArray {
        require(stepMinutes > 0) { "stepMinutes must be positive" }
        val result = DoubleArray(systems.size)
        if (!to.isAfter(from)) return result
        val stepMillis = stepMinutes * 60_000L
        var t = from.toEpochMilli()
        val end = to.toEpochMilli()
        while (t < end) {
            val sliceMillis = minOf(stepMillis, end - t)
            val mid = Instant.ofEpochMilli(t + sliceMillis / 2)
            val power = powerKw(systems, location, mid)
            val hours = sliceMillis / 3_600_000.0
            for (i in systems.indices) result[i] += power[i] * hours
            t += sliceMillis
        }
        return result
    }

    private fun powerKw(systems: List<PvSystem>, location: GeoLocation, instant: Instant): DoubleArray {
        val position = SolarCalculator.position(location, instant)
        if (!position.isAboveHorizon) return DoubleArray(systems.size)
        val irradiance = irradianceModel.irradiance(position, instant)
        return DoubleArray(systems.size) { i ->
            val s = systems[i]
            val poa = planeOfArrayIrradiance(irradiance, position, s.tiltDeg, s.azimuthDeg)
            (s.peakPowerKw * poa / STC_IRRADIANCE * s.performanceRatio).coerceIn(0.0, s.peakPowerKw)
        }
    }

    companion object {
        /** Standard test conditions irradiance [W/m²] at which kWp is rated. */
        const val STC_IRRADIANCE = 1000.0
        const val DEFAULT_STEP_MINUTES = 5L
        const val MONTHLY_STEP_MINUTES = 10L
        val COMPARISON_TILTS = listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 90.0)
        val MONTHLY_TILTS = listOf(0.0, 30.0, 45.0, 60.0, 90.0)
    }
}

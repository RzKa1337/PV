package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarDetails
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

/** Estimated PV power at a point in time. */
data class PowerPoint(val time: Instant, val powerKw: Double)

/**
 * Model state for one moment (MODELLED, not measured).
 *
 * @property poa plane-of-array irradiance [W/m²]
 * @property angleOfIncidenceDeg angle between the sun's rays and the panel normal
 * @property cellTemperatureC panel temperature, null when the air temperature is unknown
 */
data class PvPointEstimate(
    val sun: SolarDetails,
    val irradiance: Irradiance,
    val ghi: Double,
    val poa: Double,
    val angleOfIncidenceDeg: Double,
    val cellTemperatureC: Double?,
    val powerKw: Double,
    /** True when the inverter limit cut the power. */
    val clipped: Boolean = false,
) {
    /** Share of direct sunlight the panel geometry captures: cos(AOI), 0 when the sun is down. */
    val geometricUtilization: Double
        get() = if (sun.elevationDeg > 0.0) kotlin.math.cos(Math.toRadians(angleOfIncidenceDeg)).coerceAtLeast(0.0) else 0.0
}

/** Estimated energy for one panel tilt. */
data class TiltEstimate(val tiltDeg: Double, val energyKwh: Double)

/** One month of the "change the angle every month" plan. */
data class MonthTilt(
    val month: Month,
    /** Angle that gives the most energy in this month (whole degrees). */
    val bestTiltDeg: Double,
    val bestKwh: Double,
    /** The same month with the panels left at the current angle. */
    val currentTiltKwh: Double,
)

/**
 * Monthly re-tilting vs a fixed angle (ESTIMATES from the same irradiance model). The panels are assumed to be set to
 * [MonthTilt.bestTiltDeg] at the start of each month and left there for the whole month.
 */
data class MonthlyTiltPlan(
    val year: Int,
    val months: List<MonthTilt>,
    val currentTiltDeg: Double,
    /** Best single angle for the whole year and its energy. */
    val bestFixedTiltDeg: Double,
    val bestFixedKwh: Double,
    val stepMinutes: Long,
) {
    val monthlyAdjustedKwh: Double get() = months.sumOf { it.bestKwh }
    val currentFixedKwh: Double get() = months.sumOf { it.currentTiltKwh }
    val gainVsCurrentKwh: Double get() = monthlyAdjustedKwh - currentFixedKwh
    val gainVsBestFixedKwh: Double get() = monthlyAdjustedKwh - bestFixedKwh
    fun gainPercent(baseKwh: Double): Double? = if (baseKwh > 0) (monthlyAdjustedKwh / baseKwh - 1) * 100 else null
}

/** One month: current fixed panels vs the same panels on a tracker (ESTIMATES). */
data class MonthTracker(val month: Month, val fixedKwh: Double, val singleAxisKwh: Double, val dualAxisKwh: Double)

/**
 * "What if a solar tracker were installed": the same panels (kWp, losses, weather/climate model) on the current fixed
 * mount, on a single-axis N–S tracker (±[maxRotationDeg]) and on a dual-axis tracker. Tracker own consumption, wind
 * stow and row shading are NOT included – real gains are lower.
 */
data class TrackerComparison(
    val year: Int,
    val months: List<MonthTracker>,
    val fixedTiltDeg: Double,
    val fixedAzimuthDeg: Double,
    val maxRotationDeg: Double,
    val stepMinutes: Long,
) {
    val fixedKwh: Double get() = months.sumOf { it.fixedKwh }
    val singleAxisKwh: Double get() = months.sumOf { it.singleAxisKwh }
    val dualAxisKwh: Double get() = months.sumOf { it.dualAxisKwh }
    /** Gain of [kwh] over the fixed panels [%]; null without fixed production. */
    fun gainPercent(kwh: Double, baseKwh: Double = fixedKwh): Double? = if (baseKwh > 0) (kwh / baseKwh - 1) * 100 else null
}

/** Estimated energy per month for one panel tilt. */
data class MonthlyEstimate(val tiltDeg: Double, val energyByMonthKwh: Map<Month, Double>) {
    val yearlyKwh: Double get() = energyByMonthKwh.values.sum()
}

/**
 * Local PV production estimator. All results are ESTIMATES derived from a model
 * ([IrradianceModel]: clear sky by default, or weather data) – never measurements.
 * When the model provides the air temperature, panel temperature losses are computed.
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

    /**
     * Best panel angle for every month of [year] if the angle is changed once a month, compared with leaving it at
     * the current angle and with the best single angle for the whole year. All angles [minTiltDeg]..[maxTiltDeg] in
     * steps of [tiltStepDeg] are evaluated in one pass (the sun is computed once per time step for all of them);
     * [stepMinutes] = 15 changes monthly sums by well under 1 % vs 5-minute steps.
     */
    fun monthlyTiltPlan(
        system: PvSystem,
        location: GeoLocation,
        year: Int,
        zone: ZoneId,
        minTiltDeg: Double = PvSystem.MIN_TILT_DEG,
        maxTiltDeg: Double = PvSystem.MAX_TILT_DEG,
        tiltStepDeg: Double = 1.0,
        stepMinutes: Long = PLAN_STEP_MINUTES,
    ): MonthlyTiltPlan {
        require(tiltStepDeg > 0) { "tiltStepDeg must be positive" }
        val base = system.sanitized()
        val tilts = generateSequence(minTiltDeg.coerceIn(PvSystem.MIN_TILT_DEG, PvSystem.MAX_TILT_DEG)) { it + tiltStepDeg }
            .takeWhile { it <= maxTiltDeg.coerceAtMost(PvSystem.MAX_TILT_DEG) + 1e-9 }.toList()
        // The current angle is evaluated exactly, even when it is not on the grid.
        val all = (tilts + base.tiltDeg).distinct()
        val systems = all.map { base.copy(tiltDeg = it) }
        val currentIndex = all.indexOf(base.tiltDeg)
        val perMonth = Month.entries.associateWith { month ->
            val ym = YearMonth.of(year, month)
            integrateKwh(systems, location, ym.atDay(1).atStartOfDay(zone).toInstant(), ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(), stepMinutes)
        }
        val gridIdx = tilts.indices
        val months = Month.entries.map { m ->
            val e = perMonth.getValue(m)
            val best = gridIdx.maxBy { e[it] }
            MonthTilt(m, all[best], e[best], e[currentIndex])
        }
        val yearly = DoubleArray(all.size) { i -> Month.entries.sumOf { perMonth.getValue(it)[i] } }
        val bestFixed = gridIdx.maxBy { yearly[it] }
        return MonthlyTiltPlan(year, months, base.tiltDeg, all[bestFixed], yearly[bestFixed], stepMinutes)
    }

    /**
     * The current panels compared with a single-axis (N–S axis, ±[maxRotationDeg]) and a dual-axis tracker, month by
     * month. Same irradiance model, POA transposition and losses as every other estimate; only the surface orientation
     * differs (geometry from [trackerSurfaceOrientation]).
     */
    fun trackerComparison(
        system: PvSystem,
        location: GeoLocation,
        year: Int,
        zone: ZoneId,
        maxRotationDeg: Double = DEFAULT_TRACKER_ROTATION_DEG,
        stepMinutes: Long = PLAN_STEP_MINUTES,
    ): TrackerComparison {
        require(stepMinutes > 0) { "stepMinutes must be positive" }
        require(maxRotationDeg in 0.0..90.0) { "maxRotationDeg 0–90" }
        val s = system.sanitized()
        val trackers = listOf(TrackerType.FIXED, TrackerType.SINGLE_AXIS, TrackerType.DUAL_AXIS)
        val months = Month.entries.map { month ->
            val ym = YearMonth.of(year, month)
            val e = DoubleArray(trackers.size)
            val stepMillis = stepMinutes * 60_000L
            var t = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            while (t < end) {
                val slice = minOf(stepMillis, end - t)
                val mid = Instant.ofEpochMilli(t + slice / 2)
                val position = SolarCalculator.position(location, mid)
                if (position.isAboveHorizon) {
                    val irradiance = irradianceModel.irradiance(position, mid)
                    val hours = slice / 3_600_000.0
                    trackers.forEachIndexed { i, tracker ->
                        val (tilt, az) = trackerSurfaceOrientation(tracker, s.tiltDeg, s.azimuthDeg, maxRotationDeg, position)
                        e[i] += planePower(s, location, irradiance, position, mid, tilt, az).powerKw * hours
                    }
                }
                t += slice
            }
            MonthTracker(month, e[0], e[1], e[2])
        }
        return TrackerComparison(year, months, s.tiltDeg, s.azimuthDeg, maxRotationDeg, stepMinutes)
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
        return DoubleArray(systems.size) { i -> planePower(systems[i], location, irradiance, position, instant).powerKw }
    }

    /**
     * Everything the model knows about one moment: sun, irradiance, panel geometry, cell
     * temperature and power. Uses exactly the same formulas as [powerKw].
     */
    fun pointEstimate(system: PvSystem, location: GeoLocation, instant: Instant): PvPointEstimate {
        val s = system.sanitized()
        val sun = SolarCalculator.details(location, instant)
        val position = sun.position
        val aoi = angleOfIncidenceDeg(position, s.tiltDeg, s.azimuthDeg)
        if (!position.isAboveHorizon) {
            val night = irradianceModel.irradiance(position, instant)
            return PvPointEstimate(sun, Irradiance(0.0, 0.0, night.ambientTemperatureC), 0.0, 0.0, aoi, night.ambientTemperatureC, 0.0)
        }
        val irradiance = irradianceModel.irradiance(position, instant)
        val p = planePower(s, location, irradiance, position, instant)
        return PvPointEstimate(
            sun = sun,
            irradiance = irradiance,
            ghi = irradiance.ghi(position),
            poa = p.poa,
            angleOfIncidenceDeg = aoi,
            cellTemperatureC = p.cellTemperatureC,
            powerKw = p.powerKw,
            clipped = p.clipped,
        )
    }

    private class PlanePower(val poa: Double, val cellTemperatureC: Double?, val powerKw: Double, val clipped: Boolean)

    /**
     * AC power of one plane:
     * POA (beam + Hay–Davies sky diffuse + ground) → beam reflection loss (ASHRAE IAM) → cell temperature (Faiman with
     * wind, NOCT without) and the module's γ → [PvSystem.performanceRatio] → inverter limit. The PR is an annual
     * figure that already contains typical temperature and angle losses, so both are divided out
     * ([TYPICAL_TEMPERATURE_FACTOR], [typicalAngleFactor]) – they shape the day and the year without changing the
     * annual total twice.
     */
    private fun planePower(
        s: PvSystem,
        location: GeoLocation,
        irradiance: Irradiance,
        position: SolarPosition,
        instant: Instant,
        tiltDeg: Double = s.tiltDeg,
        azimuthDeg: Double = s.azimuthDeg,
    ): PlanePower {
        if (!position.isAboveHorizon) return PlanePower(0.0, irradiance.ambientTemperatureC, 0.0, false)
        val c = poaComponents(irradiance, position, tiltDeg, azimuthDeg, extraterrestrialDni = ClearSkyModel.extraterrestrialIrradiance(instant))
        val poa = c.total
        val iam = ashraeIam(cosIncidence(position, tiltDeg, azimuthDeg), IAM_B0)
        val optical = if (poa > 0) (c.beam * iam + c.skyDiffuse + c.groundReflected) / typicalAngleFactor(location, tiltDeg, azimuthDeg) else 0.0
        val cell = irradiance.ambientTemperatureC?.let { cellTemperatureC(poa, it, irradiance.windMs) }
        val raw = s.peakPowerKw * optical / STC_IRRADIANCE * s.performanceRatio * cellTemperatureFactor(cell, s.temperatureCoefficient)
        val limit = minOf(s.peakPowerKw, s.inverterLimitKw ?: Double.MAX_VALUE)
        return PlanePower(poa, cell, raw.coerceIn(0.0, limit), raw > limit + 1e-9)
    }

    private val angleFactors = java.util.concurrent.ConcurrentHashMap<Long, Double>()

    /**
     * Clear-sky weighted mean of (POA after reflection loss) / POA over a year for this orientation and latitude:
     * the part of the angle loss the annual PR already contains. 12 representative days, 30-minute steps.
     */
    fun typicalAngleFactor(location: GeoLocation, tiltDeg: Double, azimuthDeg: Double): Double {
        val key = (Math.round(location.latitude * 2) * 1_000_000L) + (Math.round(tiltDeg) * 1_000L) + Math.round(azimuthDeg)
        return angleFactors.getOrPut(key) {
            val ref = GeoLocation(Math.round(location.latitude * 2) / 2.0, location.longitude)
            val clear = ClearSkyModel()
            var withIam = 0.0
            var plain = 0.0
            for (month in 1..12) {
                val day = LocalDate.of(2025, month, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                for (k in 0 until 48) {
                    val t = day.plusSeconds(k * 1800L + 900L)
                    val pos = SolarCalculator.position(ref, t)
                    if (!pos.isAboveHorizon) continue
                    val c = poaComponents(clear.irradiance(pos, t), pos, tiltDeg, azimuthDeg, extraterrestrialDni = ClearSkyModel.extraterrestrialIrradiance(t))
                    val iam = ashraeIam(cosIncidence(pos, tiltDeg, azimuthDeg), IAM_B0)
                    withIam += c.beam * iam + c.skyDiffuse + c.groundReflected
                    plain += c.total
                }
            }
            if (plain > 0) (withIam / plain).coerceIn(0.5, 1.0) else 1.0
        }
    }

    companion object {
        /** Panel power temperature coefficient [1/°C] (typical crystalline silicon). */
        const val TEMPERATURE_COEFFICIENT = -0.004

        /** Nominal operating cell temperature [°C] (800 W/m², 20 °C air). */
        const val NOCT = 45.0

        /**
         * Average temperature loss already contained in [PvSystem.performanceRatio]. When the
         * real air temperature is known, this part is replaced by the computed temperature factor.
         */
        const val TYPICAL_TEMPERATURE_FACTOR = 0.95

        /** Cell temperature (NOCT model) [°C]. */
        fun cellTemperatureC(poa: Double, ambientC: Double): Double = ambientC + poa / 800.0 * (NOCT - 20.0)

        /**
         * Cell temperature [°C]: Faiman (2008) T = Ta + POA / (U0 + U1·wind) with the PVsyst/pvlib free-standing
         * defaults U0 = 25 W/m²K, U1 = 6.84 W·s/m³K when the wind is known, otherwise the NOCT model.
         */
        fun cellTemperatureC(poa: Double, ambientC: Double, windMs: Double?): Double =
            PvSimulationEngine.cellTemperature(poa, ambientC, windMs)

        /** Multiplier for the performance ratio; 1.0 when the air temperature is unknown. */
        fun temperatureFactor(poa: Double, ambientC: Double?): Double =
            cellTemperatureFactor(ambientC?.let { cellTemperatureC(poa, it) }, TEMPERATURE_COEFFICIENT)

        /** (1 + γ·(Tcell − 25 °C)) relative to the typical loss inside the PR; 1.0 when the cell temperature is unknown. */
        fun cellTemperatureFactor(cellC: Double?, gamma: Double): Double {
            if (cellC == null) return 1.0
            return ((1.0 + gamma * (cellC - 25.0)) / TYPICAL_TEMPERATURE_FACTOR).coerceIn(0.5, 1.2)
        }

        /** ASHRAE incidence-angle coefficient of glass-covered modules (same default as the loss-chain engine). */
        const val IAM_B0 = PvArrayConfig.DEFAULT_IAM_B0

        /** Standard test conditions irradiance [W/m²] at which kWp is rated. */
        const val STC_IRRADIANCE = 1000.0
        const val DEFAULT_STEP_MINUTES = 5L
        const val MONTHLY_STEP_MINUTES = 10L
        /** Time step of the monthly tilt plan (91 angles × a whole year). */
        const val PLAN_STEP_MINUTES = 15L
        /** Typical rotation limit of single-axis trackers. */
        const val DEFAULT_TRACKER_ROTATION_DEG = 60.0
        val COMPARISON_TILTS = listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 90.0)
        val MONTHLY_TILTS = listOf(0.0, 30.0, 45.0, 60.0, 90.0)
    }
}

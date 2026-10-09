package com.solartracker.pro.core.live

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.energy.InstantFlow
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvPointEstimate
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.DayType
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SunTimes
import com.solartracker.pro.core.weather.WeatherSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything shown in Live Solar for one second. All values are MODELLED (no sensor),
 * kept in full double precision; rounding happens only in the UI.
 */
data class LiveSolarSnapshot(
    val instant: Instant,
    val zone: ZoneId,
    val location: GeoLocation,
    val system: PvSystem,
    val estimate: PvPointEstimate,
    val irradianceSource: WeatherSource,
    val todaySunTimes: SunTimes,
    val nextSunrise: Instant?,
    val nextSunset: Instant?,
    val flow: InstantFlow,
    val socPercent: Double?,
    val storedKwh: Double?,
) {
    val isDay: Boolean get() = estimate.sun.elevationDeg > 0.0
    val timeToSunrise: Duration? get() = nextSunrise?.let { Duration.between(instant, it) }
    val timeToSunset: Duration? get() = nextSunset?.let { Duration.between(instant, it) }
}

/**
 * Builds [LiveSolarSnapshot]s from the shared models: [SolarCalculator] (sun), the
 * [PvEstimator] with its irradiance model (power) and [EnergyFlowSimulator] (battery rules).
 * Purely local and cheap – meant to be called every second; it never touches the network.
 */
class LiveSolarCalculator(
    private val estimator: PvEstimator,
    private val simulator: EnergyFlowSimulator = EnergyFlowSimulator(estimator),
    private val sourceAt: (Instant) -> WeatherSource = { WeatherSource.CLEAR_SKY },
) {
    fun snapshot(
        instant: Instant,
        zone: ZoneId,
        location: GeoLocation,
        system: PvSystem,
        consumption: ConsumptionProfile,
        battery: BatteryStorage?,
        socPercent: Double?,
    ): LiveSolarSnapshot {
        val estimate = estimator.pointEstimate(system, location, instant)
        val loadKw = consumption.powerKwAtHour(instant.atZone(zone).hour)
        val soc = if (battery != null) (socPercent ?: battery.initialSocPercent).coerceIn(0.0, 100.0) else null
        val today = instant.atZone(zone).toLocalDate()
        return LiveSolarSnapshot(
            instant = instant,
            zone = zone,
            location = location,
            system = system.sanitized(),
            estimate = estimate,
            irradianceSource = sourceAt(instant),
            todaySunTimes = SolarCalculator.sunTimes(location, today),
            nextSunrise = nextEvent(location, instant, today) { it.sunrise },
            nextSunset = nextEvent(location, instant, today) { it.sunset },
            flow = simulator.instantFlow(estimate.powerKw, loadKw, battery, soc),
            socPercent = soc,
            storedKwh = if (battery != null && soc != null) battery.storedKwh(soc) else null,
        )
    }

    companion object {
        /**
         * First sunrise/sunset strictly after [instant], searching from yesterday to two days
         * ahead (covers time zones far from the solar day). Null during long polar periods.
         */
        fun nextEvent(location: GeoLocation, instant: Instant, today: LocalDate, pick: (SunTimes) -> Instant?): Instant? =
            (-1L..2L).asSequence()
                .mapNotNull { pick(SolarCalculator.sunTimes(location, today.plusDays(it))) }
                .filter { it.isAfter(instant) }
                .minOrNull()
    }
}

data class SunPathPoint(val time: Instant, val azimuthDeg: Double, val elevationDeg: Double)

/** The sun's path across the local calendar day. */
data class SunPath(
    val date: LocalDate,
    val points: List<SunPathPoint>,
    val sunTimes: SunTimes,
) {
    /** Highest point of the day (around solar noon). */
    val highest: SunPathPoint get() = points.maxBy { it.elevationDeg }
    val dayType: DayType get() = sunTimes.dayType

    companion object {
        fun forDay(location: GeoLocation, date: LocalDate, zone: ZoneId, stepMinutes: Long = 5): SunPath {
            require(stepMinutes in 1..60) { "stepMinutes must be 1..60" }
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val points = generateSequence(start) { it.plusSeconds(stepMinutes * 60) }
                .takeWhile { !it.isAfter(end) }
                .map { t -> SolarCalculator.position(location, t).let { SunPathPoint(t, it.azimuthDeg, it.elevationDeg) } }
                .toList()
            return SunPath(date, points, SolarCalculator.sunTimes(location, date))
        }
    }
}

/**
 * What the same model expects next: power in [AHEAD_MINUTES], today's maximum and the energy of the day split at
 * [computedAt]. Energy is integrated over time (midpoint rule, [STEP_MINUTES]) – never a sum of power samples.
 * ESTIMATE / FORECAST of the model, not a measurement.
 */
data class LiveOutlook(
    val computedAt: Instant,
    /** (minutes ahead, power [kW]). */
    val ahead: List<Pair<Long, Double>>,
    val todayMaxKw: Double,
    val todayMaxAt: Instant?,
    val soFarKwh: Double,
    val remainingKwh: Double,
) {
    val dayKwh: Double get() = soFarKwh + remainingKwh

    companion object {
        val AHEAD_MINUTES = listOf(5L, 15L, 30L, 60L)
        const val STEP_MINUTES = 5L

        fun compute(estimator: PvEstimator, system: PvSystem, location: GeoLocation, now: Instant, zone: ZoneId): LiveOutlook {
            val date = now.atZone(zone).toLocalDate()
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val profile = estimator.dailyProfile(system, location, date, zone, STEP_MINUTES)
            val max = profile.maxByOrNull { it.powerKw }
            return LiveOutlook(
                computedAt = now,
                ahead = AHEAD_MINUTES.map { m -> m to estimator.powerKw(system, location, now.plusSeconds(m * 60)) },
                todayMaxKw = max?.powerKw ?: 0.0,
                todayMaxAt = max?.takeIf { it.powerKw > 0.0 }?.time,
                soFarKwh = estimator.energyKwh(system, location, start, now, STEP_MINUTES),
                remainingKwh = estimator.energyKwh(system, location, now, end, STEP_MINUTES),
            )
        }
    }
}

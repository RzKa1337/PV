package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.EnsembleForecast
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Distribution of a forecast value over the ensemble members: P10 / P50 / P90 (10 % of the weather scenarios give
 * less than P10, 10 % more than P90 – an 80 % range), the mean and how many members it is based on.
 * Units are those of the caller: W for an hour, kWh for an energy.
 */
data class PvQuantiles(val p10: Double, val p50: Double, val p90: Double, val mean: Double, val members: Int) {
    val width: Double get() = p90 - p10

    /**
     * 80 % range relative to the median, with a floor so a dark day (median near zero) does not look "infinitely"
     * uncertain: [floor] is typically 15 % of the clear-sky value.
     */
    fun relativeWidth(floor: Double = 0.0): Double = width / maxOf(p50, floor, 1e-9)

    companion object {
        /** Quantiles of finite [values] (linear interpolation between order statistics, R type 7); null when empty. */
        fun of(values: DoubleArray): PvQuantiles? {
            val v = values.filter { it.isFinite() }.sorted()
            if (v.isEmpty()) return null
            return PvQuantiles(quantile(v, 0.1), quantile(v, 0.5), quantile(v, 0.9), v.average(), v.size)
        }

        /** [sorted] must be sorted ascending and non-empty. */
        fun quantile(sorted: List<Double>, q: Double): Double {
            val h = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
            val lo = floor(h).toInt()
            val hi = minOf(lo + 1, sorted.lastIndex)
            return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
        }

        /**
         * Confidence factor (0.4…1) from the ensemble spread: the weather models' own agreement. 80 % range = 20 % of
         * the median → 0.9, 60 % → 0.7, ≥ 120 % → 0.4.
         */
        fun spreadFactor(q: PvQuantiles, floor: Double): Double = (1.0 - 0.5 * q.relativeWidth(floor)).coerceIn(0.4, 1.0)
    }
}

/**
 * Expected PV of every ensemble member, hour by hour (mean power [W] over the hour = energy [Wh]). Night hours hold
 * zeros; an hour without enough members holds null. Members keep their position in every hour, so energies over
 * any period are summed PER MEMBER and only then turned into quantiles – summing hourly P90s would assume that every
 * hour is at its worst/best at once and give a range far too wide.
 */
class EnsemblePvResult(
    val model: String,
    val modelLabel: String,
    val memberCount: Int,
    val fetchedAt: Instant,
    val computedAt: Instant,
    private val starts: List<Instant>,
    private val values: List<DoubleArray?>,
) {
    init {
        require(starts.size == values.size) { "starts/values size mismatch" }
    }

    val coversUntil: Instant? get() = starts.indices.lastOrNull { values[it] != null }?.let { starts[it].plus(HOUR) }

    private fun index(start: Instant): Int = starts.binarySearch(start)

    /** Quantiles of the mean power [W] in the hour starting at [start]; null outside the ensemble or with too few members. */
    fun hour(start: Instant): PvQuantiles? {
        val i = index(start).takeIf { it >= 0 } ?: return null
        val v = values[i] ?: return null
        return PvQuantiles.of(v)?.takeIf { it.members >= MIN_MEMBERS }
    }

    /**
     * Quantiles of the energy [kWh] in [from, to) (partial hours by overlap). Null when any hour in the period is not
     * covered by the ensemble or fewer than [MIN_MEMBERS] members cover all of it.
     */
    fun energy(from: Instant, to: Instant): PvQuantiles? = memberEnergies(from, to)?.let { PvQuantiles.of(it) }?.takeIf { it.members >= MIN_MEMBERS }

    /** Share of weather scenarios with at least [kwh] in [from, to) (0…1); null without ensemble coverage. */
    fun probabilityAtLeast(from: Instant, to: Instant, kwh: Double): Double? {
        val e = memberEnergies(from, to)?.filter { it.isFinite() } ?: return null
        if (e.size < MIN_MEMBERS) return null
        return e.count { it >= kwh - 1e-9 }.toDouble() / e.size
    }

    fun day(date: LocalDate, zone: ZoneId): PvQuantiles? =
        energy(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())

    /** Per-member energy [kWh] in [from, to); NaN for a member missing any hour. */
    private fun memberEnergies(from: Instant, to: Instant): DoubleArray? {
        if (!to.isAfter(from)) return null
        val hourStart = { t: Instant -> starts.lastOrNull { !it.isAfter(t) } }
        val first = hourStart(from) ?: return null
        var i = index(first)
        if (i < 0) return null
        val sum = DoubleArray(memberCount)
        var t = first
        while (t.isBefore(to)) {
            if (i >= starts.size || starts[i] != t) return null // gap in the grid
            val v = values[i] ?: return null
            val a = maxOf(t, from)
            val b = minOf(t.plus(HOUR), to)
            val hours = Duration.between(a, b).toMillis() / 3_600_000.0
            for (m in 0 until memberCount) sum[m] += v.getOrElse(m) { Double.NaN } * hours / 1000.0
            i++
            t = t.plus(HOUR)
        }
        return sum
    }

    companion object {
        private val HOUR: Duration = Duration.ofHours(1)

        /** Fewer members than this in an hour or period is not reported as a distribution. */
        const val MIN_MEMBERS = 5
    }
}

/**
 * Runs the ONE PV prediction engine ([PredictivePvEngine]: irradiance → POA → cell temperature → calibration →
 * shading → inverter limit) once per ensemble member. Each member keeps the deterministic forecast's wind, snow and
 * precipitation (the ensemble provides irradiance, temperature and clouds only). No live nowcast is applied: the
 * ensemble describes the weather hours and days ahead, not the next minutes.
 *
 * Cost: members × daylight hours × 6 samples (≈ 40 × 100 × 6 for a week) – meant for a background thread, and the
 * shading ray test runs once per instant for all members (see [PredictivePvEngine.withWeather]).
 */
class EnsemblePvEngine(
    private val location: GeoLocation,
    private val deterministic: PredictivePvEngine,
    private val ensemble: EnsembleForecast,
    private val base: WeatherForecast?,
) {
    private val shadeCache = ConcurrentHashMap<Instant, Double>()

    private val engines: List<Pair<WeatherForecast, PredictivePvEngine>> = ensemble.members.map { member ->
        val merged = WeatherForecast(member.hours.map { merge(it) }, member.fetchedAt, member.zone)
        merged to deterministic.withWeather(WeatherAwareIrradianceModel(location, merged), shadeCache)
    }

    private fun merge(m: HourlyWeather): HourlyWeather {
        val b = base?.at(m.endTime)?.takeIf { it.endTime == m.endTime } ?: return m
        return b.copy(
            ghi = m.ghi, dni = m.dni, dhi = m.dhi,
            temperatureC = m.temperatureC ?: b.temperatureC,
            cloudCoverPercent = m.cloudCoverPercent ?: b.cloudCoverPercent,
            // Layered clouds, UV and the weather code describe the deterministic run's sky – not this member's.
            cloudLowPercent = null, cloudMidPercent = null, cloudHighPercent = null, uvIndex = null, weatherCode = null,
        )
    }

    /** Hours starting in [from, to) on the hourly grid starting at [from] (Instant arithmetic: DST-safe). */
    fun compute(from: Instant, to: Instant, now: Instant): EnsemblePvResult {
        val starts = generateSequence(from) { it.plus(Duration.ofHours(1)) }.takeWhile { it.isBefore(to) }.toList()
        val values = starts.map { start -> hour(start, now) }
        return EnsemblePvResult(ensemble.model, ensemble.label, engines.size, ensemble.fetchedAt, now, starts, values)
    }

    private fun hour(start: Instant, now: Instant): DoubleArray? {
        val mid = start.plusSeconds(1800)
        val night = listOf(start, mid, start.plusSeconds(3600)).all { SolarCalculator.position(location, it).elevationDeg < -1 }
        if (night) return DoubleArray(engines.size)
        // The same 10-minute grid as the deterministic hourly rows and the stored forecasts.
        val samples = (0 until 6).map { start.plusSeconds(300L + it * 600L) }
        var covered = 0
        val out = DoubleArray(engines.size) { m ->
            val (weather, engine) = engines[m]
            if (weather.at(mid)?.hasIrradiance != true) return@DoubleArray Double.NaN
            covered++
            samples.sumOf { engine.at(it, now, null).expectedKw } / samples.size * 1000
        }
        return out.takeIf { covered >= EnsemblePvResult.MIN_MEMBERS }
    }
}

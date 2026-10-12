package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.Irradiance
import com.solartracker.pro.core.pv.IrradianceModel
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset

/** Where the irradiance for a moment comes from. */
enum class WeatherSource { FORECAST, CLIMATE, CLEAR_SKY }

/**
 * Irradiance model using real weather where available:
 *
 * 1. [forecast] (hourly direct/diffuse irradiance and air temperature) for the hours it covers,
 * 2. otherwise [climate]: a two-state sky – a share of clear days and the rest overcast
 *    (diffuse light only) – mixed so the month's mean irradiation matches the climate data,
 * 3. otherwise plain clear sky.
 *
 * Used by the shared PvEstimator, so every estimate in the app follows the same weather.
 */
class WeatherAwareIrradianceModel(
    private val location: GeoLocation,
    private val forecast: WeatherForecast? = null,
    private val climate: MonthlyClimate? = null,
    private val clearSky: ClearSkyModel = ClearSkyModel(),
) : IrradianceModel {

    /** Typical / clear-sky irradiation ratio per month, computed once for this location. */
    val monthlyClearnessFactor: Map<Month, Double>? by lazy {
        climate?.let { c ->
            Month.entries.associateWith { month ->
                val clear = clearSkyDailyGhi(location, month, clearSky)
                if (clear <= 0.0) 0.0 else (c.dailyGhiKwhPerM2.getValue(month) / clear).coerceIn(0.0, 1.0)
            }
        }
    }

    /** When the forecast was downloaded (not when the provider issued it); null without a forecast. */
    val forecastFetchedAt: Instant? get() = forecast?.fetchedAt

    /** Forecast hour covering [instant] (wind, snow, humidity…), or null outside the forecast. */
    fun hourAt(instant: Instant): HourlyWeather? = forecast?.at(instant)

    fun sourceAt(instant: Instant): WeatherSource = when {
        forecast?.at(instant)?.hasIrradiance == true -> WeatherSource.FORECAST
        climate != null -> WeatherSource.CLIMATE
        else -> WeatherSource.CLEAR_SKY
    }

    override fun irradiance(position: SolarPosition, instant: Instant): Irradiance {
        val hour = forecast?.at(instant)
        if (hour != null && hour.hasIrradiance) {
            val temperature = interpolate(instant) { it.temperatureC }
            val wind = interpolate(instant) { it.windSpeedMs }
            if (!position.isAboveHorizon) return Irradiance(0.0, 0.0, temperature, wind)
            return irradianceIn(hour, position, instant).copy(ambientTemperatureC = temperature, windMs = wind)
        }
        val clear = clearSky.irradiance(position, instant)
        val c = climate ?: return clear
        val month = instant.atZone(ZoneOffset.UTC).month
        val temperature = hour?.temperatureC ?: c.meanTemperatureC[month]
        if (!position.isAboveHorizon) return Irradiance(0.0, 0.0, temperature)
        val factor = monthlyClearnessFactor?.get(month) ?: 1.0
        return mixClearAndOvercast(clear, position, factor).copy(ambientTemperatureC = temperature)
    }

    /** Clear-sky index and diffuse fraction of one forecast interval (cached: computed once per interval). */
    private class Shape(val clearSkyIndex: Double, val diffuseFraction: Double)

    /** One provider interval (start, end] with its mean beam (DNI) and diffuse (DHI) irradiance. */
    private class Interval(val start: Instant, val end: Instant, val dni: Double, val dhi: Double, val samples: Int)

    private val shapes = java.util.concurrent.ConcurrentHashMap<Long, Shape>()

    private fun HourlyWeather.interval(): Interval? = if (hasIrradiance) Interval(startTime, endTime, dni!!, dhi!!, SAMPLES_PER_HOUR) else null

    private fun IntervalIrradiance.interval(): Interval? = if (hasIrradiance) Interval(startTime, endTime, dni!!, dhi!!, SAMPLES_PER_QUARTER) else null

    /** Hourly and 15-minute shapes share the cache: the key carries the interval length. */
    private fun shape(i: Interval): Shape = shapes.getOrPut(i.end.epochSecond * 8 + i.samples) {
        // Provider values are means of the preceding interval: compare them with the clear-sky mean of the same interval.
        val seconds = java.time.Duration.between(i.start, i.end).seconds
        var clearSum = 0.0
        var cosSum = 0.0
        for (k in 0 until i.samples) {
            val t = i.start.plusSeconds((k * seconds + seconds / 2) / i.samples)
            val pos = SolarCalculator.position(location, t)
            if (pos.isAboveHorizon) {
                clearSum += clearSky.irradiance(pos, t).ghi(pos)
                cosSum += kotlin.math.cos(Math.toRadians(pos.zenithDeg))
            }
        }
        val clearMean = clearSum / i.samples
        // Interval-mean GHI from the same components the model uses (DNI·cos z + DHI), so beam and diffuse stay consistent.
        val ghi = i.dni * cosSum / i.samples + i.dhi
        val k = if (clearMean > MIN_CLEAR_GHI) (ghi / clearMean).coerceIn(0.0, MAX_CLEAR_SKY_INDEX) else if (ghi > 0) 1.0 else 0.0
        val d = if (ghi > 1.0) (i.dhi / ghi).coerceIn(0.0, 1.0) else 1.0
        Shape(k, d)
    }

    /**
     * Irradiance at [instant] inside a provider interval (15-minute where the forecast has it, otherwise hourly).
     * Interval means applied as a constant step put the interval's beam on the wrong sun angles (worst around sunrise
     * and sunset) and make the curve jump at the boundaries. Instead the clear-sky index and the diffuse fraction are
     * interpolated between interval centres and applied to the clear-sky irradiance of this very moment: the energy is
     * kept, the shape follows the sun.
     */
    private fun intraInterval(here: Interval, neighbour: (Instant) -> Interval?, position: SolarPosition, instant: Instant): Irradiance {
        val half = java.time.Duration.between(here.start, here.end).dividedBy(2)
        val center = here.start.plus(half)
        val other = neighbour(if (instant.isBefore(center)) here.start else here.end.plusSeconds(1))?.let(::shape)
        val w = if (other == null) 0.0 else kotlin.math.abs(java.time.Duration.between(center, instant).seconds) / (2.0 * half.seconds)
        val s = shape(here)
        val k = s.clearSkyIndex * (1 - w) + (other?.clearSkyIndex ?: 0.0) * w
        val d = s.diffuseFraction * (1 - w) + (other?.diffuseFraction ?: 0.0) * w
        val clear = clearSky.irradiance(position, instant)
        val ghi = k * clear.ghi(position)
        val dhi = d * ghi
        val cosZ = kotlin.math.cos(Math.toRadians(position.zenithDeg))
        val dni = if (cosZ > MIN_COS_ZENITH) ((ghi - dhi) / cosZ).coerceIn(0.0, ClearSkyModel.extraterrestrialIrradiance(instant)) else 0.0
        return Irradiance(dni, dhi)
    }

    /** 15-minute interval when the forecast has one for [instant], otherwise the hourly one. */
    private fun irradianceIn(hour: HourlyWeather, position: SolarPosition, instant: Instant): Irradiance {
        val f = forecast!!
        f.quarterAt(instant)?.interval()?.let { q -> return intraInterval(q, { t -> f.quarterAt(t)?.interval() }, position, instant) }
        val h = hour.interval() ?: return Irradiance(hour.dni!!, hour.dhi!!)
        return intraInterval(h, { t -> f.at(t)?.interval() }, position, instant)
    }

    /** True when 15-minute irradiance is used at [instant]. */
    fun usesQuarterHour(instant: Instant): Boolean = forecast?.quarterAt(instant) != null

    /** Instantaneous provider variable (valid at the hour's end time), linear between neighbouring hours. */
    private fun interpolate(instant: Instant, value: (HourlyWeather) -> Double?): Double? {
        val hour = forecast?.at(instant) ?: return null
        val end = value(hour)
        val prev = forecast.at(hour.startTime)?.let(value) ?: return end
        if (end == null) return prev
        val w = java.time.Duration.between(hour.startTime, instant).seconds / 3600.0
        return prev + (end - prev) * w.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val SAMPLES_PER_HOUR = 6
        private const val SAMPLES_PER_QUARTER = 3
        /** Below this clear-sky mean [W/m²] (sun just rising/setting) the index is not reliable. */
        private const val MIN_CLEAR_GHI = 10.0
        /** Hourly means above clear sky happen (cloud enhancement), but rarely by more than ~20 %. */
        const val MAX_CLEAR_SKY_INDEX = 1.2
        /** Beam is not derived from GHI − DHI below ~3° of sun elevation (division by a tiny cos zenith). */
        private const val MIN_COS_ZENITH = 0.05

        /** Mean daily clear-sky irradiation [kWh/m²/day] on a horizontal plane for [month]. */
        fun clearSkyDailyGhi(location: GeoLocation, month: Month, model: ClearSkyModel = ClearSkyModel()): Double {
            val year = 2025 // non-leap reference year; the result barely depends on the year
            val days = (1..month.length(false) step 3).toList()
            var totalWh = 0.0
            for (d in days) {
                val start = LocalDate.of(year, month, d).atStartOfDay(ZoneOffset.UTC).toInstant()
                for (step in 0 until 96) { // 15-minute steps, midpoint rule
                    val t = start.plusSeconds(step * 900L + 450L)
                    val pos = SolarCalculator.position(location, t)
                    if (pos.isAboveHorizon) totalWh += model.irradiance(pos, t).ghi(pos) * 0.25
                }
            }
            return totalWh / days.size / 1000.0
        }

        /** Global irradiance of an overcast sky relative to clear sky (all diffuse). */
        const val OVERCAST_GHI_RATIO = 0.25

        /**
         * Expected irradiance for a month whose mean irradiation is [factor] × clear sky.
         *
         * The month is modelled as a share `p` of clear days and `1 − p` overcast days with
         * [OVERCAST_GHI_RATIO] × clear-sky GHI of purely diffuse light, where
         * `p = (factor − r) / (1 − r)`. Because PV power is linear in irradiance, using the expected
         * value gives the right mean energy and keeps the tilt gain of clear days, which a uniform
         * "hazy every day" scaling would hide.
         */
        fun mixClearAndOvercast(clear: Irradiance, position: SolarPosition, factor: Double): Irradiance {
            val k = factor.coerceIn(0.0, 1.0)
            val r = OVERCAST_GHI_RATIO
            val clearShare = ((k - r) / (1.0 - r)).coerceIn(0.0, 1.0)
            // Below r every day is overcast and darker than a typical overcast day.
            val overcastGhi = clear.ghi(position) * if (k < r) k else r
            return Irradiance(
                dni = clear.dni * clearShare,
                dhi = clear.dhi * clearShare + overcastGhi * (1.0 - clearShare),
                ambientTemperatureC = clear.ambientTemperatureC,
            )
        }
    }
}

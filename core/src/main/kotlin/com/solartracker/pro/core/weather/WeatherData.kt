package com.solartracker.pro.core.weather

import com.solartracker.pro.core.solar.GeoLocation
import java.time.Duration
import java.time.Instant
import java.time.Month

/**
 * Weather for one hour ending at [endTime] (Open-Meteo convention: radiation values are
 * the mean of the preceding hour). Irradiance in W/m², null when not provided.
 */
data class HourlyWeather(
    val endTime: Instant,
    val ghi: Double?,
    val dni: Double?,
    val dhi: Double?,
    val temperatureC: Double?,
    val cloudCoverPercent: Double?,
    /** Wind speed at 10 m [m/s]. */
    val windSpeedMs: Double? = null,
    val relativeHumidityPercent: Double? = null,
    /** Precipitation in the hour [mm]. */
    val precipitationMm: Double? = null,
    /** Snow depth on the ground [m]. */
    val snowDepthM: Double? = null,
    val visibilityM: Double? = null,
    /** Cloud cover by layer [%]: low (< 2 km), mid (2–6 km), high (> 6 km, often thin cirrus). */
    val cloudLowPercent: Double? = null,
    val cloudMidPercent: Double? = null,
    val cloudHighPercent: Double? = null,
    /** UV index forecast (with clouds) and for a cloudless sky. */
    val uvIndex: Double? = null,
    val uvIndexClearSky: Double? = null,
    /** Maximum wind gust in the hour at 10 m [m/s]. */
    val windGustsMs: Double? = null,
    /** Dew point at 2 m as reported by the provider [°C]; null → compute with [Psychrometrics.dewPointC]. */
    val dewPointC: Double? = null,
    /** "Feels like" temperature reported by the provider [°C]. */
    val apparentTemperatureC: Double? = null,
    /** Probability of precipitation ≥ 0.1 mm in the hour [%] (model ensemble based). */
    val precipitationProbabilityPercent: Double? = null,
    /** Direction the wind comes FROM [° from north]. */
    val windDirectionDeg: Double? = null,
    /** Liquid rain in the hour [mm] and snowfall [cm] (subsets of [precipitationMm]). */
    val rainMm: Double? = null,
    val snowfallCm: Double? = null,
    /** WMO weather interpretation code (0 = clear … 95–99 = thunderstorm). */
    val weatherCode: Int? = null,
) {
    val startTime: Instant get() = endTime.minus(Duration.ofHours(1))
    val hasIrradiance: Boolean get() = dni != null && dhi != null
}

/**
 * Hourly forecast, sorted by time.
 * @property zone time zone of the forecast location (from the provider); null when unknown (old cache)
 * @property latitude/longitude grid point the provider used, when reported
 */
class WeatherForecast(
    hours: List<HourlyWeather>,
    val fetchedAt: Instant,
    val zone: java.time.ZoneId? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hours: List<HourlyWeather> = hours.sortedBy { it.endTime }

    val coversFrom: Instant? get() = hours.firstOrNull { it.hasIrradiance }?.startTime
    val coversUntil: Instant? get() = hours.lastOrNull { it.hasIrradiance }?.endTime

    /** The hour whose interval (start, end] contains [instant], or null. */
    fun at(instant: Instant): HourlyWeather? {
        var lo = 0
        var hi = hours.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val h = hours[mid]
            when {
                !instant.isAfter(h.startTime) -> hi = mid - 1
                instant.isAfter(h.endTime) -> lo = mid + 1
                else -> return h
            }
        }
        return null
    }
}

enum class ClimateSource { ARCHIVE, DEFAULT_POLAND }

/**
 * Typical monthly climate: mean daily global horizontal irradiation [kWh/m²/day]
 * and mean air temperature [°C].
 */
data class MonthlyClimate(
    val dailyGhiKwhPerM2: Map<Month, Double>,
    val meanTemperatureC: Map<Month, Double>,
    val source: ClimateSource,
    val years: Int = 0,
) {
    init {
        require(dailyGhiKwhPerM2.keys.containsAll(Month.entries)) { "GHI for every month required" }
    }

    companion object {
        /**
         * Approximate long-term averages for central Poland (Warsaw area), used offline when no
         * archive data is available. Monthly sums [kWh/m²]: 21, 37, 77, 120, 156, 160, 162, 140,
         * 92, 54, 23, 15 (≈ 1060 kWh/m² per year).
         */
        val DEFAULT_POLAND: MonthlyClimate = run {
            val monthlySums = listOf(21.0, 37.0, 77.0, 120.0, 156.0, 160.0, 162.0, 140.0, 92.0, 54.0, 23.0, 15.0)
            val temps = listOf(-2.0, -1.0, 3.0, 9.0, 14.0, 17.0, 19.0, 19.0, 14.0, 9.0, 4.0, 0.0)
            MonthlyClimate(
                dailyGhiKwhPerM2 = Month.entries.associateWith { monthlySums[it.ordinal] / it.length(false) },
                meanTemperatureC = Month.entries.associateWith { temps[it.ordinal] },
                source = ClimateSource.DEFAULT_POLAND,
            )
        }

        /** Whether [DEFAULT_POLAND] is a reasonable fallback for [location]. */
        fun defaultFor(location: GeoLocation): MonthlyClimate? =
            DEFAULT_POLAND.takeIf { location.latitude in 48.5..55.5 && location.longitude in 13.5..25.0 }
    }
}

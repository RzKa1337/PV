package com.solartracker.pro.core.weather

import com.solartracker.pro.core.solar.GeoLocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.util.Locale

/**
 * Open-Meteo (https://open-meteo.com, CC BY 4.0) request URLs and response parsing.
 * No API key is needed. Networking itself lives in the app module.
 */
object OpenMeteo {

    const val ATTRIBUTION = "Dane pogodowe: Open-Meteo.com (CC BY 4.0)"

    private const val HOURLY_VARIABLES =
        "temperature_2m,cloud_cover,shortwave_radiation,direct_normal_irradiance,diffuse_radiation," +
            "wind_speed_10m,relative_humidity_2m,precipitation,snow_depth,visibility," +
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high,uv_index,uv_index_clear_sky,wind_gusts_10m"

    fun forecastUrl(location: GeoLocation, forecastDays: Int = 16, pastDays: Int = 2): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${coord(location.latitude)}" +
            "&longitude=${coord(location.longitude)}&hourly=$HOURLY_VARIABLES" +
            "&forecast_days=$forecastDays&past_days=$pastDays&wind_speed_unit=ms&timezone=GMT&timeformat=unixtime"

    /** Daily irradiation and temperature for whole calendar years [firstYear]..[lastYear]. */
    fun climateUrl(location: GeoLocation, firstYear: Int, lastYear: Int): String =
        "https://archive-api.open-meteo.com/v1/archive?latitude=${coord(location.latitude)}" +
            "&longitude=${coord(location.longitude)}&start_date=$firstYear-01-01&end_date=$lastYear-12-31" +
            "&daily=shortwave_radiation_sum,temperature_2m_mean&timezone=GMT&timeformat=unixtime"

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /** @throws IllegalArgumentException when the response is not a valid forecast */
    fun parseForecast(json: String, fetchedAt: Instant): WeatherForecast {
        val hourly = root(json)["hourly"]?.jsonObject ?: throw IllegalArgumentException("Missing 'hourly'")
        val time = hourly.longs("time")
        val ghi = hourly.doubles("shortwave_radiation", time.size)
        val dni = hourly.doubles("direct_normal_irradiance", time.size)
        val dhi = hourly.doubles("diffuse_radiation", time.size)
        val temp = hourly.doubles("temperature_2m", time.size)
        val cloud = hourly.doubles("cloud_cover", time.size)
        val wind = hourly.doubles("wind_speed_10m", time.size)
        val gusts = hourly.doubles("wind_gusts_10m", time.size)
        val humidity = hourly.doubles("relative_humidity_2m", time.size)
        val precipitation = hourly.doubles("precipitation", time.size)
        val snow = hourly.doubles("snow_depth", time.size)
        val visibility = hourly.doubles("visibility", time.size)
        val cloudLow = hourly.doubles("cloud_cover_low", time.size)
        val cloudMid = hourly.doubles("cloud_cover_mid", time.size)
        val cloudHigh = hourly.doubles("cloud_cover_high", time.size)
        val uv = hourly.doubles("uv_index", time.size)
        val uvClear = hourly.doubles("uv_index_clear_sky", time.size)
        val hours = time.mapIndexedNotNull { i, t ->
            t ?: return@mapIndexedNotNull null
            HourlyWeather(
                endTime = Instant.ofEpochSecond(t),
                ghi = ghi[i]?.coerceAtLeast(0.0),
                dni = dni[i]?.coerceAtLeast(0.0),
                dhi = dhi[i]?.coerceAtLeast(0.0),
                temperatureC = temp[i],
                cloudCoverPercent = cloud[i]?.coerceIn(0.0, 100.0),
                windSpeedMs = wind[i]?.coerceAtLeast(0.0),
                windGustsMs = gusts[i]?.coerceAtLeast(0.0),
                relativeHumidityPercent = humidity[i]?.coerceIn(0.0, 100.0),
                precipitationMm = precipitation[i]?.coerceAtLeast(0.0),
                snowDepthM = snow[i]?.coerceAtLeast(0.0),
                visibilityM = visibility[i]?.coerceAtLeast(0.0),
                cloudLowPercent = cloudLow[i]?.coerceIn(0.0, 100.0),
                cloudMidPercent = cloudMid[i]?.coerceIn(0.0, 100.0),
                cloudHighPercent = cloudHigh[i]?.coerceIn(0.0, 100.0),
                uvIndex = uv[i]?.coerceAtLeast(0.0),
                uvIndexClearSky = uvClear[i]?.coerceAtLeast(0.0),
            )
        }
        require(hours.isNotEmpty()) { "Empty forecast" }
        return WeatherForecast(hours, fetchedAt)
    }

    /**
     * Averages daily archive values per calendar month.
     * shortwave_radiation_sum is in MJ/m² per day (1 kWh = 3.6 MJ).
     */
    fun parseClimate(json: String): MonthlyClimate {
        val daily = root(json)["daily"]?.jsonObject ?: throw IllegalArgumentException("Missing 'daily'")
        val time = daily.longs("time")
        val radiation = daily.doubles("shortwave_radiation_sum", time.size)
        val temperature = daily.doubles("temperature_2m_mean", time.size)
        val ghiByMonth = HashMap<Month, MutableList<Double>>()
        val tempByMonth = HashMap<Month, MutableList<Double>>()
        val years = HashSet<Int>()
        time.forEachIndexed { i, t ->
            t ?: return@forEachIndexed
            val date = LocalDate.ofEpochDay(Math.floorDiv(t, 86_400L))
            years += date.year
            radiation[i]?.let { ghiByMonth.getOrPut(date.month) { mutableListOf() } += it / 3.6 }
            temperature[i]?.let { tempByMonth.getOrPut(date.month) { mutableListOf() } += it }
        }
        require(Month.entries.all { (ghiByMonth[it]?.size ?: 0) >= 5 }) { "Not enough data for every month" }
        return MonthlyClimate(
            dailyGhiKwhPerM2 = Month.entries.associateWith { ghiByMonth.getValue(it).average() },
            meanTemperatureC = Month.entries.associateWith { m -> tempByMonth[m]?.average() ?: 10.0 },
            source = ClimateSource.ARCHIVE,
            years = years.size,
        )
    }

    private fun root(json: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON", e)
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("JSON object expected")
        if ((obj["error"] as? JsonPrimitive)?.content == "true") {
            throw IllegalArgumentException("API error: ${(obj["reason"] as? JsonPrimitive)?.content}")
        }
        return obj
    }

    private fun JsonObject.array(name: String): JsonArray =
        this[name]?.jsonArray ?: throw IllegalArgumentException("Missing '$name'")

    private fun JsonObject.longs(name: String): List<Long?> = array(name).map { it.primitiveOrNull()?.longOrNull }

    /** Missing variable → all null; shorter arrays are padded with null. */
    private fun JsonObject.doubles(name: String, size: Int): List<Double?> {
        val values = this[name]?.jsonArray?.map { it.primitiveOrNull()?.doubleOrNull } ?: emptyList()
        return List(size) { values.getOrNull(it) }
    }

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = if (this is JsonNull) null else this as? JsonPrimitive

    /** Utility for tests and callers: epoch-day start of [date] in UTC. */
    internal fun epochSecond(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
}

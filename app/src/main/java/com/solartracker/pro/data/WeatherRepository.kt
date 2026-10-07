package com.solartracker.pro.data

import com.solartracker.pro.i18n.tr
import android.content.Context
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.MonthlyClimate
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.WeatherForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** Weather data available for the calculations. Either part may be missing. */
data class WeatherResult(
    val forecast: WeatherForecast?,
    val climate: MonthlyClimate?,
    /** Human-readable (Polish) problem, e.g. no internet; data may still come from the cache. */
    val error: String? = null,
)

/** Source of weather data; abstracted so the ViewModel can be unit tested. */
interface WeatherProvider {
    suspend fun load(location: GeoLocation, forceRefresh: Boolean): WeatherResult
}

/**
 * Downloads the Open-Meteo forecast and archive climate and caches them in app storage,
 * so the last data is available offline. No API key is used.
 */
class WeatherRepository(
    context: Context,
    private val clock: () -> Instant = { Instant.now() },
) : WeatherProvider {

    private val dir = File(context.applicationContext.filesDir, "weather").apply { mkdirs() }

    override suspend fun load(location: GeoLocation, forceRefresh: Boolean): WeatherResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val forecast = loadForecast(location, forceRefresh, errors)
        val climate = loadClimate(location, errors)
        WeatherResult(forecast, climate, errors.firstOrNull())
    }

    /** Cached data only (no network) – for the home-screen widget. */
    fun cachedOnly(location: GeoLocation): WeatherResult {
        val forecast = readCache(FORECAST, location, FORECAST_MATCH_DEG)?.takeIf { it.age() < FORECAST_STALE_LIMIT }?.let(::parseForecastOrNull)
        val climate = readCache(CLIMATE, location, CLIMATE_MATCH_DEG)?.let { runCatching { OpenMeteo.parseClimate(it.json) }.getOrNull() }
            ?: MonthlyClimate.defaultFor(location)
        return WeatherResult(forecast, climate, null)
    }

    private fun loadForecast(location: GeoLocation, force: Boolean, errors: MutableList<String>): WeatherForecast? {
        val cached = readCache(FORECAST, location, FORECAST_MATCH_DEG)
        if (!force && cached != null && cached.age() < FORECAST_MAX_AGE) {
            parseForecastOrNull(cached)?.let { return it }
        }
        return try {
            val json = download(OpenMeteo.forecastUrl(location))
            val forecast = OpenMeteo.parseForecast(json, clock())
            writeCache(FORECAST, location, json)
            forecast
        } catch (e: Exception) {
            val stale = cached?.takeIf { it.age() < FORECAST_STALE_LIMIT }?.let(::parseForecastOrNull)
            errors += if (stale != null) {
                tr("Brak aktualnej prognozy (${reason(e)}). Używam zapisanej.", "No current forecast (${reason(e)}). Using the saved one.")
            } else {
                tr("Nie udało się pobrać prognozy pogody (${reason(e)}).", "Could not download the weather forecast (${reason(e)}).")
            }
            stale
        }
    }

    private fun loadClimate(location: GeoLocation, errors: MutableList<String>): MonthlyClimate? {
        val cached = readCache(CLIMATE, location, CLIMATE_MATCH_DEG)
        if (cached != null && cached.age() < CLIMATE_MAX_AGE) {
            runCatching { OpenMeteo.parseClimate(cached.json) }.getOrNull()?.let { return it }
        }
        return try {
            val lastYear = clock().atZone(ZoneOffset.UTC).year - 1
            val json = download(OpenMeteo.climateUrl(location, lastYear - CLIMATE_YEARS + 1, lastYear), CLIMATE_TIMEOUT_MS)
            val climate = OpenMeteo.parseClimate(json)
            writeCache(CLIMATE, location, json)
            climate
        } catch (e: Exception) {
            val fallback = cached?.let { runCatching { OpenMeteo.parseClimate(it.json) }.getOrNull() }
                ?: MonthlyClimate.defaultFor(location)
            if (fallback == null) errors += tr("Nie udało się pobrać danych klimatycznych (${reason(e)}).", "Could not download climate data (${reason(e)}).")
            fallback
        }
    }

    private fun parseForecastOrNull(cache: CacheEntry): WeatherForecast? =
        runCatching { OpenMeteo.parseForecast(cache.json, cache.fetchedAt) }.getOrNull()

    private fun download(url: String, timeoutMs: Int = TIMEOUT_MS): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> tr("brak internetu", "no internet")
        is java.net.SocketTimeoutException -> tr("przekroczono czas", "timed out")
        else -> e.message ?: e.javaClass.simpleName
    }

    private class CacheEntry(val latitude: Double, val longitude: Double, val fetchedAt: Instant, val json: String, private val now: Instant) {
        fun age(): Duration = Duration.between(fetchedAt, now)
    }

    private fun readCache(name: String, location: GeoLocation, matchDeg: Double): CacheEntry? = runCatching {
        val meta = File(dir, "$name.meta").readText().split(';')
        val entry = CacheEntry(meta[0].toDouble(), meta[1].toDouble(), Instant.ofEpochSecond(meta[2].toLong()), File(dir, "$name.json").readText(), clock())
        entry.takeIf { abs(it.latitude - location.latitude) <= matchDeg && abs(it.longitude - location.longitude) <= matchDeg }
    }.getOrNull()

    private fun writeCache(name: String, location: GeoLocation, json: String) {
        runCatching {
            File(dir, "$name.json").writeText(json)
            File(dir, "$name.meta").writeText("${location.latitude};${location.longitude};${clock().epochSecond}")
        }
    }

    companion object {
        private const val FORECAST = "forecast"
        private const val CLIMATE = "climate"
        private const val FORECAST_MATCH_DEG = 0.05
        private const val CLIMATE_MATCH_DEG = 0.1
        private const val CLIMATE_YEARS = 3
        private const val TIMEOUT_MS = 15_000
        private const val CLIMATE_TIMEOUT_MS = 30_000
        val FORECAST_MAX_AGE: Duration = Duration.ofHours(1)
        val FORECAST_STALE_LIMIT: Duration = Duration.ofDays(3)
        val CLIMATE_MAX_AGE: Duration = Duration.ofDays(180)
    }
}

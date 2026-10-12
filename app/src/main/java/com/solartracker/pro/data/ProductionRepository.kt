package com.solartracker.pro.data

import android.content.Context
import com.solartracker.pro.core.analytics.ManualProductionCodec
import com.solartracker.pro.core.analytics.ManualProductionEntry
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.WeatherForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Production readings typed in by the user (a small text file in app storage) and the hourly weather of the
 * days they cover (Open-Meteo archive, cached), so the model can be compared with what really happened.
 */
class ProductionRepository(
    private val dir: File,
    private val clock: () -> Instant = { Instant.now() },
    private val download: (String) -> String = { WeatherRepository.download(it, ARCHIVE_TIMEOUT_MS) },
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "production"))

    init {
        dir.mkdirs()
    }

    private val entriesFile = File(dir, "entries.txt")

    suspend fun entries(): List<ManualProductionEntry> = withContext(Dispatchers.IO) {
        runCatching { ManualProductionCodec.decode(entriesFile.readText()) }.getOrDefault(emptyList())
    }

    /** @return false when the file could not be written (the caller keeps showing the unsaved state as an error) */
    suspend fun save(entries: List<ManualProductionEntry>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Write next to the file and rename, so a crash never leaves half a list.
            val tmp = File(dir, "entries.tmp")
            tmp.writeText(ManualProductionCodec.encode(entries))
            if (!tmp.renameTo(entriesFile)) throw java.io.IOException("rename failed")
        }.isSuccess
    }

    /**
     * Hourly weather for [from]…[to] at [location]: the cached answer when it covers the range and is recent,
     * otherwise a fresh download. Null when neither is available – the caller then compares with the climate.
     */
    suspend fun weather(location: GeoLocation, from: LocalDate, to: LocalDate): WeatherForecast? = withContext(Dispatchers.IO) {
        val cached = readCache(location)
        if (cached != null && cached.covers(from, to) && Duration.between(cached.fetchedAt, clock()) < CACHE_MAX_AGE) {
            parse(cached)?.let { return@withContext it }
        }
        try {
            val json = download(OpenMeteo.archiveHourlyUrl(location, from, to))
            val forecast = OpenMeteo.parseForecast(json, clock())
            runCatching {
                File(dir, "archive.json").writeText(json)
                File(dir, "archive.meta").writeText("${location.latitude};${location.longitude};$from;$to;${clock().epochSecond}")
            }
            forecast
        } catch (e: Exception) {
            // Offline: an older copy still covering the days is better than no weather at all.
            cached?.takeIf { it.covers(from, to) }?.let(::parse)
        }
    }

    private class Cache(val from: LocalDate, val to: LocalDate, val fetchedAt: Instant, val json: String) {
        fun covers(a: LocalDate, b: LocalDate) = !from.isAfter(a) && !to.isBefore(b)
    }

    private fun parse(cache: Cache): WeatherForecast? = runCatching { OpenMeteo.parseForecast(cache.json, cache.fetchedAt) }.getOrNull()

    private fun readCache(location: GeoLocation): Cache? = runCatching {
        val meta = File(dir, "archive.meta").readText().split(';')
        if (abs(meta[0].toDouble() - location.latitude) > MATCH_DEG || abs(meta[1].toDouble() - location.longitude) > MATCH_DEG) return null
        Cache(LocalDate.parse(meta[2]), LocalDate.parse(meta[3]), Instant.ofEpochSecond(meta[4].toLong()), File(dir, "archive.json").readText())
    }.getOrNull()

    companion object {
        private const val MATCH_DEG = 0.05
        private const val ARCHIVE_TIMEOUT_MS = 30_000
        /** The archive revises its most recent days; older days do not change. */
        val CACHE_MAX_AGE: Duration = Duration.ofHours(12)
    }
}

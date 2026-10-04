package com.solartracker.pro.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.Month
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Polish number/time formatting helpers. */
object Format {
    val locale: Locale = Locale.forLanguageTag("pl-PL")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun decimal(value: Double, digits: Int = 2): String = String.format(locale, "%.${digits}f", value)

    fun kwh(value: Double): String = "${decimal(value, if (value >= 100) 0 else 2)} kWh"

    fun kw(value: Double): String = "${decimal(value)} kW"

    fun degrees(value: Double, digits: Int = 0): String = "${decimal(value, digits)}°"

    fun time(instant: Instant?, zone: ZoneId): String =
        instant?.atZone(zone)?.format(timeFormatter) ?: "—"

    fun duration(duration: Duration): String {
        val minutes = duration.toMinutes()
        return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }

    fun monthName(month: Month): String =
        month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }

    fun monthShort(month: Month): String =
        month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }

    /** Compass direction (N, NE, E, ...) for an azimuth in degrees. */
    fun compass(azimuthDeg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((azimuthDeg % 360 + 360) % 360) / 45.0).roundToInt() % 8
        return names[index]
    }

    fun coordinates(latitude: Double, longitude: Double): String {
        val ns = if (latitude >= 0) "N" else "S"
        val ew = if (longitude >= 0) "E" else "W"
        return "${decimal(abs(latitude), 4)}°$ns, ${decimal(abs(longitude), 4)}°$ew"
    }

    /** Parses user input accepting both comma and dot as decimal separator. */
    fun parseDecimal(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

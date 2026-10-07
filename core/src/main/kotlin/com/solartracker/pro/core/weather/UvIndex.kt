package com.solartracker.pro.core.weather

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** WHO UV index categories with Polish labels and sun-protection advice. */
enum class UvLevel(val label: String, val advice: String) {
    LOW("niski", "Ochrona zwykle niepotrzebna"),
    MODERATE("umiarkowany", "Okulary i krem z filtrem przy dłuższym przebywaniu na słońcu"),
    HIGH("wysoki", "Krem SPF 30+, nakrycie głowy, w południe szukaj cienia"),
    VERY_HIGH("bardzo wysoki", "Unikaj słońca 11–16, krem SPF 50, odzież ochronna"),
    EXTREME("ekstremalny", "Unikaj przebywania na słońcu, pełna ochrona"),
    ;

    companion object {
        fun of(uv: Double): UvLevel = when {
            uv < 3 -> LOW
            uv < 6 -> MODERATE
            uv < 8 -> HIGH
            uv < 11 -> VERY_HIGH
            else -> EXTREME
        }
    }
}

/** UV now and today's maximum from the forecast (FORECAST data; null when Open-Meteo provides none). */
data class UvSummary(
    val now: Double?,
    val nowClearSky: Double?,
    val todayMax: Double?,
    val todayMaxAt: Instant?,
) {
    val levelNow: UvLevel? get() = now?.let { UvLevel.of(it) }
    val levelMax: UvLevel? get() = todayMax?.let { UvLevel.of(it) }
}

object UvIndex {
    fun summary(forecast: WeatherForecast?, now: Instant, zone: ZoneId): UvSummary? {
        forecast ?: return null
        val hour = forecast.at(now)
        val today: LocalDate = now.atZone(zone).toLocalDate()
        val max = forecast.hours.filter { it.uvIndex != null && it.endTime.atZone(zone).toLocalDate() == today }.maxByOrNull { it.uvIndex!! }
        if (hour?.uvIndex == null && max == null) return null
        return UvSummary(hour?.uvIndex, hour?.uvIndexClearSky, max?.uvIndex, max?.endTime)
    }
}

package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import java.time.LocalDate
import java.time.ZoneId

/**
 * How much the weather (mostly clouds) changes a day's production compared with a cloudless sky.
 * Both values are model estimates; [source] says whether the day is covered by the forecast or only by
 * monthly climate averages.
 */
data class CloudImpact(
    val date: LocalDate,
    val clearSkyKwh: Double,
    val weatherKwh: Double,
    val source: WeatherSource,
) {
    /** Reduction versus clear sky [%] (0 = cloudless); null when there is no meaningful clear-sky energy. */
    val reductionPercent: Double? get() = if (clearSkyKwh < 0.05) null else ((1 - weatherKwh / clearSkyKwh) * 100).coerceIn(0.0, 100.0)

    fun describe(): String {
        val r = reductionPercent ?: return "Brak słońca w tym dniu"
        val what = when (source) {
            WeatherSource.FORECAST -> "według prognozy pogody"
            WeatherSource.CLIMATE -> "średnio w tym miesiącu (brak prognozy)"
            WeatherSource.CLEAR_SKY -> "brak danych pogodowych"
        }
        return when {
            source == WeatherSource.CLEAR_SKY -> "Liczone dla bezchmurnego nieba – $what"
            r < 5 -> "Prawie bezchmurnie – $what"
            else -> "Chmury obniżają produkcję o ok. ${r.toInt()}% – $what"
        }
    }
}

object CloudImpactCalculator {
    private val clear = PvEstimator()

    fun day(system: PvSystem, location: GeoLocation, date: LocalDate, zone: ZoneId, weather: WeatherAwareIrradianceModel?): CloudImpact {
        val clearKwh = clear.dailyEnergyKwh(system, location, date, zone, stepMinutes = 15)
        if (weather == null) return CloudImpact(date, clearKwh, clearKwh, WeatherSource.CLEAR_SKY)
        val weatherKwh = PvEstimator(weather).dailyEnergyKwh(system, location, date, zone, stepMinutes = 15)
        val noon = date.atTime(12, 0).atZone(zone).toInstant()
        return CloudImpact(date, clearKwh, weatherKwh, weather.sourceAt(noon))
    }
}

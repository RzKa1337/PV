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
            if (!position.isAboveHorizon) return Irradiance(0.0, 0.0, hour.temperatureC)
            return Irradiance(hour.dni!!, hour.dhi!!, hour.temperatureC)
        }
        val clear = clearSky.irradiance(position, instant)
        val c = climate ?: return clear
        val month = instant.atZone(ZoneOffset.UTC).month
        val temperature = hour?.temperatureC ?: c.meanTemperatureC[month]
        if (!position.isAboveHorizon) return Irradiance(0.0, 0.0, temperature)
        val factor = monthlyClearnessFactor?.get(month) ?: 1.0
        return mixClearAndOvercast(clear, position, factor).copy(ambientTemperatureC = temperature)
    }

    companion object {
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

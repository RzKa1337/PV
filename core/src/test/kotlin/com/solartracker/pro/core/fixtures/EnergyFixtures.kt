package com.solartracker.pro.core.fixtures

import com.solartracker.pro.core.forecast.LoadForecastPoint
import com.solartracker.pro.core.forecast.PvForecastPoint
import com.solartracker.pro.core.quality.DataKind
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Synthetic day profiles (PV and load) for SOC / energy-security tests. Shapes are smooth and plausible
 * for a ~4 kWp array in Poland; they are test inputs, not measured or forecast data.
 */
object EnergyFixtures {
    data class Profile(val name: String, val pvPeakKw: Double, val sunrise: Double, val sunset: Double, val pvBand: Double, val load: (Double) -> Double)

    private val baseLoad: (Double) -> Double = { h -> if (h in 6.0..22.0) 0.6 else 0.35 }

    val CLEAR_SUMMER = Profile("clear summer", 3.6, 4.5, 20.5, 0.15, baseLoad)
    val CLOUDY_SUMMER = Profile("cloudy summer", 0.9, 4.5, 20.5, 0.45, baseLoad)
    val PARTLY_CLOUDY = Profile("partly cloudy", 2.0, 5.0, 20.0, 0.35, baseLoad)
    val WINTER = Profile("winter", 1.1, 7.7, 15.5, 0.4, { h -> if (h in 6.0..22.0) 0.8 else 0.45 })
    val ZERO_PV = Profile("zero PV", 0.0, 6.0, 18.0, 0.0, baseLoad)
    val HIGH_LOAD = Profile("high load", 3.0, 5.0, 20.0, 0.2, { h -> if (h in 17.0..23.0) 2.5 else 1.0 })
    /** Cold room with a compressor duty cycle (see CoolingLoadProfile) – average ~0.9 kW all day. */
    val COOLING_LOAD = Profile("cooling load", 3.0, 5.0, 20.0, 0.2, { h -> 0.6 + (if (h in 10.0..18.0) 1.2 else 0.7) })

    fun pv(p: Profile, zone: ZoneId): (Instant) -> PvForecastPoint = { t ->
        val z = t.atZone(zone)
        val h = z.hour + z.minute / 60.0
        val kw = if (h <= p.sunrise || h >= p.sunset) 0.0 else p.pvPeakKw * max(0.0, sin(PI * (h - p.sunrise) / (p.sunset - p.sunrise)))
        PvForecastPoint(t, kw, 0.0, kw, kw * (1 - p.pvBand), kw * (1 + p.pvBand), DataKind.FORECAST, 1 - p.pvBand, "fixture", false)
    }

    fun load(p: Profile, zone: ZoneId): (Instant) -> LoadForecastPoint = { t ->
        val z = t.atZone(zone)
        LoadForecastPoint(t, p.load(z.hour + z.minute / 60.0), DataKind.FORECAST, 0.7, "fixture")
    }
}

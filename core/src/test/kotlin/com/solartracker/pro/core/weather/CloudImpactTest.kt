package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CloudImpactTest {
    private val loc = GeoLocation(52.23, 21.01)
    private val system = PvSystem(peakPowerKw = 4.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val date = LocalDate.of(2026, 6, 21)
    private val zone = ZoneOffset.UTC

    private fun forecast(dni: Double, dhi: Double, cloud: Double) = WeatherForecast(
        (0..24).map { h -> HourlyWeather(date.atStartOfDay(zone).toInstant().plusSeconds(h * 3600L), null, dni, dhi, 20.0, cloud) },
        Instant.parse("2026-06-21T00:00:00Z"),
    )

    @Test
    fun overcastReducesClearDoesNot() {
        val overcast = CloudImpactCalculator.day(system, loc, date, zone, WeatherAwareIrradianceModel(loc, forecast(0.0, 120.0, 100.0)))
        assertEquals(WeatherSource.FORECAST, overcast.source)
        assertTrue("overcast ${overcast.reductionPercent}", overcast.reductionPercent!! > 60)
        assertTrue(overcast.describe().contains("prognozy"))
        val none = CloudImpactCalculator.day(system, loc, date, zone, null)
        assertEquals(WeatherSource.CLEAR_SKY, none.source)
        assertEquals(0.0, none.reductionPercent!!, 1e-9)
        assertTrue(none.describe().contains("bezchmurnego"))
    }
}

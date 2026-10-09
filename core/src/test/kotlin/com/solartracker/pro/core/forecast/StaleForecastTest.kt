package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** An old cached forecast must not look as trustworthy as a fresh one just because the hour asked for is "now". */
class StaleForecastTest {
    private val loc = GeoLocation(52.23, 21.01)
    private val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val noon = SolarCalculator.sunTimes(loc, LocalDate.of(2026, 6, 21)).solarNoon

    private fun engine(fetchedAt: Instant): PredictivePvEngine {
        val end = Instant.ofEpochSecond((noon.epochSecond / 3600 + 1) * 3600)
        val hours = (-5..5).map { HourlyWeather(end.plusSeconds(it * 3600L), null, 700.0, 120.0, 20.0, 10.0, windSpeedMs = 2.0) }
        return PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, WeatherForecast(hours, fetchedAt)), null)
    }

    @Test
    fun confidenceFallsWithTheAgeOfTheDownloadedForecast() {
        val fresh = engine(noon.minus(Duration.ofMinutes(10))).at(noon, noon)
        val old = engine(noon.minus(Duration.ofHours(48))).at(noon, noon)
        // Same irradiance, same power – only the trust differs.
        assertEquals(fresh.expectedKw, old.expectedKw, 1e-12)
        assertTrue("fresh ${fresh.confidence} vs 48 h old ${old.confidence}", old.confidence < fresh.confidence - 0.05)
        assertTrue(old.maxKw - old.minKw > fresh.maxKw - fresh.minKw)
        assertTrue(old.basis, "pobrana 48 h temu" in old.basis)
        assertFalse(fresh.basis, "pobrana" in fresh.basis)
        // Still a FORECAST (it is a prediction), never relabelled as a measurement.
        assertEquals(DataKind.FORECAST, old.kind)
    }

    @Test
    fun noForecastMeansNoAgeAndNoAgeNote() {
        val clear = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc), null).at(noon, noon)
        assertFalse(clear.basis, "pobrana" in clear.basis)
        assertEquals(DataKind.ESTIMATED, clear.kind)
        assertNull(WeatherAwareIrradianceModel(loc).forecastFetchedAt)
    }
}

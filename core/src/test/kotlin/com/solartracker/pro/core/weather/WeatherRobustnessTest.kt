package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Bad provider answers, rate limits, missing hours and missing variables: never a crash, never an invented value. */
class WeatherRobustnessTest {
    private val warsaw = GeoLocation(52.23, 21.01)
    private val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val day = LocalDate.of(2026, 6, 21)
    private val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    private fun rejects(json: String, containing: String? = null) {
        try {
            OpenMeteo.parseForecast(json, t0)
            fail("expected IllegalArgumentException for: $json")
        } catch (e: IllegalArgumentException) {
            if (containing != null) assertTrue(e.message, e.message!!.contains(containing, ignoreCase = true))
        }
    }

    @Test
    fun rateLimitAndErrorBodiesAreRejectedWithTheProviderReason() {
        // Open-Meteo answers HTTP 429 / 400 with {"error":true,"reason":"..."}.
        rejects("""{"error":true,"reason":"Too many concurrent requests"}""", "Too many")
        rejects("""{"error":true,"reason":"Latitude must be in range of -90 to 90°. Given: 123."}""", "Latitude")
        rejects("""{"error": "true", "reason": "x"}""")
    }

    @Test
    fun malformedResponsesAreRejected() {
        rejects("")
        rejects("<html>502 Bad Gateway</html>", "JSON")
        rejects("[1,2,3]", "object")
        rejects("""{"hourly":{}}""", "time")                   // no time axis
        rejects("""{"hourly":{"time":[]}}""", "Empty")          // empty forecast
        rejects("""{"hourly":{"time":[null,null]}}""", "Empty") // only null timestamps
        rejects("""{"latitude":1}""", "hourly")
    }

    @Test
    fun oddButParsableValuesAreCleaned() {
        val t = t0.epochSecond
        val json = """{"hourly":{"time":[$t,${t + 3600},${t + 7200},${t + 10800}],
            "shortwave_radiation":[-5.0,300.0,"x",null],"direct_normal_irradiance":[-1.0,400.0],"diffuse_radiation":[10.0,50.0,60.0,70.0],
            "temperature_2m":[10.0,11.0,12.0,13.0],"cloud_cover":[150.0,50.0,-20.0,0.0],"wind_speed_10m":[-3.0,2.0,3.0,4.0]}}"""
        val f = OpenMeteo.parseForecast(json, t0)
        assertEquals(4, f.hours.size)
        assertEquals(0.0, f.hours[0].ghi!!, 0.0)           // negative radiation clamped
        assertEquals(0.0, f.hours[0].dni!!, 0.0)
        assertNull(f.hours[2].ghi)                          // a string is not a number → missing, not 0
        assertNull(f.hours[3].dni)                          // shorter array padded with null
        assertEquals(100.0, f.hours[0].cloudCoverPercent!!, 0.0)
        assertEquals(0.0, f.hours[2].cloudCoverPercent!!, 0.0)
        assertEquals(0.0, f.hours[0].windSpeedMs!!, 0.0)
        assertFalse(f.hours[3].hasIrradiance)
        assertTrue(f.hours[1].hasIrradiance)
    }

    private fun hours(range: IntProgression, dni: Double = 600.0, dhi: Double = 100.0): List<HourlyWeather> {
        val end0 = Instant.ofEpochSecond((noon.epochSecond / 3600 + 1) * 3600)
        return range.map { HourlyWeather(end0.plusSeconds(it * 3600L), null, dni, dhi, 20.0, 20.0, windSpeedMs = 2.0) }
    }

    @Test
    fun aMissingHourFallsBackForThatHourOnlyAndNeverProducesJumpsToNaN() {
        val gap = hours(-4..-1) + hours(1..4) // the hour ending at +0 is missing
        val forecast = WeatherForecast(gap, noon)
        val model = WeatherAwareIrradianceModel(warsaw, forecast)
        val est = PvEstimator(model)
        val gapMid = Instant.ofEpochSecond((noon.epochSecond / 3600) * 3600 + 1800)
        assertEquals(WeatherSource.CLEAR_SKY, model.sourceAt(gapMid))                            // honest: no forecast, no climate
        assertEquals(WeatherSource.FORECAST, model.sourceAt(gapMid.minusSeconds(3600)))
        assertEquals(WeatherSource.FORECAST, model.sourceAt(gapMid.plusSeconds(3600)))
        for (k in -180..180 step 5) {
            val t = gapMid.plusSeconds(k * 60L)
            val p = est.powerKw(system, warsaw, t)
            assertTrue("finite and non-negative at $t", p.isFinite() && p >= 0.0 && p <= system.peakPowerKw)
        }
        // The hour next to the gap still uses its own forecast (it does not borrow from the missing neighbour).
        val pos = SolarCalculator.position(warsaw, gapMid.minusSeconds(1800))
        assertNotNull(model.irradiance(pos, gapMid.minusSeconds(1800)).ambientTemperatureC)
        // With a climate fallback the gap is CLIMATE instead.
        val withClimate = WeatherAwareIrradianceModel(warsaw, forecast, MonthlyClimate.DEFAULT_POLAND)
        assertEquals(WeatherSource.CLIMATE, withClimate.sourceAt(gapMid))
    }

    @Test
    fun forecastWithoutIrradianceNeverBecomesZeroProduction() {
        // Only temperature/cloud (no radiation variables): the model must not read "missing" as "dark".
        val noRadiation = (-3..3).map { HourlyWeather(Instant.ofEpochSecond((noon.epochSecond / 3600 + 1 + it) * 3600L), null, null, null, 18.0, 90.0) }
        val model = WeatherAwareIrradianceModel(warsaw, WeatherForecast(noRadiation, noon))
        assertEquals(WeatherSource.CLEAR_SKY, model.sourceAt(noon))
        assertEquals(PvEstimator().powerKw(system, warsaw, noon), PvEstimator(model).powerKw(system, warsaw, noon), 1e-9)
    }

    @Test
    fun forecastAgeAndCoverageAreReportedFromTheDataOnly() {
        val f = WeatherForecast(hours(-2..2), noon.minus(Duration.ofHours(5)))
        assertEquals(noon.minus(Duration.ofHours(5)), f.fetchedAt)
        assertEquals(5, f.hours.size)
        assertEquals(f.hours.first().startTime, f.coversFrom)
        assertEquals(f.hours.last().endTime, f.coversUntil)
        assertNull(f.at(f.coversUntil!!.plusSeconds(1)))
        assertNull(f.at(f.coversFrom!!))                    // interval is (start, end]: the start instant belongs to the previous hour
    }
}

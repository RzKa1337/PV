package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 15-minute irradiance: SYNTHETIC inputs (clear sky × a factor). */
class QuarterHourIrradianceTest {
    private val loc = GeoLocation(51.94, 15.50)
    private val start = LocalDate.of(2026, 6, 20).atStartOfDay(ZoneOffset.UTC).toInstant()
    private val clearSky = ClearSkyModel()

    private fun hour(end: Instant, factor: Double = 1.0): HourlyWeather {
        val mid = end.minus(Duration.ofMinutes(30))
        val pos = SolarCalculator.position(loc, mid)
        val irr = clearSky.irradiance(pos, mid)
        return HourlyWeather(end, irr.ghi(pos) * factor, irr.dni * factor, irr.dhi, 20.0, 0.0)
    }

    private fun quarter(end: Instant, factor: Double): IntervalIrradiance {
        val mid = end.minus(Duration.ofMinutes(7)).minusSeconds(30)
        val pos = SolarCalculator.position(loc, mid)
        val irr = clearSky.irradiance(pos, mid)
        return IntervalIrradiance(end, Duration.ofMinutes(15), irr.ghi(pos) * factor, irr.dni * factor, irr.dhi * if (factor < 0.5) 2.0 else 1.0)
    }

    private val hours = (1..48).map { hour(start.plus(Duration.ofHours(it.toLong()))) }

    private fun ghi(model: WeatherAwareIrradianceModel, t: Instant): Double {
        val pos = SolarCalculator.position(loc, t)
        return model.irradiance(pos, t).ghi(pos)
    }

    @Test
    fun passingCloudIsPlacedWithinTheHour() {
        // 10:00–11:00 UTC: bright hour on the hourly grid, but a cloud in the third quarter (10:30–10:45).
        val h10 = start.plus(Duration.ofHours(10))
        val quarters = (1..4).map { q -> quarter(h10.plus(Duration.ofMinutes(15L * q)), if (q == 3) 0.15 else 1.0) }
        val withQuarters = WeatherAwareIrradianceModel(loc, WeatherForecast(hours, start, quarters = quarters))
        val hourly = WeatherAwareIrradianceModel(loc, WeatherForecast(hours, start))
        val cloudCentre = h10.plus(Duration.ofMinutes(37)).plusSeconds(30)
        val sunnyCentre = h10.plus(Duration.ofMinutes(7)).plusSeconds(30)
        assertTrue(withQuarters.usesQuarterHour(cloudCentre))
        assertTrue(ghi(withQuarters, cloudCentre) < 0.4 * ghi(withQuarters, sunnyCentre))
        assertTrue(ghi(hourly, cloudCentre) > 0.8 * ghi(hourly, sunnyCentre))

        // Over the whole quarter the cloud still dominates (the shape blends towards the sunny neighbours only near the
        // boundaries) and the hour as a whole is darker than on the hourly grid alone.
        fun mean(m: WeatherAwareIrradianceModel, from: Instant, minutes: Int) =
            (0 until minutes).map { from.plusSeconds(30L + it * 60L) }.map { ghi(m, it) }.average()
        val q3 = quarters[2]
        assertTrue(mean(withQuarters, q3.startTime, 15) < 0.7 * mean(hourly, q3.startTime, 15))
        assertTrue(mean(withQuarters, h10, 60) < mean(hourly, h10, 60))
    }

    @Test
    fun outsideTheQuartersTheHourlyModelIsUnchanged() {
        val h10 = start.plus(Duration.ofHours(10))
        val quarters = (1..4).map { q -> quarter(h10.plus(Duration.ofMinutes(15L * q)), 0.5) }
        val withQuarters = WeatherAwareIrradianceModel(loc, WeatherForecast(hours, start, quarters = quarters))
        val hourly = WeatherAwareIrradianceModel(loc, WeatherForecast(hours, start))
        for (t in listOf(start.plus(Duration.ofHours(13)), start.plus(Duration.ofHours(30)).plusSeconds(900))) {
            assertFalse(withQuarters.usesQuarterHour(t))
            assertEquals(ghi(hourly, t), ghi(withQuarters, t), 1e-9)
        }
    }

    @Test
    fun parsesMinutely15AndToleratesItsAbsence() {
        val times = (1..48).map { start.plus(Duration.ofHours(it.toLong())).epochSecond }
        val q = (1..8).map { start.plus(Duration.ofMinutes(600L + 15L * it)).epochSecond }
        fun arr(n: Int, v: String) = List(n) { v }.joinToString(",")
        val base = "\"hourly\":{\"time\":[${times.joinToString(",")}],\"shortwave_radiation\":[${arr(48, "300")}]," +
            "\"direct_normal_irradiance\":[${arr(48, "400")}],\"diffuse_radiation\":[${arr(48, "100")}]}"
        val withQ = "{\"timezone\":\"UTC\",$base,\"minutely_15\":{\"time\":[${q.joinToString(",")}],\"shortwave_radiation\":[${arr(8, "500")}]," +
            "\"direct_normal_irradiance\":[${arr(7, "600")},null],\"diffuse_radiation\":[${arr(8, "120")}]}}"
        val f = OpenMeteo.parseForecast(withQ, start)
        assertEquals("the step without DNI is dropped", 7, f.quarters.size)
        assertEquals(600.0, f.quarters.first().dni!!, 1e-9)
        assertEquals(Duration.ofMinutes(15), f.quarters.first().length)
        assertTrue(f.quarterAt(start.plus(Duration.ofMinutes(620))) != null)
        assertEquals(0, OpenMeteo.parseForecast("{$base}", start).quarters.size)
        // A malformed block never breaks the hourly forecast.
        assertEquals(48, OpenMeteo.parseForecast("{$base,\"minutely_15\":{\"time\":\"x\"}}", start).hours.size)
        assertTrue(OpenMeteo.forecastUrl(loc).contains("minutely_15="))
        assertFalse(OpenMeteo.forecastUrl(loc, quarterHours = false).contains("minutely_15"))
    }
}

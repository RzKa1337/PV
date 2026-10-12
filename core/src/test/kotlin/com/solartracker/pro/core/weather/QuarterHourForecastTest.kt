package com.solartracker.pro.core.weather

import com.solartracker.pro.core.forecast.PredictivePvEngine
import com.solartracker.pro.core.forecast.PvRadarBuilder
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 15-minute Open-Meteo irradiance (`minutely_15`): parsing, lookup, use in the irradiance model and in the PV radar. */
class QuarterHourForecastTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 3.0, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val now = LocalDate.of(2026, 6, 21).atTime(11, 0).atZone(zone).toInstant()
    private val cs = ClearSkyModel()

    /** Clear-sky beam and diffuse at the middle of an interval, scaled by [factor]. */
    private fun clear(start: Instant, seconds: Long, factor: Double): Pair<Double, Double> {
        val mid = start.plusSeconds(seconds / 2)
        val pos = SolarCalculator.position(loc, mid)
        val irr = cs.irradiance(pos, mid)
        return irr.dni * factor to irr.dhi * factor
    }

    /** Hours −2…+10 around [now]; hour i covers now + i h … now + (i + 1) h. */
    private fun hours(factor: (Int) -> Double = { 1.0 }): List<HourlyWeather> = (-2..10).map { i ->
        val start = now.plus(Duration.ofHours(i.toLong()))
        val (dni, dhi) = clear(start, 3600, factor(i))
        HourlyWeather(start.plusSeconds(3600), null, dni, dhi, 22.0, 5.0)
    }

    /** Quarters −4…+11 around [now] (one hour back, three ahead); quarter q covers now + 15q min … now + 15(q + 1) min. */
    private fun quarters(range: IntRange = -4..11, factor: (Int) -> Double = { 1.0 }): List<QuarterHourIrradiance> = range.map { q ->
        val start = now.plusSeconds(q * 900L)
        val (dni, dhi) = clear(start, 900, factor(q))
        QuarterHourIrradiance(start.plusSeconds(900), dni, dhi)
    }

    private fun model(hours: List<HourlyWeather>, quarters: List<QuarterHourIrradiance> = emptyList()) =
        WeatherAwareIrradianceModel(loc, WeatherForecast(hours, now, zone, quarterHours = quarters))

    private fun ghi(m: WeatherAwareIrradianceModel, t: Instant): Double {
        val pos = SolarCalculator.position(loc, t)
        return m.irradiance(pos, t).ghi(pos)
    }

    private fun clearGhi(t: Instant): Double {
        val pos = SolarCalculator.position(loc, t)
        return cs.irradiance(pos, t).ghi(pos)
    }

    /** A cloud arriving 30 minutes from now and staying for half an hour. */
    private val cloudAt30 = { q: Int -> if (q in 2..3) 0.3 else 1.0 }

    @Test
    fun url_requestsQuarterHourIrradianceForYesterdayAndTwoDaysAhead() {
        val url = OpenMeteo.forecastUrl(loc)
        assertTrue("minutely_15=shortwave_radiation,direct_normal_irradiance,diffuse_radiation" in url)
        assertTrue("past_minutely_15=96" in url && "forecast_minutely_15=192" in url)
        assertTrue("the hourly request is unchanged", "forecast_days=16" in url && "past_days=2" in url && "hourly=temperature_2m" in url)
    }

    @Test
    fun parse_realResponse_hasQuartersNextToHours() {
        // Real Open-Meteo answer for Warsaw captured 2026-10-12 with the app's variables (fewer days to keep the file small).
        val json = javaClass.getResource("/open-meteo-warsaw-minutely15.json")!!.readText()
        val f = OpenMeteo.parseForecast(json, Instant.ofEpochSecond(1791805000))
        assertEquals(120, f.hours.size)
        assertEquals(288, f.quarterHours.size)
        assertEquals(zone, f.zone)
        assertTrue(f.quarterHours.zipWithNext().all { (a, b) -> Duration.between(a.endTime, b.endTime) == Duration.ofMinutes(15) })
        assertTrue(f.quarterHours.all { it.dni >= 0 && it.dhi >= 0 && it.dni < 1400 && it.dhi < 1000 })
        assertTrue("daylight in the data", f.quarterHours.any { it.dhi > 50 })
        // Quarters lie inside the hourly range, so temperature and wind are always available for them.
        assertNotNull(f.at(f.quarterHours.first().endTime))
        assertNotNull(f.at(f.quarterHours.last().endTime))
    }

    @Test
    fun parse_realResponse_modelGivesSanePowerOverTheWholeRange() {
        val json = javaClass.getResource("/open-meteo-warsaw-minutely15.json")!!.readText()
        val f = OpenMeteo.parseForecast(json, Instant.ofEpochSecond(1791805000))
        val fetched = f.fetchedAt
        val withQuarters = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, f), null)
        val hourlyOnly = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, WeatherForecast(f.hours, fetched, f.zone)), null)
        var a = 0.0
        var b = 0.0
        var t = f.quarterHours.first().startTime.plusSeconds(150)
        while (t.isBefore(f.quarterHours.last().endTime)) {
            val p = withQuarters.at(t, fetched).expectedKw
            assertTrue("power $p at $t", p.isFinite() && p >= 0 && p <= system.peakPowerKw * 1.2)
            a += p / 12
            b += hourlyOnly.at(t, fetched).expectedKw / 12
            t = t.plusSeconds(300)
        }
        assertTrue("three days produce energy", a > 0.5 && b > 0.5)
        // Same provider, two time steps: the totals must tell the same story (they are not identical – different model runs).
        assertTrue("15 min $a kWh vs hourly $b kWh", a / b in 0.6..1.6)
    }

    @Test
    fun parse_missingOrBrokenBlock_keepsTheHourlyForecast() {
        val t = now.epochSecond
        val hourly = """"hourly":{"time":[$t],"direct_normal_irradiance":[500],"diffuse_radiation":[100],"temperature_2m":[20]}"""
        assertTrue(OpenMeteo.parseForecast("{$hourly}", now).quarterHours.isEmpty()) // old cache without the block
        for (broken in listOf("5", "\"x\"", "[]", "{}", "{\"time\":7}", "{\"direct_normal_irradiance\":[1]}")) {
            val f = OpenMeteo.parseForecast("{$hourly,\"minutely_15\":$broken}", now)
            assertEquals("hourly survives minutely_15=$broken", 1, f.hours.size)
            assertTrue(f.quarterHours.isEmpty())
        }
    }

    @Test
    fun parse_dropsIncompleteStepsAndClampsNegatives() {
        val t = now.epochSecond
        val json = """{"hourly":{"time":[$t],"direct_normal_irradiance":[500],"diffuse_radiation":[100]},
            "minutely_15":{"time":[${t + 900},${t + 1800},null,${t + 3600},${t + 4500}],
              "direct_normal_irradiance":[400,null,300,-5,250],"diffuse_radiation":[90,80,70,60]}}"""
        val q = OpenMeteo.parseForecast(json, now).quarterHours
        // 2nd: no beam, 3rd: no time, 5th: diffuse array too short → dropped; 4th: negative beam → 0.
        assertEquals(listOf(now.plusSeconds(900), now.plusSeconds(3600)), q.map { it.endTime })
        assertEquals(400.0, q[0].dni, 0.0)
        assertEquals(0.0, q[1].dni, 0.0)
        assertEquals(60.0, q[1].dhi, 0.0)
    }

    @Test
    fun lookup_usesPrecedingIntervalConvention() {
        val f = WeatherForecast(hours(), now, zone, quarterHours = quarters(0..3).shuffled(java.util.Random(1)))
        val end0 = now.plusSeconds(900)
        assertNull("the start of the first quarter belongs to the one before", f.quarterAt(now))
        assertEquals(end0, f.quarterAt(now.plusSeconds(1))!!.endTime)
        assertEquals(end0, f.quarterAt(end0)!!.endTime) // end is inclusive
        assertEquals(end0.plusSeconds(900), f.quarterAt(end0.plusSeconds(1))!!.endTime)
        assertNull(f.quarterAt(now.plusSeconds(3601)))
        assertNull(WeatherForecast(hours(), now).quarterAt(now.plusSeconds(60)))
    }

    @Test
    fun model_followsTheQuartersNotTheHour() {
        // The hourly data knows nothing about the cloud; the 15-minute data does.
        val sharp = model(hours(), quarters(factor = cloudAt30))
        val coarse = model(hours())
        val sunny = now.plusSeconds(450)       // middle of the first quarter
        val cloudy = now.plusSeconds(45 * 60)  // boundary between the two cloudy quarters
        val after = now.plusSeconds(5850)      // middle of a clear quarter, away from the cloud
        assertEquals(1.0, ghi(sharp, sunny) / clearGhi(sunny), 0.03)
        assertEquals(0.3, ghi(sharp, cloudy) / clearGhi(cloudy), 0.03)
        assertEquals(1.0, ghi(sharp, after) / clearGhi(after), 0.03)
        assertEquals(1.0, ghi(coarse, cloudy) / clearGhi(cloudy), 0.03)
    }

    @Test
    fun model_keepsTheEnergyOfTheQuarters() {
        val q = quarters(factor = cloudAt30)
        val m = model(hours(), q)
        // Provider energy of quarters 0…7 (two hours) vs the model integrated minute by minute over the same time.
        var provider = 0.0
        for (i in 0..7) {
            val start = now.plusSeconds(i * 900L)
            var mean = 0.0
            for (k in 0 until 15) mean += clearGhi(start.plusSeconds(k * 60L + 30)) * cloudAt30(i) / 15
            provider += mean * 0.25
        }
        var modelled = 0.0
        for (k in 0 until 120) modelled += ghi(m, now.plusSeconds(k * 60L + 30)) / 60
        assertEquals(provider, modelled, provider * 0.02)
    }

    @Test
    fun model_fallsBackToHoursOutsideTheQuarters() {
        val sharp = model(hours(), quarters(factor = cloudAt30))
        val coarse = model(hours())
        val later = now.plus(Duration.ofMinutes(5 * 60 + 20)) // quarters end at +3 h
        assertEquals(ghi(coarse, later), ghi(sharp, later), 1e-9)
        assertEquals(15, sharp.resolutionMinutesAt(now.plusSeconds(3600)))
        assertEquals(60, sharp.resolutionMinutesAt(later))
        assertNull(sharp.resolutionMinutesAt(now.plus(Duration.ofDays(3))))
        assertEquals(60, coarse.resolutionMinutesAt(now.plusSeconds(3600)))
        assertEquals(WeatherSource.FORECAST, sharp.sourceAt(now.plusSeconds(3600)))
        assertEquals(WeatherSource.CLEAR_SKY, sharp.sourceAt(now.plus(Duration.ofDays(3))))
    }

    @Test
    fun resolutionOfAWindow_isTheCoarsestStepInIt() {
        val sharp = model(hours(), quarters())
        assertEquals(15, sharp.resolutionMinutes(now.plusSeconds(60), now.plus(Duration.ofMinutes(180))))
        assertEquals("window runs past the quarters", 60, sharp.resolutionMinutes(now.plusSeconds(60), now.plus(Duration.ofMinutes(181))))
        assertEquals("hourly forecast only", 60, model(hours()).resolutionMinutes(now.plusSeconds(60), now.plus(Duration.ofMinutes(180))))
        assertEquals("no forecast at all", 60, WeatherAwareIrradianceModel(loc).resolutionMinutes(now, now.plusSeconds(3600)))
        // A hole in the 15-minute data inside the window also falls back to the honest 60 minutes.
        val holed = model(hours(), quarters().filterIndexed { i, _ -> i != 8 })
        assertEquals(60, holed.resolutionMinutes(now.plusSeconds(60), now.plus(Duration.ofMinutes(180))))
    }

    @Test
    fun night_isDarkAndStillHasTemperature() {
        val midnight = LocalDate.of(2026, 6, 21).atTime(0, 30).atZone(zone).toInstant()
        val h = (-2..2).map { HourlyWeather(midnight.plus(Duration.ofHours(it.toLong())).plusSeconds(1800), 0.0, 0.0, 0.0, 14.0, 0.0) }
        val q = (-4..4).map { QuarterHourIrradiance(midnight.plusSeconds(it * 900L), 0.0, 0.0) }
        val m = WeatherAwareIrradianceModel(loc, WeatherForecast(h, midnight, zone, quarterHours = q))
        val pos = SolarCalculator.position(loc, midnight)
        val irr = m.irradiance(pos, midnight)
        assertEquals(0.0, irr.ghi(pos), 0.0)
        assertEquals(14.0, irr.ambientTemperatureC!!, 1e-9)
    }

    @Test
    fun radar_timesTheCloudWithQuarterHourData() {
        // What the hourly data would say about the same sky: the first hour is the mean of its four quarters.
        val hourly = hours { if (it == 0) (1.0 + 1.0 + 0.3 + 0.3) / 4 else 1.0 }
        val weather = model(hourly, quarters(factor = cloudAt30))
        val pv = PredictivePvEngine(loc, system, weather, null)
        val clear = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc), null)
        val t0 = now.plusSeconds(60)
        val resolution = weather.resolutionMinutes(t0, t0.plus(Duration.ofMinutes(PvRadarBuilder.EVENT_WINDOW_MIN - 5)))
        val radar = PvRadarBuilder(pv, clear, system.peakPowerKw, resolution).build(t0, nowcastRatio = null, currentKw = null)
        val event = radar.cloudEvent!!
        assertEquals(15, radar.resolutionMinutes)
        assertEquals(15, event.resolutionMinutes)
        assertTrue("cloud arrives 30 min from now, event at +${event.minutesAhead} min", event.minutesAhead in 25..40)
        assertTrue("drop ${event.dropPercent}", event.dropPercent >= 50)
        assertTrue("15 min" in radar.basis)
    }
}

package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.AccuracyReport
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.radar.RadarFrameKind
import com.solartracker.pro.core.radar.RadarFreshnessEvaluator
import com.solartracker.pro.core.radar.RadarStatus
import com.solartracker.pro.core.radar.RainViewer
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.Psychrometrics
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
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
import java.time.ZoneId

/** SYNTHETIC weather (clear-sky irradiance × a factor) – no provider data is invented, only test inputs. */
class RadarForecastTest {
    private val loc = GeoLocation(37.37, 27.27) // Didim
    private val zone = ZoneId.of("Europe/Istanbul")
    private val system = PvSystem(peakPowerKw = 2.09, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val day = LocalDate.of(2026, 10, 8)
    private val clearSky = ClearSkyModel()

    private fun hour(end: Instant, factor: Double = 1.0, temp: Double? = 24.0, rain: Double? = 0.0, pop: Double? = 5.0, cloud: Double? = 10.0,
                     rh: Double? = 50.0, dew: Double? = null, gust: Double? = 5.0, wind: Double? = 3.0): HourlyWeather {
        val mid = end.minus(Duration.ofMinutes(30))
        val pos = SolarCalculator.position(loc, mid)
        val irr = clearSky.irradiance(pos, mid)
        return HourlyWeather(end, irr.ghi(pos) * factor, irr.dni * factor, irr.dhi, temp, cloud, windSpeedMs = wind, relativeHumidityPercent = rh,
            precipitationMm = rain, windGustsMs = gust, dewPointC = dew, precipitationProbabilityPercent = pop, windDirectionDeg = 200.0)
    }

    private fun forecast(days: Int = 2, zone: ZoneId? = this.zone, f: (Instant) -> HourlyWeather = { hour(it) }): WeatherForecast {
        val start = day.atStartOfDay(this.zone).toInstant()
        return WeatherForecast((1..days * 24).map { f(start.plus(Duration.ofHours(it.toLong()))) }, start, zone, loc.latitude, loc.longitude)
    }

    private fun engine(fc: WeatherForecast?): HourlyPvForecastEngine {
        val weather = WeatherAwareIrradianceModel(loc, fc)
        val pv = PredictivePvEngine(loc, system, weather, null)
        val clear = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc), null)
        val est = PvEstimator(weather)
        return HourlyPvForecastEngine(loc, fc, weather::sourceAt, { t -> pv.at(t, t) }, { t -> clear.at(t, t).expectedKw }, { t -> est.pointEstimate(system, loc, t) }, ZoneId.of("UTC"))
    }

    private fun row(start: Instant, w: Double, simulated: Boolean = false, minutes: Long = 5) = HistorySample(start, start.plus(Duration.ofMinutes(minutes)), 5, w, w,
        null, null, null, null, null, null, null, null, w * minutes / 60.0 / 1000, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptySet(), emptySet(), simulated)

    private fun fullHour(start: Instant, w: Double, simulated: Boolean = false) = (0 until 12).map { row(start.plus(Duration.ofMinutes(5L * it)), w, simulated) }

    // ---- Dew point
    @Test
    fun dewPointMagnus() {
        assertEquals(14.5, Psychrometrics.dewPointC(26.4, 48.0)!!, 0.1) // Magnus (Alduchov–Eskridge); simpler coefficient sets give ~14.2
        assertEquals(20.0, Psychrometrics.dewPointC(20.0, 100.0)!!, 1e-9) // saturation: dew point = air temperature
        assertEquals(24.0, Psychrometrics.dewPointC(25.0, 94.0)!!, 0.1) // high humidity
        assertEquals(-4.9, Psychrometrics.dewPointC(30.0, 10.0)!!, 0.2) // very dry
        assertEquals(-10.0, Psychrometrics.dewPointC(-5.0, 68.0)!!, 0.3) // below freezing
        assertNull(Psychrometrics.dewPointC(20.0, 0.0))
        assertNull(Psychrometrics.dewPointC(20.0, 101.0))
        assertNull(Psychrometrics.dewPointC(60.0, 50.0)) // outside the formula's validity
        assertNull(Psychrometrics.dewPointC(null, 50.0))
        assertNull(Psychrometrics.dewPointC(20.0, Double.NaN))
        val provider = hour(Instant.parse("2026-10-08T12:00:00Z"), dew = 9.0)
        assertEquals(9.0 to false, Psychrometrics.dewPoint(provider)) // provider value wins
        assertTrue(Psychrometrics.dewPoint(provider.copy(dewPointC = null))!!.second) // otherwise CALCULATED from the same hour
    }

    // ---- Provider parsing, timezone, missing values
    @Test
    fun openMeteoParsesNewFieldsAndLocationZone() {
        val json = """{"latitude":37.375,"longitude":27.25,"timezone":"Europe/Istanbul","hourly":{"time":[1791460800,1791464400],
            "temperature_2m":[26.4,null],"relative_humidity_2m":[48,50],"dew_point_2m":[14.2,null],"apparent_temperature":[27.1,null],
            "precipitation_probability":[5,80],"wind_direction_10m":[370,null],"rain":[0.0,1.8],"snowfall":[0,0],"weather_code":[1,61],
            "shortwave_radiation":[600,null],"direct_normal_irradiance":[700,null],"diffuse_radiation":[100,null]}}"""
        val f = OpenMeteo.parseForecast(json, Instant.EPOCH)
        assertEquals(ZoneId.of("Europe/Istanbul"), f.zone)
        assertEquals(37.375, f.latitude!!, 0.0)
        val h = f.hours[0]
        assertEquals(14.2, h.dewPointC!!, 0.0)
        assertEquals(27.1, h.apparentTemperatureC!!, 0.0)
        assertEquals(10.0, h.windDirectionDeg!!, 1e-9) // normalised
        assertEquals(61, f.hours[1].weatherCode)
        assertNull("missing stays missing", f.hours[1].temperatureC)
        assertNull(OpenMeteo.parseForecast(json.replace("Europe/Istanbul", "GMT"), Instant.EPOCH).zone) // old cache: zone unknown
        assertTrue(OpenMeteo.forecastUrl(loc).contains("timezone=auto"))
    }

    @Test
    fun dstDayHas25LocalHoursAndLabelsInLocationZone() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        val dstDay = LocalDate.of(2026, 10, 25) // CEST → CET
        val start = dstDay.atStartOfDay(warsaw).toInstant()
        val fc = WeatherForecast((1..48).map { hour(start.plus(Duration.ofHours(it.toLong()))) }, start, warsaw)
        val weather = WeatherAwareIrradianceModel(loc, fc)
        val pv = PredictivePvEngine(loc, system, weather, null)
        val est = PvEstimator(weather)
        val e = HourlyPvForecastEngine(loc, fc, weather::sourceAt, { pv.at(it, it) }, null, { est.pointEstimate(system, loc, it) }, ZoneId.of("UTC"))
        val r = e.build(start.plus(Duration.ofHours(3)), 1, emptyList(), null, false)
        assertEquals(warsaw, r.zone)
        assertTrue(r.zoneFromProvider)
        val local = r.days.single().hours
        assertEquals(25, local.size)
        assertEquals(2, local.count { it.start.atZone(warsaw).hour == 2 }) // 02:00 twice (CEST and CET)
        assertTrue(local.zipWithNext().all { (a, b) -> Duration.between(a.start, b.start) == Duration.ofHours(1) })
    }

    @Test
    fun noForecastFallsBackHonestly() {
        val e = engine(null)
        val r = e.build(day.atTime(12, 0).atZone(zone).toInstant(), 1, emptyList(), null, false)
        assertEquals(ValueQuality.UNAVAILABLE, r.weatherQuality)
        assertEquals(ZoneId.of("UTC"), r.zone)
        assertFalse(r.zoneFromProvider)
        assertTrue(r.hours.all { it.weather == null })
        assertTrue(r.hours.first().weatherProvenance.quality == ValueQuality.UNAVAILABLE)
        assertEquals(ConfidenceLevel.LOW, r.days.first().confidence.level)
    }

    // ---- PV forecast
    @Test
    fun pvForecastClearCloudsRainNightSunriseHeat() {
        val now = day.atTime(9, 0).atZone(zone).toInstant()
        val clear = engine(forecast()).build(now, 1, emptyList(), null, false)
        val cloudy = engine(forecast { hour(it, factor = 0.3, cloud = 95.0) }).build(now, 1, emptyList(), null, false)
        val hot = engine(forecast { hour(it, temp = 42.0) }).build(now, 1, emptyList(), null, false)
        val d = clear.days.first()
        assertTrue("clear day ${d.expectedKwh}", d.expectedKwh in 6.0..14.0)
        assertTrue(d.peakW in 1200.0..2090.0)
        val noonLocal = d.peakAt!!.atZone(zone).toLocalTime()
        assertTrue("peak near solar noon: $noonLocal", noonLocal.hour in 11..14)
        assertTrue(cloudy.days.first().expectedKwh < d.expectedKwh * 0.5)
        assertTrue("heat lowers output", hot.days.first().expectedKwh < d.expectedKwh)
        val night = clear.hours.filter { it.sunElevationDeg < -5 }
        assertTrue(night.isNotEmpty() && night.all { it.expectedW == 0.0 })
        val sunrise = d.sun.sunrise!!
        val first = clear.hours.first { it.expectedW > 0 }
        assertTrue("first production hour contains/after sunrise", !first.end.isBefore(sunrise))
        val h14 = clear.hours.single { it.start.atZone(zone).hour == 14 }
        assertNotNull(h14.ghiWm2); assertNotNull(h14.poaWm2)
        assertTrue(h14.sunAzimuthDeg in 180.0..270.0)
        assertEquals(h14.expectedW, d.hours.single { it.start == h14.start }.expectedW, 1e-9)
        assertEquals(ConfidenceLevel.HIGH, d.confidence.level)
        assertTrue(d.confidence.factors.isNotEmpty())
        assertEquals("słonecznie", d.condition)
        assertNotNull(clear.best)
        assertNull(clear.worst)
        val worst = cloudy.worst!!
        assertTrue(worst.reason!!.contains("chmury"))
    }

    @Test
    fun rainApproachingWithImpactAndNightRainWithout() {
        val now = day.atTime(10, 10).atZone(zone).toInstant()
        val rainStart = day.atTime(12, 0).atZone(zone).toInstant()
        val fc = forecast { end -> val s = end.minus(Duration.ofHours(1)); if (!s.isBefore(rainStart) && s.isBefore(rainStart.plus(Duration.ofHours(2))))
            hour(end, factor = 0.2, rain = 2.0, pop = 90.0, cloud = 100.0) else hour(end) }
        val r = engine(fc).build(now, 1, emptyList(), null, false)
        val rain = r.rain!!
        assertFalse(rain.raining)
        assertEquals(rainStart, rain.from)
        assertEquals(rainStart.plus(Duration.ofHours(2)), rain.to)
        assertEquals(4.0, rain.precipitationMm, 1e-9)
        assertTrue("impact ${rain.impactPercent}", rain.impactPercent!! in 50.0..95.0)
        assertTrue(rain.reductionKwh!! > 0.5)
        assertTrue(r.alerts.any { it.kind == AlertKind.RAIN })
        val nightRain = day.atTime(22, 0).atZone(zone).toInstant()
        val n = engine(forecast { e -> if (e.minus(Duration.ofHours(1)) == nightRain) hour(e, rain = 1.0, pop = 80.0) else hour(e) })
            .build(day.atTime(20, 0).atZone(zone).toInstant(), 1, emptyList(), null, false).rain!!
        assertNull(n.impactPercent)
        assertTrue(n.note.contains("nocy"))
        val dry = engine(forecast()).build(now, 1, emptyList(), null, false).rain!!
        assertNull(dry.from)
    }

    @Test
    fun windAndHeatAlertsUseDocumentedThresholds() {
        val now = day.atTime(8, 0).atZone(zone).toInstant()
        val r = engine(forecast { e -> if (e.atZone(zone).hour == 15) hour(e, gust = 20.0, temp = 33.0) else hour(e) }).build(now, 1, emptyList(), null, false)
        assertTrue(r.alerts.single { it.kind == AlertKind.WIND }.detail.contains("Beauforta"))
        assertTrue(r.alerts.any { it.kind == AlertKind.HEAT })
        val calm = engine(forecast()).build(now, 1, emptyList(), null, false)
        assertTrue(calm.alerts.none { it.kind == AlertKind.WIND || it.kind == AlertKind.HEAT })
    }

    // ---- Expected vs actual
    @Test
    fun expectedVsActual() {
        val now = day.atTime(15, 30).atZone(zone).toInstant()
        val fc = forecast()
        val base = engine(fc).build(now, 1, emptyList(), null, false)
        fun h(hr: Int) = base.hours.single { it.start.atZone(zone).hour == hr }
        val rows = fullHour(h(11).start, h(11).expectedW) + fullHour(h(12).start, h(12).expectedW * 1.1) + fullHour(h(13).start, h(13).expectedW * 0.9) +
            (0 until 4).map { row(h(14).start.plus(Duration.ofMinutes(5L * it)), 500.0) } + fullHour(h(10).start, 999.0, simulated = true)
        val r = engine(fc).build(now, 1, rows, null, false)
        fun rh(hr: Int) = r.hours.single { it.start.atZone(zone).hour == hr }
        assertEquals(0.0, rh(11).deviationPercent!!, 1e-6) // exact match
        assertEquals(10.0, rh(12).deviationPercent!!, 1e-6)
        assertEquals(-10.0, rh(13).deviationPercent!!, 1e-6)
        assertEquals(ValueQuality.PARTIAL, rh(14).actual.quality) // 20 of 60 minutes
        assertEquals(ValueQuality.SIMULATED, rh(10).actual.quality)
        assertNull("simulator is never Actual", rh(10).actual.averageW)
        assertEquals(999.0, rh(10).actual.simulatedW!!, 1e-9)
        assertNull(rh(10).differenceW)
        assertEquals(ValueQuality.UNAVAILABLE, rh(9).actual.quality) // missing actual
        assertEquals(ValueQuality.UNAVAILABLE, rh(17).actual.quality) // future
        val today = r.days.first()
        // Like with like: the partial hour counts only for the 20 minutes that were measured.
        val e = listOf(11, 12, 13).map { h(it).expectedW }
        val perf = (e[0] + 1.1 * e[1] + 0.9 * e[2] + 500.0 / 3) / (e[0] + e[1] + e[2] + h(14).expectedW / 3) * 100
        assertEquals(perf, today.performancePercent!!, 1e-6)
        assertTrue(today.actualSoFarKwh!! > 0)
        // Live value: real vs simulator.
        val live = engine(fc).build(now, 1, rows, null, false, liveActualW = 1000.0)
        assertEquals(ValueQuality.REAL, live.now.actualQuality)
        assertEquals(1000.0 - live.now.expectedW!!, live.now.differenceW!!, 1e-9)
        val sim = engine(fc).build(now, 1, rows, null, false, liveActualW = 1000.0, liveSimulated = true)
        assertNull(sim.now.actualW)
        assertEquals(ValueQuality.SIMULATED, sim.now.actualQuality)
        assertNull(sim.now.differenceW)
        val none = engine(fc).build(now, 1, rows, null, false)
        assertEquals(ValueQuality.UNAVAILABLE, none.now.actualQuality)
        assertNull(none.now.actualW)
        assertNotNull("expected is still shown", none.now.expectedW)
    }

    @Test
    fun pvBelowForecastAlert() {
        val now = day.atTime(13, 0).atZone(zone).toInstant()
        val e = engine(forecast())
        val expected = e.build(now, 1, emptyList(), null, false).now.expectedW!!
        assertTrue(e.build(now, 1, emptyList(), null, false, liveActualW = expected * 0.7).alerts.any { it.kind == AlertKind.PV_BELOW })
        assertTrue(e.build(now, 1, emptyList(), null, false, liveActualW = expected * 0.9).alerts.none { it.kind == AlertKind.PV_BELOW })
    }

    // ---- Confidence
    @Test
    fun confidenceDependsOnQualityHorizonAndHistory() {
        val now = day.atTime(9, 0).atZone(zone).toInstant()
        val good = AccuracyReport(200, 0.05, 0.08, 10.0, 1.0, 92.0)
        val poor = AccuracyReport(200, 0.3, 0.4, 60.0, 30.0, 45.0)
        val fc = forecast(days = 10)
        val r = engine(fc).build(now, 10, emptyList(), good, false)
        assertEquals(ConfidenceLevel.HIGH, r.days[0].confidence.level)
        assertTrue("far days less certain", r.days.last().confidence.score < r.days[0].confidence.score)
        assertTrue(engine(fc).build(now, 1, emptyList(), poor, false).days[0].confidence.score < r.days[0].confidence.score)
        assertTrue(engine(fc).build(now, 1, emptyList(), good, true).days[0].confidence.score < r.days[0].confidence.score) // stale cache
        val broken = engine(forecast { hour(it, factor = 0.6, cloud = 50.0) }).build(now, 1, emptyList(), good, false).days[0].confidence
        assertTrue(broken.factors.any { it.startsWith("zmienne zachmurzenie") })
        assertEquals(ConfidenceLevel.LOW, engine(null).build(now, 1, emptyList(), null, false).days[0].confidence.level)
    }

    // ---- Radar
    private val radarJson = """{"version":"2.0","generated":1791460800,"host":"https://tilecache.rainviewer.com",
        "radar":{"past":[{"time":1791460200,"path":"/v2/radar/abc"},{"time":1791460800,"path":"/v2/radar/def"}],"nowcast":[]}}"""

    @Test
    fun radarFramesFreshnessAndMalformedData() {
        val fetched = Instant.ofEpochSecond(1791460900)
        val s = RainViewer.parse(radarJson, fetched)
        assertEquals(2, s.frames.size)
        assertEquals(RadarFrameKind.PAST, s.latestObserved!!.kind)
        assertEquals("https://tilecache.rainviewer.com/v2/radar/def/256/7/70/50/2/1_1.png", RainViewer.tileUrl(s.latestObserved!!, 7, 70, 50))
        val live = RadarFreshnessEvaluator.evaluate(s, Instant.ofEpochSecond(1791460800 + 7 * 60))
        assertEquals(RadarStatus.LIVE, live.status)
        assertEquals(Duration.ofMinutes(7), live.age)
        assertEquals(RadarStatus.STALE, RadarFreshnessEvaluator.evaluate(s, Instant.ofEpochSecond(1791460800 + 45 * 60)).status)
        assertEquals(RadarStatus.CACHED, RadarFreshnessEvaluator.evaluate(s, Instant.ofEpochSecond(1791460800 + 60), fromCache = true).status)
        assertEquals(RadarStatus.UNAVAILABLE, RadarFreshnessEvaluator.evaluate(null, fetched).status) // no Internet, no cache
        for (bad in listOf("", "not json", """{"host":"http://insecure","generated":1,"radar":{"past":[{"time":1,"path":"/a"}]}}""",
            """{"host":"https://x","generated":1,"radar":{"past":[]}}""", """{"host":"https://x","radar":{}}""",
            """{"host":"https://x","generated":1,"radar":{"past":[{"time":1,"path":"/../../etc"}]}}""")) {
            try { RainViewer.parse(bad, fetched); fail("accepted: $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun compassLabels() {
        assertEquals("N", HourlyPvForecastEngine.compass(359.0))
        assertEquals("SW", HourlyPvForecastEngine.compass(225.0))
        assertNull(HourlyPvForecastEngine.compass(null))
    }
}

package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.ForecastPair
import com.solartracker.pro.core.analytics.IntervalAccuracy
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.EnsembleForecast
import com.solartracker.pro.core.weather.Erbs
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.OpenMeteoEnsemble
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
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
import kotlin.math.cos

/** SYNTHETIC ensembles (clear-sky irradiance × a factor per member) – test inputs only, no provider data is invented. */
class EnsembleForecastTest {
    private val loc = GeoLocation(51.94, 15.50) // Zielona Góra
    private val zone = ZoneId.of("Europe/Warsaw")
    private val system = PvSystem(peakPowerKw = 1.5, tiltDeg = 0.0, azimuthDeg = 180.0)
    private val day = LocalDate.of(2026, 6, 20)
    private val start = day.atStartOfDay(zone).toInstant()
    private val clearSky = ClearSkyModel()

    private fun hour(end: Instant, factor: Double, temp: Double = 20.0): HourlyWeather {
        val mid = end.minus(Duration.ofMinutes(30))
        val pos = SolarCalculator.position(loc, mid)
        val irr = clearSky.irradiance(pos, mid)
        return HourlyWeather(end, irr.ghi(pos) * factor, irr.dni * factor, irr.dhi, temp, (1 - factor) * 100, windSpeedMs = 2.0)
    }

    private fun forecast(days: Int = 2, factor: (Instant) -> Double = { 1.0 }): WeatherForecast =
        WeatherForecast((1..days * 24).map { val end = start.plus(Duration.ofHours(it.toLong())); hour(end, factor(end)) }, start, zone, loc.latitude, loc.longitude)

    private fun engine(fc: WeatherForecast) = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, fc), null)

    // ---- quantiles

    @Test
    fun quantilesInterpolateLikeRType7() {
        val q = PvQuantiles.of(DoubleArray(10) { it + 1.0 })!!
        assertEquals(1.9, q.p10, 1e-9)
        assertEquals(5.5, q.p50, 1e-9)
        assertEquals(9.1, q.p90, 1e-9)
        assertEquals(5.5, q.mean, 1e-9)
        assertEquals(10, q.members)
        assertNull(PvQuantiles.of(doubleArrayOf(Double.NaN)))
        assertEquals(1, PvQuantiles.of(doubleArrayOf(Double.NaN, 3.0))!!.members) // NaN (member without data) is skipped
    }

    @Test
    fun spreadFactorFollowsAgreement() {
        assertEquals(1.0, PvQuantiles.spreadFactor(PvQuantiles(5.0, 5.0, 5.0, 5.0, 40), 0.0), 1e-9)
        assertEquals(0.9, PvQuantiles.spreadFactor(PvQuantiles(4.5, 5.0, 5.5, 5.0, 40), 0.0), 1e-9)
        assertEquals(0.4, PvQuantiles.spreadFactor(PvQuantiles(0.0, 1.0, 9.0, 3.0, 40), 0.0), 1e-9)
        // Floor: a dark day (median 0.1 kWh) is judged against 15 % of the clear-sky energy, not against ~0.
        assertTrue(PvQuantiles.spreadFactor(PvQuantiles(0.05, 0.1, 0.3, 0.1, 40), 1.5) > 0.9)
    }

    // ---- Erbs and beam/diffuse split

    @Test
    fun erbsMatchesPublishedCorrelation() {
        assertEquals(0.991, Erbs.diffuseFraction(0.1), 1e-3)
        assertEquals(0.165, Erbs.diffuseFraction(0.9), 1e-9)
        // Continuous at the breakpoints (within the correlation's own rounding).
        assertEquals(Erbs.diffuseFraction(0.2199), Erbs.diffuseFraction(0.2201), 0.01)
        assertEquals(Erbs.diffuseFraction(0.7999), Erbs.diffuseFraction(0.8001), 0.01)
        assertTrue(Erbs.diffuseFraction(0.75) < Erbs.diffuseFraction(0.4))
    }

    @Test
    fun splitPreservesGlobalIrradiance() {
        val end = start.plus(Duration.ofHours(12)) // hour 11–12 local (summer, sun high)
        val ghi = 600.0
        for (dhiGiven in listOf<Double?>(150.0, null)) {
            val (dni, dhi) = OpenMeteoEnsemble.split(loc, end, ghi, dhiGiven)
            var cosSum = 0.0
            for (k in 0 until 6) {
                val p = SolarCalculator.position(loc, end.minusSeconds(3600L - (k * 3600L + 1800L) / 6))
                if (p.isAboveHorizon) cosSum += cos(Math.toRadians(p.zenithDeg))
            }
            assertEquals("GHI = DNI·<cos z> + DHI", ghi, dni * cosSum / 6 + dhi, 1e-6)
            assertTrue(dhi in 0.0..ghi)
            if (dhiGiven != null) assertEquals(150.0, dhi, 1e-6)
        }
        // Night: nothing.
        assertEquals(0.0 to 0.0, OpenMeteoEnsemble.split(loc, start.plus(Duration.ofHours(1)), 0.0, null))
    }

    // ---- parsing

    private fun json(members: Int, withDiffuse: Boolean = true, hours: Int = 48, modelSuffix: String = ""): String {
        val times = (1..hours).map { start.plus(Duration.ofHours(it.toLong())) }
        val sb = StringBuilder("{\"latitude\":51.94,\"longitude\":15.5,\"timezone\":\"Europe/Warsaw\",\"hourly\":{")
        sb.append("\"time\":[").append(times.joinToString(",") { it.epochSecond.toString() }).append("]")
        for (m in 0 until members) {
            val suffix = (if (m == 0) "" else "_member%02d".format(m)) + modelSuffix
            val factor = 0.4 + 0.1 * m
            val ghi = times.map { "%.1f".format(java.util.Locale.ROOT, hour(it, factor).let { h -> h.ghi!! }) }
            sb.append(",\"shortwave_radiation$suffix\":[").append(ghi.joinToString(",")).append("]")
            if (withDiffuse) sb.append(",\"diffuse_radiation$suffix\":[").append(times.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, hour(it, factor).dhi!!) }).append("]")
            sb.append(",\"temperature_2m$suffix\":[").append(times.joinToString(",") { "18.5" }).append("]")
            sb.append(",\"cloud_cover$suffix\":[").append(times.joinToString(",") { if (m == 1) "null" else "40" }).append("]")
        }
        return sb.append("}}").toString()
    }

    @Test
    fun memberKeysAreSplit() {
        val names = listOf("shortwave_radiation", "diffuse_radiation", "temperature_2m", "cloud_cover")
        assertEquals("shortwave_radiation" to 0, OpenMeteoEnsemble.splitKey("shortwave_radiation", names))
        assertEquals("shortwave_radiation" to 7, OpenMeteoEnsemble.splitKey("shortwave_radiation_member07", names))
        assertEquals("cloud_cover" to 39, OpenMeteoEnsemble.splitKey("cloud_cover_member39_icon_seamless_eps", names))
        assertEquals("temperature_2m" to 0, OpenMeteoEnsemble.splitKey("temperature_2m_ecmwf_ifs025", names))
        assertNull(OpenMeteoEnsemble.splitKey("time", names))
        assertNull(OpenMeteoEnsemble.splitKey("wind_speed_10m_member01", names))
    }

    @Test
    fun parsesMembersWithAndWithoutDiffuse() {
        val e = OpenMeteoEnsemble.parse(json(8), start, loc, "icon_seamless_eps")
        assertEquals(8, e.size)
        assertEquals(ZoneId.of("Europe/Warsaw"), e.zone)
        assertTrue(e.diffuseFromModel)
        assertEquals("DWD ICON-EPS", e.label)
        val noon = start.plus(Duration.ofHours(12))
        val h = e.members[3].at(noon)!!
        assertTrue(h.hasIrradiance)
        assertEquals(18.5, h.temperatureC!!, 1e-9)
        assertNull("null in the response stays null", e.members[1].at(noon)!!.cloudCoverPercent)

        val noDiffuse = OpenMeteoEnsemble.parse(json(6, withDiffuse = false, modelSuffix = "_icon_seamless_eps"), start, loc, "icon_seamless_eps")
        assertFalse(noDiffuse.diffuseFromModel)
        noDiffuse.members.forEach { m -> m.hours.filter { it.hasIrradiance }.forEach { assertTrue(it.dhi!! <= it.ghi!! + 1e-6) } }
    }

    @Test
    fun tooFewMembersOrBadJsonIsRejected() {
        for (bad in listOf(json(3), "{\"error\":true,\"reason\":\"No data\"}", "not json", "{\"hourly\":{}}")) {
            try {
                OpenMeteoEnsemble.parse(bad, start, loc, "icon_seamless_eps"); fail("accepted: ${bad.take(40)}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---- PV per member

    @Test
    fun identicalMembersCollapseToTheDeterministicForecast() {
        val base = forecast { 0.7 }
        val det = engine(base)
        val ens = EnsembleForecast("test", start, List(6) { base })
        val result = EnsemblePvEngine(loc, det, ens, base).compute(start, start.plus(Duration.ofDays(1)), start)
        val noon = start.plus(Duration.ofHours(12))
        val q = result.hour(noon)!!
        val expected = (0 until 6).sumOf { det.at(noon.plusSeconds(300L + it * 600L), start, null).expectedKw } / 6 * 1000
        assertEquals(expected, q.p10, 1e-6)
        assertEquals(expected, q.p90, 1e-6)
        assertEquals(6, q.members)
        val dayQ = result.day(day, zone)!!
        assertEquals(dayQ.p10, dayQ.p90, 1e-9)
        assertTrue(dayQ.p50 > 1.0)
        // Night hours are zero, not missing.
        assertEquals(0.0, result.hour(start.plus(Duration.ofHours(1)))!!.p90, 1e-12)
    }

    @Test
    fun spreadOfMembersBecomesTheRange() {
        val base = forecast { 0.6 }
        val members = (0 until 10).map { m -> forecast { 0.2 + 0.08 * m } }
        val det = engine(base)
        val result = EnsemblePvEngine(loc, det, EnsembleForecast("test", start, members), base).compute(start, start.plus(Duration.ofDays(2)), start)
        val noon = start.plus(Duration.ofHours(12))
        val q = result.hour(noon)!!
        assertTrue(q.p10 < q.p50 && q.p50 < q.p90)
        val darkest = (0 until 6).sumOf { engine(members.first()).at(noon.plusSeconds(300L + it * 600L), start, null).expectedKw } / 6 * 1000
        assertTrue("P10 above the darkest member", q.p10 >= darkest - 1e-6)

        // Energies are summed per member BEFORE taking quantiles: never wider than adding up hourly quantiles.
        val dayQ = result.day(day, zone)!!
        val hourStarts = (0 until 24).map { start.plus(Duration.ofHours(it.toLong())) }
        assertTrue(dayQ.p90 <= hourStarts.sumOf { result.hour(it)!!.p90 } / 1000 + 1e-6)
        assertTrue(dayQ.p10 >= hourStarts.sumOf { result.hour(it)!!.p10 } / 1000 - 1e-6)

        // Probability of reaching an energy falls as the target rises.
        val dayStart = start
        val dayEnd = start.plus(Duration.ofDays(1))
        val pLow = result.probabilityAtLeast(dayStart, dayEnd, dayQ.p10)!!
        val pHigh = result.probabilityAtLeast(dayStart, dayEnd, dayQ.p90)!!
        assertTrue(pLow >= pHigh)
        assertEquals(1.0, result.probabilityAtLeast(dayStart, dayEnd, 0.0)!!, 1e-12)
        assertEquals(0.0, result.probabilityAtLeast(dayStart, dayEnd, 1e6)!!, 1e-12)

        // Partial period: half of the noon hour is half of its energy for every member.
        val half = result.energy(noon, noon.plusSeconds(1800))!!
        assertEquals(q.p50 / 2 / 1000, half.p50, 1e-9)
        // Outside the ensemble: no range.
        assertNull(result.energy(start.plus(Duration.ofDays(2)), start.plus(Duration.ofDays(3))))
        assertNull(result.day(day.plusDays(5), zone))
    }

    @Test
    fun hourlyReportUsesTheEnsembleRangeAndSpreadConfidence() {
        val base = forecast { 0.6 }
        val members = (0 until 10).map { m -> forecast { 0.2 + 0.08 * m } }
        val det = engine(base)
        val weather = WeatherAwareIrradianceModel(loc, base)
        val result = EnsemblePvEngine(loc, det, EnsembleForecast("icon_seamless_eps", start, members), base).compute(start, start.plus(Duration.ofDays(2)), start)
        val est = PvEstimator(weather)
        val report = HourlyPvForecastEngine(loc, base, weather::sourceAt, { t -> det.at(t, t) }, null, { t -> est.pointEstimate(system, loc, t) }, zone,
            ensemble = result).build(start, 2, emptyList(), null, false)
        val noon = report.hours.first { it.start == start.plus(Duration.ofHours(12)) }
        assertNotNull(noon.ensemble)
        assertEquals(noon.ensemble!!.p10, noon.expectedMinW, 1e-9)
        assertEquals(noon.ensemble!!.p90, noon.expectedMaxW, 1e-9)
        val today = report.days.first()
        assertNotNull(today.ensemble)
        assertEquals("DWD ICON-EPS", today.ensembleModel)
        assertEquals(10, report.ensembleMembers)
        assertTrue(today.confidence.factors.any { "scenariuszy" in it })
        assertFalse(today.confidence.factors.any { it.startsWith("horyzont") })

        // Without an ensemble the old rules of thumb remain.
        val plain = HourlyPvForecastEngine(loc, base, weather::sourceAt, { t -> det.at(t, t) }, null, { t -> est.pointEstimate(system, loc, t) }, zone)
            .build(start, 2, emptyList(), null, false)
        assertNull(plain.days.first().ensemble)
        assertTrue(plain.days.first().confidence.factors.any { it.startsWith("horyzont") })
        assertEquals(WeatherSource.FORECAST, plain.days.first().weatherSource)
    }

    // ---- range coverage

    @Test
    fun coverageCountsHitsAndSides() {
        val t = (0 until 5).map { start.plus(Duration.ofHours(it.toLong())) }
        val pairs = listOf(
            ForecastPair(t[0], 1.0, 1.0), // inside
            ForecastPair(t[1], 1.0, 0.2), // below
            ForecastPair(t[2], 1.0, 2.5), // above
            ForecastPair(t[3], 0.0, 0.0), // night range → skipped
            ForecastPair(t[4], 1.0, 1.4), // inside
        )
        val bounds = mapOf(t[0] to (0.5 to 1.5), t[1] to (0.5 to 1.5), t[2] to (0.5 to 1.5), t[3] to (0.0 to 0.0), t[4] to (0.5 to 1.5))
        val c = IntervalAccuracy.coverage(pairs, bounds)
        assertEquals(4, c.count)
        assertEquals(2, c.inside)
        assertEquals(1, c.below)
        assertEquals(1, c.above)
        assertEquals(50.0, c.percent!!, 1e-9)
        assertNull(IntervalAccuracy.coverage(emptyList(), emptyMap()).percent)
    }
}

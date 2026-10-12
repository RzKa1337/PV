package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.MonthlyClimate
import com.solartracker.pro.core.weather.OpenMeteo
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
import java.time.ZoneId

/** Production typed in by the user: validation, storage, past-day model estimates and the comparison. */
class ManualProductionTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 3.0, tiltDeg = 30.0, azimuthDeg = 180.0)
    private val today = LocalDate.of(2026, 10, 12)

    private fun day(d: Int, kwh: Double) = ManualProductionEntry(LocalDate.of(2026, 10, d), LocalDate.of(2026, 10, d), kwh)
    private fun period(from: Int, to: Int, kwh: Double) = ManualProductionEntry(LocalDate.of(2026, 10, from), LocalDate.of(2026, 10, to), kwh)

    // ---------------------------------------------------------------- validation

    @Test
    fun validate_acceptsFinishedDaysAndPeriods() {
        assertNull(ManualProduction.validate(day(11, 7.5), today, emptyList(), 3.0))
        assertNull(ManualProduction.validate(period(1, 11, 60.0), today, emptyList(), 3.0))
        assertNull("zero is a real reading (snow, switched off)", ManualProduction.validate(day(11, 0.0), today, emptyList(), 3.0))
        assertEquals(11, period(1, 11, 60.0).days)
    }

    @Test
    fun validate_namesEachProblem() {
        fun check(e: ManualProductionEntry, existing: List<ManualProductionEntry> = emptyList()) = ManualProduction.validate(e, today, existing, 3.0)
        assertEquals(ManualEntryProblem.INVALID_ENERGY, check(day(11, -1.0)))
        assertEquals(ManualEntryProblem.INVALID_ENERGY, check(day(11, Double.NaN)))
        assertEquals(ManualEntryProblem.INVALID_ENERGY, check(day(11, Double.POSITIVE_INFINITY)))
        assertEquals(ManualEntryProblem.END_BEFORE_START, check(period(11, 10, 5.0)))
        assertEquals("today is not over", ManualEntryProblem.NOT_FINISHED, check(day(12, 5.0)))
        assertEquals(ManualEntryProblem.NOT_FINISHED, check(period(10, 13, 5.0)))
        assertEquals(ManualEntryProblem.TOO_LONG, check(ManualProductionEntry(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 10, 11), 3000.0)))
        assertEquals(ManualEntryProblem.OVERLAP, check(period(5, 8, 20.0), listOf(day(8, 5.0))))
        assertEquals(ManualEntryProblem.OVERLAP, check(day(6, 5.0), listOf(period(5, 8, 20.0))))
        assertNull("touching periods do not overlap", check(period(5, 7, 20.0), listOf(day(8, 5.0))))
        // 3 kWp cannot make 7500 kWh in a day: Wh typed as kWh.
        assertEquals(ManualEntryProblem.IMPLAUSIBLE, check(day(11, 7500.0)))
        assertNull("no array size, no plausibility check", ManualProduction.validate(day(11, 7500.0), today, emptyList(), null))
    }

    @Test
    fun parseDate_acceptsIsoAndDayFirst() {
        val d = LocalDate.of(2026, 10, 5)
        for (text in listOf("2026-10-05", " 2026-10-05 ", "5.10.2026", "05.10.2026", "05/10/2026")) assertEquals(text, d, ManualProduction.parseDate(text))
        for (text in listOf("", "wczoraj", "2026-13-01", "31.02.2026", "5.10.26", "10-05-2026", "2026/10/05")) assertNull(text, ManualProduction.parseDate(text))
    }

    @Test
    fun add_keepsNewestFirst() {
        val list = ManualProduction.add(ManualProduction.add(listOf(day(5, 1.0)), day(9, 2.0)), day(7, 3.0))
        assertEquals(listOf(9, 7, 5), list.map { it.from.dayOfMonth })
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun codec_roundTrips() {
        val entries = listOf(period(8, 11, 31.25), day(5, 0.0), day(1, 12.3456789))
        assertEquals(entries, ManualProductionCodec.decode(ManualProductionCodec.encode(entries)))
        assertTrue(ManualProductionCodec.decode("").isEmpty())
        assertEquals("", ManualProductionCodec.encode(emptyList()))
    }

    @Test
    fun codec_skipsBrokenLinesWithoutInventingData() {
        val text = listOf(
            "2026-10-05;2026-10-05;7.5",
            "garbage",
            "2026-10-06;2026-10-06;abc",
            "2026-13-40;2026-10-06;1",
            "2026-10-07;2026-10-06;1",      // ends before it starts
            "2026-10-08;2026-10-08;-3",     // negative
            "2026-10-08;2026-10-08;NaN",
            "2026-10-05;2026-10-05;99",     // same day again: the first line wins
            "2026-10-09;2026-10-09;4;extra",
            "  2026-10-10;2026-10-10;6.5  ",
        ).joinToString("\n")
        assertEquals(listOf(day(10, 6.5), day(5, 7.5)), ManualProductionCodec.decode(text))
    }

    // ---------------------------------------------------------------- past-day estimates

    /** Clear-sky hours covering local days [from]…[to] of October 2026, scaled by [factor]. */
    private fun hours(from: Int, to: Int, factor: Double = 1.0): List<HourlyWeather> {
        val cs = ClearSkyModel()
        val start = LocalDate.of(2026, 10, from).atStartOfDay(zone).toInstant()
        val count = Duration.between(start, LocalDate.of(2026, 10, to).plusDays(1).atStartOfDay(zone).toInstant()).toHours().toInt()
        return (1..count).map { i ->
            val end = start.plus(Duration.ofHours(i.toLong()))
            val mid = end.minusSeconds(1800)
            val pos = SolarCalculator.position(loc, mid)
            val irr = cs.irradiance(pos, mid)
            HourlyWeather(end, null, irr.dni * factor, irr.dhi * factor, 12.0, 0.0)
        }
    }

    private fun estimator(hours: List<HourlyWeather>?, climate: MonthlyClimate? = null) =
        PastDayEstimator(WeatherAwareIrradianceModel(loc, hours?.let { WeatherForecast(it, Instant.EPOCH, zone) }, climate), system, loc, zone)

    @Test
    fun pastDay_basisIsTheWorstDaylightHour() {
        val d = LocalDate.of(2026, 10, 5)
        assertEquals(EstimateBasis.WEATHER, estimator(hours(5, 5)).estimate(d).basis)
        assertEquals(EstimateBasis.CLIMATE, estimator(null, MonthlyClimate.DEFAULT_POLAND).estimate(d).basis)
        assertEquals(EstimateBasis.CLEAR_SKY, estimator(null).estimate(d).basis)
        // Weather only until noon: the afternoon comes from the climate, so the day is not "weather based".
        val morning = hours(5, 5).filter { it.endTime.atZone(zone).hour in 1..12 }
        assertEquals(EstimateBasis.CLIMATE, estimator(morning, MonthlyClimate.DEFAULT_POLAND).estimate(d).basis)
        assertEquals(EstimateBasis.CLEAR_SKY, estimator(morning).estimate(d).basis)
        // The neighbouring day has no weather at all.
        assertEquals(EstimateBasis.CLEAR_SKY, estimator(hours(5, 5)).estimate(d.plusDays(1)).basis)
    }

    @Test
    fun pastDay_energyFollowsTheWeatherOfThatDay() {
        val d = LocalDate.of(2026, 10, 5)
        val clear = estimator(hours(5, 5)).estimate(d).kwh
        val cloudy = estimator(hours(5, 5, factor = 0.25)).estimate(d).kwh
        assertTrue("clear October day for 3 kWp at 30°: $clear kWh", clear in 8.0..16.0)
        // Less irradiance also means cooler cells, so slightly more than a quarter.
        assertEquals(0.25, cloudy / clear, 0.03)
    }

    @Test
    fun archive_urlAndRealResponse() {
        val url = OpenMeteo.archiveHourlyUrl(loc, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        assertTrue(url.startsWith("https://archive-api.open-meteo.com/v1/archive?latitude=52.2300&longitude=21.0100&start_date=2026-10-01&end_date=2026-10-07"))
        assertTrue("direct_normal_irradiance" in url && "diffuse_radiation" in url && "timezone=auto" in url && "timeformat=unixtime" in url && "wind_speed_unit=ms" in url)
        // Real archive answer for Warsaw, 1–7 October 2026, captured 2026-10-12 with exactly this request.
        val f = OpenMeteo.parseForecast(javaClass.getResource("/open-meteo-warsaw-archive.json")!!.readText(), Instant.EPOCH)
        assertEquals(7 * 24, f.hours.size)
        assertEquals(zone, f.zone)
        assertTrue(f.hours.all { it.hasIrradiance && it.temperatureC != null })
        val est = PastDayEstimator(WeatherAwareIrradianceModel(loc, f), system, loc, zone)
        for (i in 1..7) {
            val d = LocalDate.of(2026, 10, i)
            val e = est.estimate(d)
            // The provider's first hour ends at 00:00 local time, so the whole local day is covered.
            assertEquals("day $d", EstimateBasis.WEATHER, e.basis)
            // Horizontal irradiation of the day from the same data [kWh/m²]; a 30° south array in October collects
            // between about the same and twice as much, and the system delivers roughly 80 % of that.
            val ghi = f.hours.filter { it.endTime.minusSeconds(1).atZone(zone).toLocalDate() == d }.sumOf { h ->
                val mid = h.endTime.minusSeconds(1800)
                h.dni!! * kotlin.math.cos(Math.toRadians(SolarCalculator.position(loc, mid).zenithDeg)).coerceAtLeast(0.0) + h.dhi!!
            } / 1000
            val perKwp = e.kwh / system.peakPowerKw
            assertTrue("day $d: $perKwp kWh/kWp from $ghi kWh/m²", perKwp in ghi * 0.7..ghi * 1.9)
        }
        assertEquals("the day after the archive ends", EstimateBasis.CLEAR_SKY, est.estimate(LocalDate.of(2026, 10, 8)).basis)
    }

    // ---------------------------------------------------------------- comparison

    private fun weather(kwh: Double) = { _: LocalDate -> DayEstimate(kwh, EstimateBasis.WEATHER) }

    @Test
    fun compare_rowsHaveDifferenceAndYield() {
        val c = ManualProductionAnalyzer.compare(listOf(day(5, 9.0), period(6, 9, 44.0)), 3.0, weather(10.0))
        val (p, d) = c.rows // newest first
        assertEquals(40.0, p.estimatedKwh!!, 1e-9)
        assertEquals(10.0, p.differencePercent!!, 1e-9)
        assertEquals(44.0 / 3.0 / 4, p.yieldKwhPerKwpDay!!, 1e-9)
        assertEquals(-10.0, d.differencePercent!!, 1e-9)
        assertEquals(EstimateBasis.WEATHER, d.basis)
        val s = c.weatherBased!!
        assertEquals(2, s.entries)
        assertEquals(5, s.days)
        assertEquals(53.0 / 50.0, s.ratio!!, 1e-9) // weighted by energy, not the mean of 0.9 and 1.1
        assertNull(c.other)
    }

    @Test
    fun compare_offersAFactorOnlyWithEnoughWeatherDays() {
        val thirteen = ManualProductionAnalyzer.compare(listOf(ManualProductionEntry(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 13), 13 * 9.0)), 3.0, weather(10.0))
        assertNull(thirteen.suggestedFactor)
        assertEquals(1, thirteen.daysMissingForFactor)
        assertEquals(0.9, thirteen.weatherBased!!.ratio!!, 1e-9)
        val fourteen = ManualProductionAnalyzer.compare(listOf(ManualProductionEntry(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14), 14 * 9.0)), 3.0, weather(10.0))
        assertEquals(0.9, fourteen.suggestedFactor!!, 1e-9)
        assertEquals(0, fourteen.daysMissingForFactor)
        assertFalse(fourteen.ratioImplausible)
    }

    @Test
    fun compare_climateAndClearSkyDaysNeverFeedTheFactor() {
        val entries = (1..11).map { day(it, 5.0) } + ManualProductionEntry(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 150.0)
        val c = ManualProductionAnalyzer.compare(entries, 3.0) { d ->
            if (d.monthValue == 10) DayEstimate(10.0, EstimateBasis.WEATHER) else DayEstimate(10.0, if (d.dayOfMonth == 15) EstimateBasis.CLEAR_SKY else EstimateBasis.CLIMATE)
        }
        assertEquals(11, c.weatherBased!!.days)
        assertEquals(30, c.other!!.days)
        assertEquals("one clear-sky day makes the whole period clear-sky", EstimateBasis.CLEAR_SKY, c.rows.last().basis)
        assertNull("41 days entered, only 11 with real weather", c.suggestedFactor)
        assertEquals(3, c.daysMissingForFactor)
    }

    @Test
    fun compare_implausibleRatioIsFlaggedNotOffered() {
        val e = ManualProductionEntry(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20), 20 * 2.0)
        val c = ManualProductionAnalyzer.compare(listOf(e), 3.0, weather(10.0)) // 20 % of the model
        assertNull(c.suggestedFactor)
        assertTrue(c.ratioImplausible)
        assertEquals(0.2, c.weatherBased!!.ratio!!, 1e-9)
    }

    @Test
    fun compare_missingOrTinyEstimatesGiveNoRatio() {
        // No estimate for one of the days: the whole entry has none (never a partial sum presented as the period).
        val gap = ManualProductionAnalyzer.compare(listOf(period(5, 7, 20.0)), 3.0) { if (it.dayOfMonth == 6) null else DayEstimate(10.0, EstimateBasis.WEATHER) }
        assertNull(gap.rows.single().estimatedKwh)
        assertNull(gap.rows.single().basis)
        assertNull(gap.rows.single().ratio)
        assertNull(gap.weatherBased)
        assertEquals(ManualProductionAnalyzer.MIN_DAYS_FOR_FACTOR, gap.daysMissingForFactor)
        // Polar-night-like day: the model says ~0, so a ratio would be noise.
        val dark = ManualProductionAnalyzer.compare(listOf(day(5, 0.3)), 3.0, weather(0.05))
        assertEquals(0.05, dark.rows.single().estimatedKwh!!, 1e-9)
        assertNull(dark.rows.single().ratio)
        // Without a configured array the yield is unknown, the comparison still works.
        val noArray = ManualProductionAnalyzer.compare(listOf(day(5, 9.0)), 0.0, weather(10.0))
        assertNull(noArray.rows.single().yieldKwhPerKwpDay)
        assertEquals(0.9, noArray.rows.single().ratio!!, 1e-9)
        assertTrue(ManualProductionAnalyzer.compare(emptyList(), 3.0, weather(10.0)).rows.isEmpty())
    }

    @Test
    fun endToEnd_readingsTenPercentBelowTheModelGiveFactor09() {
        val est = estimator(hours(1, 11, factor = 0.6))
        val entries = (1..11).map { d -> day(d, est.estimate(LocalDate.of(2026, 10, d)).kwh * 0.9) } +
            ManualProductionEntry(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 250.0) // no weather for September
        val c = ManualProductionAnalyzer.compare(entries, system.peakPowerKw) { est.estimate(it) }
        assertEquals(0.9, c.weatherBased!!.ratio!!, 1e-9)
        assertEquals(11, c.weatherBased!!.days)
        assertNull("11 weather days are not enough yet", c.suggestedFactor)
        assertEquals(EstimateBasis.CLEAR_SKY, c.rows.last().basis)
    }
}

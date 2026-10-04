package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PvEstimatorTest {

    private val estimator = PvEstimator()
    private val warsaw = GeoLocation(52.2297, 21.0122)
    private val warsawZone = ZoneId.of("Europe/Warsaw")
    private val sydney = GeoLocation(-33.8688, 151.2093)
    private val sydneyZone = ZoneId.of("Australia/Sydney")
    private val quito = GeoLocation(-0.1807, -78.4678)
    private val quitoZone = ZoneId.of("America/Guayaquil")
    private val tromso = GeoLocation(69.6492, 18.9553)
    private val oslo = ZoneId.of("Europe/Oslo")

    private val defaultSystem = PvSystem()
    private val june21 = LocalDate.of(2024, 6, 21)
    private val dec21 = LocalDate.of(2024, 12, 21)

    private fun warsawTime(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), warsawZone).toInstant()

    // --- Instant power ---

    @Test
    fun power_isZeroAtNight() {
        assertEquals(0.0, estimator.powerKw(defaultSystem, warsaw, warsawTime(june21, 1)), 0.0)
        assertEquals(0.0, estimator.powerKw(defaultSystem, warsaw, warsawTime(dec21, 20)), 0.0)
    }

    @Test
    fun power_atNoonIsPositiveAndBelowPeak() {
        val noon = SolarCalculator.sunTimes(warsaw, june21).solarNoon
        val p = estimator.powerKw(defaultSystem, warsaw, noon)
        assertTrue("power $p", p > 0.5 * defaultSystem.peakPowerKw)
        assertTrue("power $p", p <= defaultSystem.peakPowerKw)
    }

    @Test
    fun power_scalesLinearlyWithPeakPower() {
        val t = warsawTime(june21, 11)
        val small = estimator.powerKw(PvSystem(peakPowerKw = 2.0), warsaw, t)
        val big = estimator.powerKw(PvSystem(peakPowerKw = 4.0), warsaw, t)
        assertEquals(2.0 * small, big, 1e-9)
    }

    @Test
    fun power_zeroPeakPowerGivesZero() {
        assertEquals(0.0, estimator.powerKw(PvSystem(peakPowerKw = 0.0), warsaw, warsawTime(june21, 12)), 0.0)
    }

    @Test
    fun power_scalesWithPerformanceRatio() {
        val t = warsawTime(june21, 12)
        val full = estimator.powerKw(PvSystem(performanceRatio = 1.0), warsaw, t)
        val half = estimator.powerKw(PvSystem(performanceRatio = 0.5), warsaw, t)
        assertEquals(full / 2.0, half, 1e-9)
    }

    @Test
    fun power_eastPanelsWinMorningWestPanelsWinAfternoon() {
        val east = PvSystem(tiltDeg = 40.0, azimuthDeg = 90.0)
        val west = PvSystem(tiltDeg = 40.0, azimuthDeg = 270.0)
        val morning = warsawTime(june21, 8)
        val evening = warsawTime(june21, 18)
        assertTrue(estimator.powerKw(east, warsaw, morning) > estimator.powerKw(west, warsaw, morning))
        assertTrue(estimator.powerKw(west, warsaw, evening) > estimator.powerKw(east, warsaw, evening))
    }

    // --- Daily energy ---

    @Test
    fun dailyEnergy_warsawSummerFlatIsPlausible() {
        val kwh = estimator.dailyEnergyKwh(defaultSystem, warsaw, june21, warsawZone)
        // Clear-sky June in central Europe: ~7–8 kWh/m² on a horizontal plane.
        assertTrue("kWh $kwh", kwh in 9.0..16.0)
    }

    @Test
    fun dailyEnergy_warsawWinterFlatIsMuchLower() {
        val winter = estimator.dailyEnergyKwh(defaultSystem, warsaw, dec21, warsawZone)
        val summer = estimator.dailyEnergyKwh(defaultSystem, warsaw, june21, warsawZone)
        assertTrue("kWh $winter", winter in 0.3..4.0)
        assertTrue(summer > 4 * winter)
    }

    @Test
    fun dailyEnergy_isZeroDuringPolarNight() {
        assertEquals(0.0, estimator.dailyEnergyKwh(defaultSystem, tromso, dec21, oslo), 1e-9)
    }

    @Test
    fun dailyEnergy_polarDayProducesAtMidnight() {
        val midnightSun = ZonedDateTime.of(june21, LocalTime.of(0, 30), oslo).toInstant()
        val south = PvSystem(tiltDeg = 90.0, azimuthDeg = 0.0) // facing north towards the midnight sun
        assertTrue(estimator.powerKw(south, tromso, midnightSun) > 0.0)
        assertTrue(estimator.dailyEnergyKwh(defaultSystem, tromso, june21, oslo) > 5.0)
    }

    @Test
    fun dailyEnergy_matchesIntegratedProfile() {
        val profile = estimator.dailyProfile(defaultSystem, warsaw, june21, warsawZone, stepMinutes = 5)
        val trapezoid = profile.zipWithNext { a, b ->
            val hours = (b.time.toEpochMilli() - a.time.toEpochMilli()) / 3_600_000.0
            (a.powerKw + b.powerKw) / 2 * hours
        }.sum()
        val integrated = estimator.dailyEnergyKwh(defaultSystem, warsaw, june21, warsawZone)
        assertEquals(integrated, trapezoid, integrated * 0.01)
    }

    @Test
    fun energyBetween_emptyOrReversedRangeIsZero() {
        val t = warsawTime(june21, 12)
        assertEquals(0.0, estimator.energyKwh(defaultSystem, warsaw, t, t), 0.0)
        assertEquals(0.0, estimator.energyKwh(defaultSystem, warsaw, t, t.minusSeconds(3600)), 0.0)
    }

    @Test
    fun energyBetween_partsSumToWholeDay() {
        val start = june21.atStartOfDay(warsawZone).toInstant()
        val mid = warsawTime(june21, 13, 17)
        val end = june21.plusDays(1).atStartOfDay(warsawZone).toInstant()
        val sum = estimator.energyKwh(defaultSystem, warsaw, start, mid) +
            estimator.energyKwh(defaultSystem, warsaw, mid, end)
        assertEquals(estimator.dailyEnergyKwh(defaultSystem, warsaw, june21, warsawZone), sum, 0.02)
    }

    // --- Profile ---

    @Test
    fun dailyProfile_coversWholeDayAndPeaksAroundSolarNoon() {
        val profile = estimator.dailyProfile(defaultSystem, warsaw, june21, warsawZone, stepMinutes = 15)
        assertEquals(97, profile.size)
        assertEquals(june21.atStartOfDay(warsawZone).toInstant(), profile.first().time)
        val peak = profile.maxBy { it.powerKw }
        val noon = SolarCalculator.sunTimes(warsaw, june21).solarNoon
        assertTrue(kotlin.math.abs(peak.time.epochSecond - noon.epochSecond) <= 15 * 60)
    }

    @Test
    fun dailyProfile_handlesDaylightSavingTimeChange() {
        // 2024-03-31 lasts 23 hours in Warsaw.
        val profile = estimator.dailyProfile(defaultSystem, warsaw, LocalDate.of(2024, 3, 31), warsawZone, 15)
        assertEquals(93, profile.size)
    }

    // --- Tilt and azimuth ---

    @Test
    fun compareTilts_returnsAllRequestedAnglesWithComputedValues() {
        val result = estimator.compareTilts(defaultSystem, warsaw, LocalDate.of(2024, 3, 20), warsawZone)
        assertEquals(PvEstimator.COMPARISON_TILTS, result.map { it.tiltDeg })
        assertTrue(result.all { it.energyKwh > 0.0 })
        assertTrue("values must not be constant", result.map { it.energyKwh }.distinct().size == result.size)
    }

    @Test
    fun compareTilts_tiltedSouthBeatsFlatInWinter() {
        val result = estimator.compareTilts(defaultSystem, warsaw, dec21, warsawZone).associate { it.tiltDeg to it.energyKwh }
        assertTrue(result.getValue(60.0) > result.getValue(0.0) * 1.5)
        assertTrue(result.getValue(90.0) > result.getValue(0.0))
    }

    @Test
    fun compareTilts_verticalLosesToModerateTiltInSummer() {
        val result = estimator.compareTilts(defaultSystem, warsaw, june21, warsawZone).associate { it.tiltDeg to it.energyKwh }
        assertTrue(result.getValue(20.0) > result.getValue(90.0))
    }

    @Test
    fun azimuth_southBeatsNorthInNorthernHemisphere() {
        val south = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 30.0, azimuthDeg = 180.0), warsaw, june21, warsawZone)
        val north = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 30.0, azimuthDeg = 0.0), warsaw, june21, warsawZone)
        assertTrue(south > north)
    }

    @Test
    fun azimuth_northBeatsSouthInSouthernHemisphere() {
        val date = LocalDate.of(2024, 6, 21)
        val north = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 30.0, azimuthDeg = 0.0), sydney, date, sydneyZone)
        val south = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 30.0, azimuthDeg = 180.0), sydney, date, sydneyZone)
        assertTrue(north > 2 * south)
    }

    @Test
    fun azimuth_isIrrelevantForFlatPanels() {
        val a = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 0.0, azimuthDeg = 0.0), warsaw, june21, warsawZone)
        val b = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 0.0, azimuthDeg = 270.0), warsaw, june21, warsawZone)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun equator_flatBeatsSteepTiltOverYear() {
        val monthly = estimator.monthlyEnergy(defaultSystem, quito, 2024, quitoZone, tilts = listOf(0.0, 60.0))
        assertTrue(monthly[0].yearlyKwh > monthly[1].yearlyKwh)
    }

    @Test
    fun verticalNorthFacingPanelStillGetsSomeDiffuseLight() {
        val kwh = estimator.dailyEnergyKwh(PvSystem(tiltDeg = 90.0, azimuthDeg = 0.0), warsaw, dec21, warsawZone)
        assertTrue(kwh > 0.0)
        assertTrue(kwh < estimator.dailyEnergyKwh(PvSystem(tiltDeg = 90.0, azimuthDeg = 180.0), warsaw, dec21, warsawZone))
    }

    // --- Monthly ---

    @Test
    fun monthlyEnergy_hasTwelveMonthsForEachTilt() {
        val result = estimator.monthlyEnergy(defaultSystem, warsaw, 2024, warsawZone)
        assertEquals(PvEstimator.MONTHLY_TILTS, result.map { it.tiltDeg })
        result.forEach { estimate ->
            assertEquals(12, estimate.energyByMonthKwh.size)
            assertTrue(estimate.energyByMonthKwh.values.all { it > 0.0 })
        }
        val flat = result.first().energyByMonthKwh
        assertTrue(flat.getValue(Month.JUNE) > 4 * flat.getValue(Month.DECEMBER))
    }

    @Test
    fun monthlyEnergy_sumIsConsistentWithDailyEnergy() {
        val june = estimator.monthlyEnergy(defaultSystem, warsaw, 2024, warsawZone, tilts = listOf(0.0))[0]
            .energyByMonthKwh.getValue(Month.JUNE)
        val daily = (1..30).sumOf { estimator.dailyEnergyKwh(defaultSystem, warsaw, LocalDate.of(2024, 6, it), warsawZone) }
        assertEquals(daily, june, daily * 0.01)
    }

    @Test
    fun monthlyEnergy_tiltedSouthWinsInWinterMonths() {
        val result = estimator.monthlyEnergy(defaultSystem, warsaw, 2024, warsawZone).associateBy { it.tiltDeg }
        val january = { tilt: Double -> result.getValue(tilt).energyByMonthKwh.getValue(Month.JANUARY) }
        assertTrue(january(60.0) > january(0.0))
        assertTrue(result.getValue(30.0).yearlyKwh > result.getValue(0.0).yearlyKwh)
    }

    // --- Sanitizing extreme values ---

    @Test
    fun sanitized_clampsOutOfRangeValues() {
        val s = PvSystem(peakPowerKw = -3.0, tiltDeg = 120.0, azimuthDeg = 370.0, performanceRatio = 1.5).sanitized()
        assertEquals(0.0, s.peakPowerKw, 0.0)
        assertEquals(90.0, s.tiltDeg, 0.0)
        assertEquals(10.0, s.azimuthDeg, 1e-9)
        assertEquals(1.0, s.performanceRatio, 0.0)

        val negative = PvSystem(tiltDeg = -5.0, azimuthDeg = -90.0).sanitized()
        assertEquals(0.0, negative.tiltDeg, 0.0)
        assertEquals(270.0, negative.azimuthDeg, 1e-9)
    }

    @Test
    fun sanitized_replacesNaN() {
        val s = PvSystem(peakPowerKw = Double.NaN, tiltDeg = Double.NaN, azimuthDeg = Double.POSITIVE_INFINITY).sanitized()
        assertEquals(0.0, s.peakPowerKw, 0.0)
        assertEquals(0.0, s.tiltDeg, 0.0)
        assertEquals(180.0, s.azimuthDeg, 0.0)
    }

    @Test
    fun estimator_neverProducesNegativeOrNonFiniteValuesForExtremes() {
        val systems = listOf(
            PvSystem(tiltDeg = 0.0), PvSystem(tiltDeg = 90.0),
            PvSystem(tiltDeg = 90.0, azimuthDeg = 0.0), PvSystem(peakPowerKw = 1000.0),
        )
        val locations = listOf(GeoLocation(90.0, 0.0), GeoLocation(-90.0, 0.0), GeoLocation(0.0, 180.0), warsaw)
        for (s in systems) for (l in locations) for (month in listOf(3, 6, 12)) {
            val kwh = estimator.dailyEnergyKwh(s, l, LocalDate.of(2024, month, 21), ZoneOffset.UTC)
            assertTrue("$s $l $kwh", kwh.isFinite() && kwh >= 0.0 && kwh <= s.peakPowerKw * 24)
        }
    }

    @Test
    fun customIrradianceModel_canReplaceClearSky() {
        val dark = PvEstimator { _, _ -> Irradiance.ZERO }
        assertEquals(0.0, dark.dailyEnergyKwh(defaultSystem, warsaw, june21, warsawZone), 0.0)
    }
}

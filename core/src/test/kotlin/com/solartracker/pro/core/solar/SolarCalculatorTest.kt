package com.solartracker.pro.core.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class SolarCalculatorTest {

    private val warsaw = GeoLocation(52.2297, 21.0122)
    private val london = GeoLocation(51.5074, -0.1278)
    private val sydney = GeoLocation(-33.8688, 151.2093)
    private val quito = GeoLocation(-0.1807, -78.4678)
    private val tromso = GeoLocation(69.6492, 18.9553)

    private val warsawZone = ZoneId.of("Europe/Warsaw")

    // --- Solar position ---

    @Test
    fun position_matchesNrelSpaReferenceExample() {
        // NREL SPA reference: 2003-10-17 12:30:30 local (UTC-7), Golden, Colorado.
        // Topocentric zenith 50.11162°, azimuth 194.34024°.
        val location = GeoLocation(39.742476, -105.1786)
        val instant = Instant.parse("2003-10-17T19:30:30Z")
        val pos = SolarCalculator.position(location, instant)
        assertEquals(50.11162, pos.zenithDeg, 0.1)
        assertEquals(194.34024, pos.azimuthDeg, 0.1)
    }

    @Test
    fun position_atSolarNoonInWarsawInJune_hasExpectedElevationAndFacesSouth() {
        val date = LocalDate.of(2024, 6, 21)
        val noon = SolarCalculator.sunTimes(warsaw, date).solarNoon
        val pos = SolarCalculator.position(warsaw, noon)
        // 90 - latitude + declination (23.44°)
        assertEquals(61.2, pos.elevationDeg, 0.2)
        assertEquals(180.0, pos.azimuthDeg, 0.5)
    }

    @Test
    fun position_atSolarNoonInWarsawInDecember_isLow() {
        val noon = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 12, 21)).solarNoon
        val pos = SolarCalculator.position(warsaw, noon)
        assertEquals(14.4, pos.elevationDeg, 0.3)
    }

    @Test
    fun position_inSouthernHemisphereAtNoon_facesNorth() {
        val noon = SolarCalculator.sunTimes(sydney, LocalDate.of(2024, 6, 21)).solarNoon
        val pos = SolarCalculator.position(sydney, noon)
        val distanceFromNorth = minOf(pos.azimuthDeg, 360.0 - pos.azimuthDeg)
        assertTrue("azimuth ${pos.azimuthDeg} should be near north", distanceFromNorth < 1.0)
        assertEquals(90.0 - 33.8688 - 23.44, pos.elevationDeg, 0.3)
    }

    @Test
    fun position_atEquatorOnEquinoxNoon_isNearZenith() {
        val noon = SolarCalculator.sunTimes(quito, LocalDate.of(2024, 3, 20)).solarNoon
        val pos = SolarCalculator.position(quito, noon)
        assertTrue("elevation ${pos.elevationDeg}", pos.elevationDeg > 89.0)
    }

    @Test
    fun position_morningIsEastAndAfternoonIsWest() {
        val morning = ZonedDateTime.of(LocalDate.of(2024, 6, 21), LocalTime.of(8, 0), warsawZone).toInstant()
        val afternoon = ZonedDateTime.of(LocalDate.of(2024, 6, 21), LocalTime.of(17, 0), warsawZone).toInstant()
        val am = SolarCalculator.position(warsaw, morning)
        val pm = SolarCalculator.position(warsaw, afternoon)
        assertTrue(am.azimuthDeg in 45.0..135.0)
        assertTrue(pm.azimuthDeg in 225.0..315.0)
        assertTrue(am.isAboveHorizon && pm.isAboveHorizon)
    }

    @Test
    fun position_atMidnight_isBelowHorizon() {
        val midnight = ZonedDateTime.of(LocalDate.of(2024, 3, 1), LocalTime.MIDNIGHT, warsawZone).toInstant()
        val pos = SolarCalculator.position(warsaw, midnight)
        assertTrue(pos.elevationDeg < -20.0)
        assertTrue(!pos.isAboveHorizon)
    }

    @Test
    fun position_valuesStayInRangeForExtremeLocations() {
        val locations = listOf(
            GeoLocation(90.0, 0.0),
            GeoLocation(-90.0, 0.0),
            GeoLocation(0.0, 180.0),
            GeoLocation(0.0, -180.0),
            GeoLocation(89.999, -179.999),
        )
        var instant = Instant.parse("2024-01-01T00:00:00Z")
        repeat(400) {
            for (loc in locations) {
                val pos = SolarCalculator.position(loc, instant)
                assertTrue(pos.elevationDeg.isFinite() && pos.elevationDeg in -90.0..90.0)
                assertTrue(pos.azimuthDeg.isFinite() && pos.azimuthDeg >= 0.0 && pos.azimuthDeg < 360.0)
            }
            instant = instant.plus(Duration.ofHours(22))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun geoLocation_rejectsInvalidLatitude() {
        GeoLocation(90.5, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun geoLocation_rejectsInvalidLongitude() {
        GeoLocation(0.0, 181.0)
    }

    // --- Sunrise / sunset ---

    @Test
    fun sunTimes_warsawSummerSolstice() {
        val times = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 6, 21))
        assertLocalTime("04:14", times.sunrise, warsawZone)
        assertLocalTime("21:00", times.sunset, warsawZone)
        assertEquals(DayType.NORMAL, times.dayType)
        assertEquals(16 * 60 + 46.0, times.dayLength.toMinutes().toDouble(), 3.0)
    }

    @Test
    fun sunTimes_warsawWinterSolstice() {
        val times = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 12, 21))
        assertLocalTime("07:43", times.sunrise, warsawZone)
        assertLocalTime("15:25", times.sunset, warsawZone)
        assertEquals(7 * 60 + 42.0, times.dayLength.toMinutes().toDouble(), 3.0)
    }

    @Test
    fun sunTimes_londonSummerSolstice() {
        val zone = ZoneId.of("Europe/London")
        val times = SolarCalculator.sunTimes(london, LocalDate.of(2024, 6, 21))
        assertLocalTime("04:43", times.sunrise, zone)
        assertLocalTime("21:21", times.sunset, zone)
    }

    @Test
    fun sunTimes_sydneySummer() {
        val zone = ZoneId.of("Australia/Sydney")
        val times = SolarCalculator.sunTimes(sydney, LocalDate.of(2024, 12, 21))
        assertLocalTime("05:41", times.sunrise, zone)
        assertLocalTime("20:05", times.sunset, zone)
    }

    @Test
    fun sunTimes_equatorHasRoughlyTwelveHourDaysAllYear() {
        for (month in 1..12) {
            val times = SolarCalculator.sunTimes(quito, LocalDate.of(2024, month, 15))
            assertEquals(DayType.NORMAL, times.dayType)
            assertEquals(12 * 60 + 7.0, times.dayLength.toMinutes().toDouble(), 4.0)
        }
    }

    @Test
    fun sunTimes_sunriseBeforeNoonBeforeSunset() {
        val times = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 9, 1))
        assertTrue(times.sunrise!!.isBefore(times.solarNoon))
        assertTrue(times.solarNoon.isBefore(times.sunset))
    }

    @Test
    fun sunTimes_elevationAtSunriseIsNearHorizon() {
        val times = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 4, 10))
        val pos = SolarCalculator.position(warsaw, times.sunrise!!)
        // Event definition uses the upper limb with refraction: centre is ~0.27° below.
        assertEquals(-0.27, pos.elevationDeg, 0.3)
    }

    @Test
    fun sunTimes_tromsoPolarDayAndPolarNight() {
        val summer = SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 6, 21))
        assertEquals(DayType.POLAR_DAY, summer.dayType)
        assertNull(summer.sunrise)
        assertNull(summer.sunset)
        assertEquals(Duration.ofHours(24), summer.dayLength)

        val winter = SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 12, 21))
        assertEquals(DayType.POLAR_NIGHT, winter.dayType)
        assertEquals(Duration.ZERO, winter.dayLength)
    }

    @Test
    fun sunTimes_poles() {
        val june = LocalDate.of(2024, 6, 21)
        assertEquals(DayType.POLAR_DAY, SolarCalculator.sunTimes(GeoLocation(90.0, 0.0), june).dayType)
        assertEquals(DayType.POLAR_NIGHT, SolarCalculator.sunTimes(GeoLocation(-90.0, 0.0), june).dayType)
    }

    @Test
    fun sunTimes_dateLineLocationsWork() {
        for (lon in listOf(-180.0, 180.0, 179.9, -179.9)) {
            val times = SolarCalculator.sunTimes(GeoLocation(10.0, lon), LocalDate.of(2024, 5, 5))
            assertEquals(DayType.NORMAL, times.dayType)
            assertNotNull(times.sunrise)
            assertTrue(times.dayLength.toHours() in 11..13)
        }
    }

    @Test
    fun normalizeDegrees_wrapsIntoRange() {
        assertEquals(0.0, normalizeDegrees(360.0), 1e-9)
        assertEquals(350.0, normalizeDegrees(-10.0), 1e-9)
        assertEquals(10.0, normalizeDegrees(730.0), 1e-9)
    }

    private fun assertLocalTime(expected: String, actual: Instant?, zone: ZoneId, toleranceMin: Long = 3) {
        assertNotNull("event expected", actual)
        val expectedTime = LocalTime.parse(expected)
        val actualTime = actual!!.atZone(zone).toLocalTime()
        val diff = abs(Duration.between(expectedTime, actualTime).toMinutes())
        assertTrue("expected $expected but was $actualTime", diff <= toleranceMin)
    }
}

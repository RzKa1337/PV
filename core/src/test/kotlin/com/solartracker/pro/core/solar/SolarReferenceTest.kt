package com.solartracker.pro.core.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Solar position against published references and geometric identities, independent of the implementation:
 * - the NREL SPA paper example (Reda & Andreas, NREL/TP-560-34302, 2003-10-17 12:30:30 UTC−7, Golden CO);
 * - USNO equinox / solstice instants (the apparent declination there is 0° / ±ε, ε = obliquity ≈ 23.436° in 2024);
 * - the meridian-altitude identity: the highest elevation of the day is 90° − |φ − δ|.
 *
 * Tolerance: the NOAA/Meeus series used here omit the solar parallax (≤ 0.0024°) and apply the standard-atmosphere
 * refraction without the station pressure/temperature SPA uses (differs ≈ 0.004° at 40° elevation), so 0.01° is the
 * honest agreement level; the observed differences are listed in the test.
 */
class SolarReferenceTest {

    @Test
    fun nrelSpaPaperExampleWithin0p01Degrees() {
        val golden = GeoLocation(39.742476, -105.1786, 1830.14)
        val d = SolarCalculator.details(golden, Instant.parse("2003-10-17T19:30:30Z"))
        // SPA table: topocentric elevation without refraction e0 = 39.872046°, azimuth 194.34024°,
        // zenith with refraction (P = 820 mbar, 11 °C) 50.11162° (refraction 0.016332°).
        assertEquals(39.872046, d.geometricElevationDeg, 0.005)
        assertEquals(194.34024, d.azimuthDeg, 0.01)
        assertEquals(50.11162, d.zenithDeg, 0.01)
        // Refraction magnitude: NOAA formula (standard atmosphere) 0.0195° vs SPA 0.0163° at this pressure.
        assertEquals(0.0163, d.elevationDeg - d.geometricElevationDeg, 0.005)
    }

    @Test
    fun declinationAtEquinoxesAndSolstices2024() {
        fun decl(utc: String) = SolarCalculator.details(GeoLocation(0.0, 0.0), Instant.parse(utc)).declinationDeg
        // USNO 2024: March equinox 03:06 UTC, June solstice 20:51, September equinox 12:44, December solstice 09:21.
        assertEquals(0.0, decl("2024-03-20T03:06:00Z"), 0.01)
        assertEquals(23.436, decl("2024-06-20T20:51:00Z"), 0.01)
        assertEquals(0.0, decl("2024-09-22T12:44:00Z"), 0.01)
        assertEquals(-23.436, decl("2024-12-21T09:21:00Z"), 0.01)
    }

    @Test
    fun equationOfTimeExtremes() {
        fun eot(utc: String) = SolarCalculator.details(GeoLocation(0.0, 0.0), Instant.parse(utc)).equationOfTimeMin
        // Published almanac values: ≈ +16.4 min around 3 November, ≈ −14.2 min around 11 February, ≈ 0 near 16 April.
        assertEquals(16.4, eot("2024-11-03T12:00:00Z"), 0.3)
        assertEquals(-14.2, eot("2024-02-11T12:00:00Z"), 0.3)
        assertEquals(0.0, eot("2024-04-15T12:00:00Z"), 0.3)
    }

    /** Highest geometric elevation and its azimuth in the UTC day, scanning every minute. */
    private fun culmination(loc: GeoLocation, day: LocalDate): Pair<Double, Double> {
        val start = day.atStartOfDay(ZoneId.of("UTC")).toInstant()
        var best = -91.0
        var az = 0.0
        for (m in 0 until 1440) {
            val d = SolarCalculator.details(loc, start.plusSeconds(m * 60L))
            if (d.geometricElevationDeg > best) { best = d.geometricElevationDeg; az = d.azimuthDeg }
        }
        return best to az
    }

    @Test
    fun meridianAltitudeAcrossLatitudesAndSeasons() {
        val june = LocalDate.of(2024, 6, 21)      // δ ≈ +23.436° (a day after the solstice: still within 0.001°)
        val december = LocalDate.of(2024, 12, 21) // δ ≈ −23.436°
        data class Case(val name: String, val lat: Double, val lon: Double, val day: LocalDate, val delta: Double, val northAz: Boolean)
        val cases = listOf(
            Case("Warsaw June", 52.23, 21.01, june, 23.436, false),           // 90 − 52.23 + 23.436 = 61.206
            Case("Warsaw December", 52.23, 21.01, december, -23.436, false),  // 90 − 52.23 − 23.436 = 14.334
            Case("Sydney June", -33.87, 151.21, june, 23.436, true),          // 90 − (33.87 + 23.436) = 32.694, sun in the north
            Case("Sydney December", -33.87, 151.21, december, -23.436, true), // 90 − |−33.87 + 23.436| = 79.566, sun in the north
            Case("Equator June", 0.0, 10.0, june, 23.436, true),              // 66.564, north
            Case("Quito December", -0.18, -78.47, december, -23.436, false),  // 90 − |−0.18 + 23.436| = 66.744, sun in the south
            Case("Tromso June", 69.65, 18.96, june, 23.436, false),           // 43.786
            Case("Tromso December", 69.65, 18.96, december, -23.436, false),  // −3.086: polar night, never above the horizon geometrically
        )
        for (c in cases) {
            val (elev, az) = culmination(GeoLocation(c.lat, c.lon), c.day)
            val expected = 90.0 - abs(c.lat - c.delta)
            assertEquals("${c.name} culmination elevation", expected, elev, 0.02)
            if (elev < 89.0 && abs(c.lat - c.delta) > 1.0) {
                // The sun culminates in the south when the latitude is north of the declination, otherwise in the north.
                val expectedAz = if (c.lat > c.delta) 180.0 else 0.0
                val diff = abs(((az - expectedAz + 540.0) % 360.0) - 180.0)
                assertTrue("${c.name} azimuth $az", diff < 1.0)
            }
        }
    }

    @Test
    fun polarDayMidnightSunAndPolarNightNoon() {
        val tromso = GeoLocation(69.65, 18.96)
        // Lower culmination at local solar midnight on 21 June: φ + δ − 90 = 69.65 + 23.436 − 90 = +3.086° → sun up.
        val solarMidnight = SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 6, 21)).solarNoon.plus(Duration.ofHours(12))
        val night = SolarCalculator.position(tromso, solarMidnight)
        assertTrue(night.isAboveHorizon)
        assertEquals(3.086, night.elevationDeg, 0.5) // refraction adds up to ≈ 0.2° at this height
        assertTrue("sun is in the north at midnight: ${night.azimuthDeg}", night.azimuthDeg < 5.0 || night.azimuthDeg > 355.0)
        // 21 December: the highest point is −3.086° geometric (≈ −2.5° with refraction) → no sunrise at all.
        val noon = SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 12, 21)).solarNoon
        assertFalse(SolarCalculator.position(tromso, noon).isAboveHorizon)
        assertEquals(DayType.POLAR_NIGHT, SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 12, 21)).dayType)
        assertEquals(DayType.POLAR_DAY, SolarCalculator.sunTimes(tromso, LocalDate.of(2024, 6, 21)).dayType)
        // Southern polar region mirrors it: Antarctica station at 78°S has polar day in December, night in June.
        val south = GeoLocation(-78.0, 166.0)
        assertEquals(DayType.POLAR_DAY, SolarCalculator.sunTimes(south, LocalDate.of(2024, 12, 21)).dayType)
        assertEquals(DayType.POLAR_NIGHT, SolarCalculator.sunTimes(south, LocalDate.of(2024, 6, 21)).dayType)
    }

    @Test
    fun solarNoonOnLocalClockMovesByAnHourAcrossDst() {
        val warsaw = GeoLocation(52.23, 21.01)
        val zone = ZoneId.of("Europe/Warsaw")
        // Clocks go forward on 2024-03-31. Solar noon on the local clock must jump by ≈ 60 min (the equation of time
        // only moves it by ≈ 1.5 min over these two days). Warsaw noon ≈ 11:4x CET → ≈ 12:4x CEST.
        val before = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 3, 30)).solarNoon.atZone(zone)
        val after = SolarCalculator.sunTimes(warsaw, LocalDate.of(2024, 4, 1)).solarNoon.atZone(zone)
        val shift = Duration.between(before.toLocalTime(), after.toLocalTime()).toMinutes()
        assertTrue("shift $shift min", shift in 57..63)
        // Same instant, same sun regardless of the zone it is expressed in (position depends on the instant only).
        val instant = Instant.parse("2024-07-01T10:30:00Z")
        val p1 = SolarCalculator.position(warsaw, instant.atZone(ZoneId.of("Europe/Warsaw")).toInstant())
        val p2 = SolarCalculator.position(warsaw, instant.atZone(ZoneId.of("Pacific/Kiritimati")).toInstant())
        assertEquals(p1.azimuthDeg, p2.azimuthDeg, 0.0)
        assertEquals(p1.elevationDeg, p2.elevationDeg, 0.0)
    }

    @Test
    fun airMassMatchesKastenYoungPublishedValues() {
        // Kasten & Young (1989): AM(z=0) = 1, AM(60°) = 1.9939, AM(80°) = 5.6, AM(85°) = 10.3 (standard atmosphere).
        assertEquals(1.0, SolarCalculator.airMass(0.0)!!, 0.001)
        assertEquals(1.994, SolarCalculator.airMass(60.0)!!, 0.01)
        assertEquals(5.6, SolarCalculator.airMass(80.0)!!, 0.1)
        assertEquals(10.3, SolarCalculator.airMass(85.0)!!, 0.2)
        assertEquals(null, SolarCalculator.airMass(90.0))
    }
}

package com.solartracker.pro.core.widget

import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class SolarSummaryTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 6.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val weather = WeatherAwareIrradianceModel(loc)
    private val noon = LocalDate.of(2026, 6, 21).atTime(13, 0).atZone(zone).toInstant()

    @Test
    fun noonSummaryWithoutInverter() {
        val s = SolarSummaryBuilder.build(loc, system, weather, noon, zone, null)
        assertTrue(s.modelPvKw > 3)
        assertEquals(DataKind.ESTIMATED, s.modelKind)
        assertEquals(WeatherSource.CLEAR_SKY, s.weatherSource)
        assertTrue(s.remainingTodayKwh in 0.0..s.todayExpectedKwh)
        assertTrue(s.remainingTodayKwh > s.todayExpectedKwh * 0.3)
        assertEquals(DataKind.UNAVAILABLE, s.measuredKind)
        assertNull(s.measuredPvKw)
        assertTrue(s.sunElevationDeg > 55)
        assertTrue(s.sunrise!!.isBefore(noon) && s.sunset!!.isAfter(noon))
    }

    @Test
    fun measurementFreshnessLabels() {
        val fresh = SolarSummaryBuilder.build(loc, system, weather, noon, zone, LastMeasurement(noon.minusSeconds(120), 2840.0, 76.0))
        assertEquals(DataKind.MEASURED, fresh.measuredKind)
        assertEquals(2.84, fresh.measuredPvKw!!, 1e-9)
        assertEquals(76.0, fresh.measuredSoc!!, 0.0)
        val old = SolarSummaryBuilder.build(loc, system, weather, noon, zone, LastMeasurement(noon.minus(Duration.ofHours(3)), 2840.0, 76.0))
        assertEquals("old data is never current", DataKind.LAST_KNOWN, old.measuredKind)
        val future = SolarSummaryBuilder.build(loc, system, weather, noon, zone, LastMeasurement(noon.plus(Duration.ofHours(1)), 1.0, 1.0))
        assertEquals(DataKind.LAST_KNOWN, future.measuredKind)
    }

    @Test
    fun nightHasNoPowerAndNoRemaining() {
        val night = LocalDate.of(2026, 6, 21).atTime(23, 30).atZone(zone).toInstant()
        val s = SolarSummaryBuilder.build(loc, system, weather, night, zone, null)
        assertEquals(0.0, s.modelPvKw, 0.0)
        assertEquals(0.0, s.remainingTodayKwh, 1e-9)
        assertTrue(s.todayExpectedKwh > 20)
    }
}

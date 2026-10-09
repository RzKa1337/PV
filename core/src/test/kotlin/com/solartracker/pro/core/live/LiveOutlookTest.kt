package com.solartracker.pro.core.live

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class LiveOutlookTest {
    private val warsaw = GeoLocation(52.23, 21.01)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val system = PvSystem(peakPowerKw = 4.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val day = LocalDate.of(2026, 6, 10)
    private val est = PvEstimator()

    @Test
    fun morningOutlookRisesAndEnergySplitsTheDay() {
        val now = day.atTime(9, 0).atZone(zone).toInstant()
        val o = LiveOutlook.compute(est, system, warsaw, now, zone)
        assertEquals(listOf(5L, 15L, 30L, 60L), o.ahead.map { it.first })
        // Clear morning, south panels: each later horizon gives more power.
        assertTrue(o.ahead.zipWithNext().all { (a, b) -> b.second > a.second })
        assertEquals(est.powerKw(system, warsaw, now.plusSeconds(1800)), o.ahead[2].second, 1e-12)
        // So far + remaining = whole day (same integration), within the discretisation.
        assertEquals(est.dailyEnergyKwh(system, warsaw, day, zone, 5), o.dayKwh, 0.01)
        assertTrue(o.soFarKwh > 0 && o.remainingKwh > o.soFarKwh)
        // Maximum near solar noon and not above the peak.
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        assertTrue(abs(Duration.between(o.todayMaxAt, noon).toMinutes()) <= 20)
        assertTrue(o.todayMaxKw <= 4.0)
    }

    @Test
    fun nightHasNothingLeft() {
        val now = day.atTime(23, 30).atZone(zone).toInstant()
        val o = LiveOutlook.compute(est, system, warsaw, now, zone)
        assertEquals(0.0, o.remainingKwh, 1e-12)
        assertTrue(o.ahead.all { it.second == 0.0 })
        assertEquals(est.dailyEnergyKwh(system, warsaw, day, zone, 5), o.soFarKwh, 1e-6)
    }
}

package com.solartracker.pro.core.live

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.pv.angleOfIncidenceDeg
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSolarTest {

    private val warsaw = GeoLocation(52.2297, 21.0122)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val day = LocalDate.of(2024, 6, 21)
    private val estimator = PvEstimator()
    private val live = LiveSolarCalculator(estimator)
    private val load = ConsumptionProfile.constant(0.72)

    private fun at(h: Int, m: Int, s: Int = 0, date: LocalDate = day): Instant =
        ZonedDateTime.of(date, LocalTime.of(h, m, s), zone).toInstant()

    /** Wall clock that follows the test's virtual time, starting at [start]. */
    private fun TestScope.clock(start: Instant, driftPpm: Long = 0): () -> Instant = {
        val ms = testScheduler.currentTime
        start.plusMillis(ms + ms * driftPpm / 1_000_000)
    }

    // --- 1–3: ticker ---

    @Test
    fun ticker_emitsOncePerSecondRightAfterTheBoundary() = runTest {
        val start = at(10, 42, 31).plusMillis(400) // 10:42:31.400
        val emitted = mutableListOf<Pair<Instant, Long>>()
        val job = launch {
            SecondTicker(clock(start)).ticks().take(10).collect { emitted += it to testScheduler.currentTime }
        }
        advanceTimeBy(20_000)
        job.join()
        assertEquals(10, emitted.size)
        assertEquals(0L, emitted[0].second) // first tick immediately on start
        emitted.forEachIndexed { i, (instant, _) ->
            assertEquals(start.epochSecond + i, instant.epochSecond)
            if (i > 0) assertTrue("tick $i at ${instant.nano / 1_000_000} ms", instant.nano / 1_000_000 in 0..20)
        }
        // Virtual (monotonic) time between ticks is one second, except the first partial one.
        assertEquals(605L, emitted[1].second) // 600 ms to the boundary + 5 ms guard
        for (i in 2 until emitted.size) assertEquals(1000L, emitted[i].second - emitted[i - 1].second)
    }

    @Test
    fun ticker_usesFreshWallClockTime() = runTest {
        var calls = 0
        val start = at(12, 0, 0)
        val ticks = SecondTicker({ calls++; start.plusMillis(testScheduler.currentTime) }).ticks().take(3).toList()
        assertEquals(listOf(0L, 1L, 2L), ticks.map { it.epochSecond - start.epochSecond })
        assertTrue("clock must be read on every tick", calls >= 3)
        assertNotEquals(ticks[0], ticks[1])
    }

    @Test
    fun ticker_neverSkipsOrRepeatsSecondsWithClockDrift() = runTest {
        // Wall clock runs 0.2 % fast relative to the monotonic scheduler clock.
        val start = at(23, 59, 50).plusMillis(990)
        val ticks = SecondTicker(clock(start, driftPpm = 2000)).ticks().take(300).toList()
        for (i in 1 until ticks.size) {
            assertEquals("second transition $i", ticks[i - 1].epochSecond + 1, ticks[i].epochSecond)
        }
        // Crosses midnight into the next day without problems.
        assertEquals(LocalDate.of(2024, 6, 22), ticks.last().atZone(zone).toLocalDate())
    }

    @Test
    fun ticker_emitsCurrentSecondAfterClockJump() = runTest {
        var offset = 0L
        val start = at(8, 0, 0)
        val ticker = SecondTicker({ start.plusMillis(testScheduler.currentTime + offset) })
        val ticks = mutableListOf<Instant>()
        val job = launch { ticker.ticks().collect { ticks += it } }
        runCurrent()
        advanceTimeBy(2_100)
        offset = 60_000 // user changes the clock by +1 minute
        advanceTimeBy(1_100)
        job.cancel()
        assertEquals(start.epochSecond + 63, ticks.last().epochSecond)
        assertEquals(ticks.size, ticks.map { it.epochSecond }.distinct().size)
    }

    @Test
    fun ticker_backfillsSecondsMissedBecauseOfAShortStall() = runTest {
        var stall = 0L
        val start = at(10, 0, 0).plusMillis(3)
        val ticker = SecondTicker({ start.plusMillis(testScheduler.currentTime + stall) })
        val ticks = mutableListOf<Instant>()
        val job = launch { ticker.ticks().collect { ticks += it } }
        runCurrent()
        advanceTimeBy(1_500)
        stall = 2_600 // the process was frozen for 2.6 s
        advanceTimeBy(1_000)
        job.cancel()
        val seconds = ticks.map { it.epochSecond - start.epochSecond }
        assertEquals((0L..seconds.last()).toList(), seconds)
        assertTrue(seconds.last() >= 4)
        // Back-filled ticks carry the exact instant of their own second (no fractional part).
        assertTrue("some seconds were back-filled", ticks.any { it.nano == 0 })
    }

    @Test
    fun ticker_stopsWhenCollectorIsCancelled() = runTest {
        var reads = 0
        val ticker = SecondTicker({ reads++; Instant.EPOCH.plusMillis(testScheduler.currentTime) })
        val job = launch { ticker.ticks().collect { } }
        advanceTimeBy(3_500)
        job.cancel()
        val after = reads
        advanceTimeBy(10_000)
        assertEquals("no work after cancellation", after, reads)
        assertEquals(1000L, SecondTicker.millisToNextSecond(Instant.ofEpochSecond(5)))
        assertEquals(1L, SecondTicker.millisToNextSecond(Instant.ofEpochMilli(5_999)))
    }

    // --- 4–5: azimuth / elevation ---

    @Test
    fun liveSun_matchesNrelReferenceAndSharedCalculator() {
        val instant = Instant.parse("2003-10-17T19:30:30Z")
        val golden = GeoLocation(39.742476, -105.1786, elevationM = 1830.14)
        val s = live.snapshot(instant, ZoneId.of("America/Denver"), golden, PvSystem(), load, null, null)
        assertEquals(194.34024, s.estimate.sun.azimuthDeg, 0.1)
        assertEquals(50.11162, s.estimate.sun.zenithDeg, 0.1)
        assertEquals(SolarCalculator.position(golden, instant), s.estimate.sun.position)
        // Pressure-corrected air mass is lower at 1830 m than at sea level.
        val seaLevel = SolarCalculator.airMass(s.estimate.sun.zenithDeg)!!
        assertTrue(s.estimate.sun.airMass!! < seaLevel * 0.82)
        assertEquals(1.0 / Math.cos(Math.toRadians(50.11)), seaLevel, 0.02)
    }

    @Test
    fun liveSun_changesSmoothlyEverySecondInFullPrecision() {
        var previous = live.snapshot(at(10, 42, 31), zone, warsaw, PvSystem(), load, null, null).estimate.sun
        for (i in 1..120) {
            val sun = live.snapshot(at(10, 42, 31).plusSeconds(i.toLong()), zone, warsaw, PvSystem(), load, null, null).estimate.sun
            val dAz = sun.azimuthDeg - previous.azimuthDeg
            val dEl = sun.elevationDeg - previous.elevationDeg
            assertTrue("azimuth step $dAz", dAz > 0.0 && dAz < 0.02) // ~0.4°/min before noon
            assertTrue("elevation step $dEl", dEl > 0.0 && dEl < 0.01)
            previous = sun
        }
        // Not rounded internally: far more precision than the 2 decimals shown in the UI.
        assertNotEquals(Math.round(previous.azimuthDeg * 100) / 100.0, previous.azimuthDeg, 1e-9)
    }

    @Test
    fun hourAngle_isZeroAtSolarNoonAndGrows15DegreesPerHour() {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        assertEquals(0.0, SolarCalculator.details(warsaw, noon).hourAngleDeg, 0.05)
        assertEquals(15.0, SolarCalculator.details(warsaw, noon.plusSeconds(3600)).hourAngleDeg, 0.05)
        assertEquals(-30.0, SolarCalculator.details(warsaw, noon.minusSeconds(7200)).hourAngleDeg, 0.05)
    }

    // --- 6–8: sunrise, sunset, night ---

    @Test
    fun nextSunriseAndSunset_beforeDuringAndAfterDaylight() {
        val times = SolarCalculator.sunTimes(warsaw, day)
        val early = live.snapshot(at(2, 0), zone, warsaw, PvSystem(), load, null, null)
        assertEquals(times.sunrise, early.nextSunrise)
        assertEquals(times.sunset, early.nextSunset)
        assertEquals(Duration.between(at(2, 0), times.sunrise), early.timeToSunrise)

        val midday = live.snapshot(at(12, 0), zone, warsaw, PvSystem(), load, null, null)
        assertEquals(SolarCalculator.sunTimes(warsaw, day.plusDays(1)).sunrise, midday.nextSunrise)
        assertEquals(times.sunset, midday.nextSunset)

        val late = live.snapshot(at(23, 0), zone, warsaw, PvSystem(), load, null, null)
        assertEquals(SolarCalculator.sunTimes(warsaw, day.plusDays(1)).sunset, late.nextSunset)
        assertTrue(late.timeToSunrise!! < Duration.ofHours(6))
    }

    @Test
    fun nextSunrise_onDaylightSavingDay() {
        val dst = LocalDate.of(2024, 3, 31) // 02:00 → 03:00 in Warsaw
        val s = live.snapshot(at(1, 30, date = dst), zone, warsaw, PvSystem(), load, null, null)
        assertEquals(SolarCalculator.sunTimes(warsaw, dst).sunrise, s.nextSunrise)
        // Sunrise 06:2x CEST, i.e. less than 4 h of real time after 01:30 CET.
        assertTrue(s.timeToSunrise!! < Duration.ofHours(4))
    }

    @Test
    fun night_givesZeroPowerAndSwitchesToDayAtSunrise() {
        val sunrise = SolarCalculator.sunTimes(warsaw, day).sunrise!!
        val night = live.snapshot(at(1, 0), zone, warsaw, PvSystem(), load, null, null)
        assertFalse(night.isDay)
        assertEquals(0.0, night.estimate.powerKw, 0.0)
        assertEquals(0.0, night.estimate.poa, 0.0)
        assertEquals(0.0, night.estimate.geometricUtilization, 0.0)
        // Walk second by second across sunrise: day starts when the elevation becomes positive.
        var switched: Instant? = null
        var t = sunrise.minusSeconds(300)
        while (t.isBefore(sunrise.plusSeconds(300))) {
            val s = live.snapshot(t, zone, warsaw, PvSystem(), load, null, null)
            assertTrue(s.estimate.powerKw >= 0.0)
            if (s.isDay && switched == null) switched = t
            if (!s.isDay) assertEquals(0.0, s.estimate.powerKw, 0.0)
            t = t.plusSeconds(1)
        }
        assertNotNull(switched)
        assertTrue(Duration.between(sunrise, switched).abs() < Duration.ofMinutes(4))
    }

    // --- 9: angle of incidence ---

    @Test
    fun angleOfIncidence_zeroWhenPanelFacesTheSun() {
        val t = at(13, 0)
        val sun = SolarCalculator.position(warsaw, t)
        val facing = PvSystem(tiltDeg = sun.zenithDeg, azimuthDeg = sun.azimuthDeg)
        val s = live.snapshot(t, zone, warsaw, facing, load, null, null)
        assertEquals(0.0, s.estimate.angleOfIncidenceDeg, 0.01)
        assertEquals(1.0, s.estimate.geometricUtilization, 1e-6)
        // Flat panel: AOI equals the zenith angle.
        assertEquals(sun.zenithDeg, angleOfIncidenceDeg(sun, 0.0, 180.0), 1e-9)
        // Vertical panel facing away from the sun gets no direct light.
        val away = live.snapshot(t, zone, warsaw, PvSystem(tiltDeg = 90.0, azimuthDeg = (sun.azimuthDeg + 180) % 360), load, null, null)
        assertTrue(away.estimate.angleOfIncidenceDeg > 90.0)
        assertEquals(0.0, away.estimate.geometricUtilization, 0.0)
    }

    // --- 10: current PV power ---

    @Test
    fun livePower_isTheSharedModelPower() {
        val system = PvSystem(peakPowerKw = 2.09, tiltDeg = 32.0, azimuthDeg = 180.0)
        val t = at(12, 15, 7)
        val s = live.snapshot(t, zone, warsaw, system, load, null, null)
        assertEquals(estimator.powerKw(system, warsaw, t), s.estimate.powerKw, 1e-12)
        assertTrue(s.estimate.powerKw in 1.0..2.09)
        assertTrue(s.estimate.poa > s.estimate.ghi) // tilted towards the sun at noon
        assertEquals(WeatherSource.CLEAR_SKY, s.irradianceSource)
    }

    @Test
    fun livePower_usesForecastIrradianceAndTemperature() {
        val t = at(12, 30)
        val hourEnd = t.plusSeconds(1800)
        val forecast = WeatherForecast(listOf(HourlyWeather(hourEnd, 500.0, 400.0, 150.0, 31.4, 40.0)), t)
        val model = WeatherAwareIrradianceModel(warsaw, forecast)
        val calc = LiveSolarCalculator(PvEstimator(model), sourceAt = model::sourceAt)
        val s = calc.snapshot(t, zone, warsaw, PvSystem(), load, null, null)
        assertEquals(WeatherSource.FORECAST, s.irradianceSource)
        // Hour mean 400 W/m² DNI; at the hour centre the interpolated value is within a few % of it.
        assertEquals(400.0, s.estimate.irradiance.dni, 400.0 * 0.03)
        assertEquals(31.4, s.estimate.irradiance.ambientTemperatureC!!, 0.0)
        assertTrue(s.estimate.cellTemperatureC!! > 31.4)
        // Outside the forecast hour: falls back to clear sky, no network involved.
        assertEquals(WeatherSource.CLEAR_SKY, calc.snapshot(t.plusSeconds(7200), zone, warsaw, PvSystem(), load, null, null).irradianceSource)
    }

    @Test
    fun livePower_isCappedForAbsurdWeatherValues() {
        val t = at(12, 30)
        val forecast = WeatherForecast(listOf(HourlyWeather(t.plusSeconds(600), 9_999.0, 50_000.0, 9_999.0, 20.0, 0.0)), t)
        val calc = LiveSolarCalculator(PvEstimator(WeatherAwareIrradianceModel(warsaw, forecast)))
        val s = calc.snapshot(t, zone, warsaw, PvSystem(), load, null, null)
        // Absurd provider values are bounded twice: the clear-sky index is capped at 1.2 and power at the peak.
        assertTrue(s.estimate.powerKw <= PvSystem().peakPowerKw)
        assertTrue(s.estimate.irradiance.dni <= com.solartracker.pro.core.pv.ClearSkyModel.extraterrestrialIrradiance(t))
        assertTrue(s.estimate.powerKw > 0.5 * PvSystem().peakPowerKw)
    }

    // --- 11: battery integration ---

    @Test
    fun battery_chargesFromSurplusAndDischargesOnDeficit() {
        val battery = BatteryStorage()
        val sim = EnergyFlowSimulator()
        val surplus = sim.instantFlow(1.84, 0.72, battery, 78.0)
        assertEquals(0.72, surplus.directKw, 1e-9)
        assertEquals(1.12, surplus.chargeKw, 1e-9)
        assertEquals(1.12, surplus.batteryKw, 1e-9)
        assertEquals(0.0, surplus.gridImportKw + surplus.exportKw, 1e-9)

        val deficit = sim.instantFlow(1.2, 2.0, battery, 78.0)
        assertEquals(-0.8, deficit.batteryKw, 1e-9)
        assertEquals(0.0, deficit.gridImportKw, 1e-9)
    }

    @Test
    fun battery_respectsLimitsAndSocBounds() {
        val battery = BatteryStorage(maxChargePowerKw = 1.0, maxDischargePowerKw = 0.5)
        val sim = EnergyFlowSimulator()
        val limitedCharge = sim.instantFlow(4.0, 0.5, battery, 50.0)
        assertEquals(1.0, limitedCharge.chargeKw, 1e-9)
        assertEquals(2.5, limitedCharge.exportKw, 1e-9)
        val full = sim.instantFlow(4.0, 0.5, battery, 100.0)
        assertEquals(0.0, full.chargeKw, 1e-9)
        assertEquals(3.5, full.exportKw, 1e-9)
        val limitedDischarge = sim.instantFlow(0.0, 2.0, battery, 50.0)
        assertEquals(0.5, limitedDischarge.dischargeKw, 1e-9)
        assertEquals(1.5, limitedDischarge.gridImportKw, 1e-9)
        val empty = sim.instantFlow(0.0, 2.0, battery, 10.0)
        assertEquals(0.0, empty.dischargeKw, 1e-9)
        assertEquals(2.0, empty.gridImportKw, 1e-9)
        val noBattery = sim.instantFlow(3.0, 1.0, null, null)
        assertEquals(2.0, noBattery.exportKw, 1e-9)
    }

    @Test
    fun snapshot_combinesPvLoadAndSimulatedSoc() {
        val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0)
        val battery = BatteryStorage()
        val sim = EnergyFlowSimulator(estimator)
        val dayResult = sim.simulate(system, warsaw, day, 1, zone, load, battery)
        val t = at(11, 7, 42)
        val soc = dayResult.socAt(t)!!
        val s = LiveSolarCalculator(estimator, sim).snapshot(t, zone, warsaw, system, load, battery, soc)
        assertEquals(soc, s.socPercent!!, 0.0)
        assertEquals(battery.storedKwh(soc), s.storedKwh!!, 1e-12)
        assertEquals(0.72, s.flow.loadKw, 0.0)
        assertEquals(s.estimate.powerKw - 0.72, s.flow.chargeKw + s.flow.exportKw, 1e-9)
        // SOC interpolation is continuous across simulation steps.
        val a = dayResult.socAt(at(11, 14, 59))!!
        val b = dayResult.socAt(at(11, 15, 0))!!
        assertTrue(kotlin.math.abs(a - b) < 0.05)
    }

    // --- sun path ---

    @Test
    fun sunPath_coversDayWithHighestPointAtNoon() {
        val path = SunPath.forDay(warsaw, day, zone)
        assertEquals(289, path.points.size)
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        assertTrue(Duration.between(noon, path.highest.time).abs() <= Duration.ofMinutes(3))
        assertEquals(61.2, path.highest.elevationDeg, 0.3)
    }
}

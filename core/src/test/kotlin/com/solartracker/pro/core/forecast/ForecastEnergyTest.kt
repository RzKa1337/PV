package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Energy = ∫ P dt. The forecast engines must integrate over exact slices (midpoint rule), never sum power samples
 * taken at the start of each step, and the nowcast must not apply the calibration a second time.
 */
class ForecastEnergyTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 6.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val weather = WeatherAwareIrradianceModel(loc)
    private val date = LocalDate.of(2026, 6, 21)
    private val reference = PvEstimator(weather)
    private val engine = EnergyForecastEngine(
        PredictivePvEngine(loc, system, weather, null),
        LoadForecaster(emptyList(), zone, ConsumptionProfile.constant(0.5), Instant.EPOCH), null, zone,
    )

    @Test
    fun remainingEnergyOfTheDayMatchesFineIntegrationAtAnyStartTime() {
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        // Start times that are NOT on the 15-minute grid. With samples at the start of each step the evening value was
        // off by +4.6 % at 14:07:30 and +19 % at 18:07:30 (the first sample carries half a step of the boundary power).
        for ((h, m) in listOf(7 to 7, 10 to 7, 14 to 7, 18 to 7, 20 to 37)) {
            val now = date.atTime(h, m, 30).atZone(zone).toInstant()
            val fine = reference.energyKwh(system, loc, now, end, stepMinutes = 1)
            val remaining = engine.day(date, now, producedSoFarKwh = 1.0).remainingKwh
            assertEquals("remaining at $h:$m", fine, remaining, 0.004 * fine + 0.002)
        }
    }

    @Test
    fun wholeDayFromMidnightMatchesFineIntegrationAndDstDayHas23Hours() {
        val whole = engine.day(date, date.minusDays(1).atTime(12, 0).atZone(zone).toInstant(), null)
        assertEquals(reference.dailyEnergyKwh(system, loc, date, zone, 1), whole.expectedKwh, 0.003 * whole.expectedKwh)
        val dst = LocalDate.of(2026, 3, 29)
        val slices = midpointSlices(dst.atStartOfDay(zone).toInstant(), dst.plusDays(1).atStartOfDay(zone).toInstant(), Duration.ofMinutes(15))
        assertEquals(23.0, slices.sumOf { it.second }, 1e-9)
        assertEquals(92, slices.size)
    }

    @Test
    fun slicesCoverExactlyTheIntervalWithMidpoints() {
        val from = Instant.parse("2026-06-21T10:07:30Z")
        val to = Instant.parse("2026-06-21T10:40:00Z")
        val s = midpointSlices(from, to, Duration.ofMinutes(15))
        assertEquals(3, s.size)
        assertEquals(Duration.between(from, to).toMillis() / 3.6e6, s.sumOf { it.second }, 1e-12)
        assertEquals(Instant.parse("2026-06-21T10:15:00Z"), s[0].first)
        assertEquals(Instant.parse("2026-06-21T10:38:45Z"), s[2].first) // last slice 10:37:30 – 10:40:00 (2.5 min)
        assertTrue(midpointSlices(to, from, Duration.ofMinutes(15)).isEmpty())
    }

    @Test
    fun noBatteryOutlookEnergyUsesTheSameIntegration() {
        val now = date.atTime(14, 7, 30).atZone(zone).toInstant()
        val pv = PredictivePvEngine(loc, system, weather, null)
        val load = LoadForecaster(emptyList(), zone, ConsumptionProfile.constant(0.5), now)
        val today = EnergySecurityAnalyzer(null, zone).outlook(now, null, DataKind.UNKNOWN, { pv.at(it, now) }, { load.at(it) }).first()
        val fine = reference.energyKwh(system, loc, now, date.plusDays(1).atStartOfDay(zone).toInstant(), 1)
        assertEquals(fine, today.pvKwh, 0.004 * fine)
        // Constant 0.5 kW load for the rest of the day, in kWh.
        val hoursLeft = Duration.between(now, date.plusDays(1).atStartOfDay(zone).toInstant()).toMillis() / 3.6e6
        assertEquals(0.5 * hoursLeft, today.loadKwh, 1e-6)
    }

    @Test
    fun nowcastDoesNotApplyTheCalibrationTwice() {
        val noon = date.atTime(13, 0).atZone(zone).toInstant()
        val calibrated = PredictivePvEngine(loc, system, weather, null, calibrationFactor = 0.9, calibrationConfidence = 1.0)
        val uncalibrated = reference.pointEstimate(system, loc, noon).powerKw
        val measured = uncalibrated * 0.9 // the array produces exactly what the calibrated model expects
        // Old caller convention (measured / uncalibrated model) would lower a perfect forecast by another 10 %:
        assertEquals(0.9 * measured, calibrated.at(noon, noon, measured / uncalibrated).expectedKw, 1e-9)
        // Engine-consistent ratio: the forecast for the measured instant equals the measurement.
        val ratio = calibrated.nowcastRatio(measured, noon)!!
        assertEquals(1.0, ratio, 1e-9)
        assertEquals(measured, calibrated.at(noon, noon, ratio).expectedKw, 1e-9)
        // Cloud passes: half of the expected power measured → forecast now = measurement, fading later.
        val half = calibrated.nowcastRatio(measured / 2, noon)!!
        assertEquals(measured / 2, calibrated.at(noon, noon, half).expectedKw, 1e-9)
    }

    @Test
    fun nowcastRatioIsNullWhenExpectationIsTinyOrMeasurementInvalid() {
        val engine = PredictivePvEngine(loc, system, weather, null)
        val night = date.atTime(2, 0).atZone(zone).toInstant()
        assertNull(engine.nowcastRatio(0.5, night))
        val noon = date.atTime(13, 0).atZone(zone).toInstant()
        assertNull(engine.nowcastRatio(Double.NaN, noon))
        assertNull(engine.nowcastRatio(-1.0, noon))
        assertNotNull(engine.nowcastRatio(2.0, noon))
    }
}

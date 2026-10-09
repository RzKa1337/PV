package com.solartracker.pro.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** What the calibration must never learn from, and how hourly forecasts are paired with measured energy. */
class CalibrationHardeningTest {
    private val start = Instant.parse("2026-05-01T00:00:00Z")

    private fun good(days: Int, perDay: Int = 24, ratio: Double = 0.9) = (0 until days).flatMap { d ->
        (0 until perDay).map { i ->
            CalibrationObservation(start.plusSeconds(d * 86_400L + 8 * 3600L + i * 600L), 3.0 * ratio, 3.0, 35.0,
                sunElevationDeg = 45.0, irradianceWm2 = 700.0, condition = SkyCondition.CLEAR)
        }
    }

    private fun bad(n: Int, tweak: (CalibrationObservation) -> CalibrationObservation) = (0 until n).map { i ->
        tweak(CalibrationObservation(start.plusSeconds(86_400L + 9 * 3600L + i * 60L), 0.6, 3.0, 35.0, sunElevationDeg = 45.0, irradianceWm2 = 700.0))
    }

    @Test
    fun lowLightSamplesAreNotLearnedFrom() {
        // Dawn-like samples: model 3 kW at POA 30 W/m² is impossible for a sane model, the real inverter gives 0.6 kW.
        val lowLight = bad(150) { it.copy(irradianceWm2 = 30.0) }
        val model = AutoCalibrationEngine().buildModel(good(6) + lowLight, ZoneOffset.UTC)
        assertEquals(0.9, model.globalFactor, 0.01)
        assertEquals(150, model.excluded.entries.single { "nasłonecznienie" in it.key }.value)
        // Unknown irradiance (older rows) is not excluded by this filter.
        assertNull(AutoCalibrationEngine().exclusionReason(good(1).first().copy(irradianceWm2 = null)))
    }

    @Test
    fun evaluateUsesTheSameExclusionsAsTheModel() {
        // 6 days × 24 good samples (ratio 0.9) drowned in faulty / offline / invalid / low-sun samples with ratio 0.2.
        val junk = bad(100) { it.copy(fault = true) } + bad(100) { it.copy(linkOk = false) } +
            bad(100) { it.copy(invalidTelemetry = true) } + bad(100) { it.copy(sunElevationDeg = 4.0) }
        val corrections = AutoCalibrationEngine().evaluate(good(6) + junk, null, start.plusSeconds(10 * 86_400L))
        val yield = corrections.single { it.kind == CorrectionKind.YIELD }
        assertEquals(0.9, yield.value, 0.02)
        assertEquals(144, yield.samples)
    }

    @Test
    fun clippedSamplesCountOnlyForTheClippingShare() {
        val clipped = bad(36) { it.copy(nearLimit = true) } // 144 good + 36 clipped → 20 %
        val corrections = AutoCalibrationEngine().evaluate(good(6) + clipped, null, start.plusSeconds(10 * 86_400L))
        assertEquals(0.9, corrections.single { it.kind == CorrectionKind.YIELD }.value, 0.02)
        assertEquals(20.0, corrections.single { it.kind == CorrectionKind.CLIPPING }.value, 1e-9)
    }

    private fun row(s: Instant, minutes: Long, kwh: Double) =
        HistorySample(s, s.plusSeconds(minutes * 60), 30, null, null, null, null, null, null, null, null, null, null,
            kwh, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptySet(), emptySet())

    @Test
    fun hourlyPairingWorksInZonesWithHalfHourOffsets() {
        // India (UTC+5:30): local hour 16:00 starts at 10:30 UTC. Rows are kept per local hour, not per UTC hour.
        val h = Instant.parse("2026-06-01T10:30:00Z")
        val rows = (0 until 4).map { row(h.plusSeconds(it * 900L), 15, 0.5) }
        val pairs = ForecastAccuracy.pairHourly(mapOf(h to 2.2), rows)
        assertEquals(1, pairs.size)
        assertEquals(2.0, pairs[0].actual, 1e-9)
        // A row from the previous hour does not leak into this one.
        val withNeighbour = rows + row(h.minusSeconds(900), 15, 5.0)
        assertEquals(2.0, ForecastAccuracy.pairHourly(mapOf(h to 2.2), withNeighbour)[0].actual, 1e-9)
    }

    @Test
    fun relativeErrorOnlyAboveAPowerThresholdAndMetricsAreExact() {
        // Hand-computed: errors (f−a) = +0.2, −0.1, +0.5 → MAE 0.8/3, RMSE sqrt((0.04+0.01+0.25)/3), bias (Σf−Σa)/Σa.
        val pairs = listOf(ForecastPair(start, 1.2, 1.0), ForecastPair(start, 0.9, 1.0), ForecastPair(start, 2.5, 2.0), ForecastPair(start, 0.03, 0.01))
        val r = ForecastAccuracy.evaluate(pairs.take(3))
        assertEquals(0.8 / 3, r.mae, 1e-12)
        assertEquals(Math.sqrt(0.30 / 3), r.rmse, 1e-12)
        assertEquals((4.6 - 4.0) / 4.0 * 100, r.biasPercent!!, 1e-9)
        // The tiny pair (actual 0.01 < 0.05 kWh) enters MAE/RMSE but not the percentage error.
        val withTiny = ForecastAccuracy.evaluate(pairs)
        assertEquals(r.mapePercent!!, withTiny.mapePercent!!, 1e-12)
        assertTrue(withTiny.mae < r.mae)
        assertNull(ForecastComparison.relativeError(0.01, 0.5))
        assertEquals(0.25, ForecastComparison.relativeError(2.0, 2.5)!!, 1e-12)
    }
}

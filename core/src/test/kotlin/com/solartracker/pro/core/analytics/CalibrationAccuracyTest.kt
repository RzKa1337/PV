package com.solartracker.pro.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CalibrationAccuracyTest {
    private val t0 = Instant.parse("2026-06-01T10:00:00Z")

    @Test
    fun accuracyMetrics() {
        val pairs = listOf(ForecastPair(t0, 1.0, 1.0), ForecastPair(t0, 2.2, 2.0), ForecastPair(t0, 3.3, 3.0), ForecastPair(t0, 0.02, 0.0))
        val r = ForecastAccuracy.evaluate(pairs)
        assertEquals(4, r.count)
        assertEquals((0.0 + 0.2 + 0.3 + 0.02) / 4, r.mae, 1e-9)
        assertEquals(Math.sqrt((0.04 + 0.09 + 0.0004) / 4), r.rmse, 1e-9)
        assertEquals((0.0 + 10.0 + 10.0) / 3, r.mapePercent!!, 1e-9)
        assertEquals((6.52 - 6.0) / 6.0 * 100, r.biasPercent!!, 1e-9)
        assertEquals(100 - 0.52 / 6.0 * 100, r.accuracyPercent!!, 1e-9)
        assertTrue(r.describe().contains("zawyża"))
        val empty = ForecastAccuracy.evaluate(emptyList())
        assertNull(empty.accuracyPercent)
        assertEquals("Za mało danych do oceny prognozy", empty.describe())
    }

    private fun obs(day: Int, i: Int, ratio: Double, cell: Double? = 40.0, limit: Boolean = false) =
        CalibrationObservation(t0.plus(Duration.ofDays(day.toLong())).plusSeconds(i * 300L), 3.0 * ratio, 3.0, cell, limit)

    @Test
    fun yieldTemperatureAndTrend() {
        // 20 days, ratio falls 0.3%/day; cell temperature varies 20..50 °C with an extra −0.2%/°C effect.
        val list = (0 until 20).flatMap { d -> (0 until 10).map { i ->
            val cell = 20.0 + i * 3.0
            obs(d, i, (0.95 - 0.003 * d) * (1 - 0.002 * (cell - 25.0)), cell)
        } }
        val engine = AutoCalibrationEngine(minSamples = 60)
        val c = engine.evaluate(list, null, t0.plus(Duration.ofDays(20)))
        val yieldC = c.first { it.kind == CorrectionKind.YIELD }
        assertTrue(yieldC.ready)
        assertEquals(0.9, yieldC.value, 0.05)
        val temp = c.first { it.kind == CorrectionKind.TEMPERATURE_COEFFICIENT }
        assertTrue(temp.note, temp.ready)
        assertEquals(-0.2, temp.value, 0.06)
        val trend = c.first { it.kind == CorrectionKind.SOILING_TREND }
        assertTrue(trend.note, trend.ready)
        assertFalse("trend is reported, not auto-applied", trend.enabled)
        assertEquals(-0.3 * 30 / 0.92, trend.value, 3.0)
        assertTrue(engine.history().any { it.kind == CorrectionKind.YIELD })
    }

    @Test
    fun disabledAndNotReadyCorrectionsAreNotApplied() {
        val few = (0 until 5).map { obs(0, it, 0.9) }
        val engine = AutoCalibrationEngine(disabled = setOf(CorrectionKind.YIELD))
        val c = engine.evaluate(few, null, t0)
        val y = c.first { it.kind == CorrectionKind.YIELD }
        assertFalse(y.ready)
        assertFalse(y.enabled)
        assertTrue(engine.history().isEmpty())
        val clipped = (0 until 60).map { obs(it / 10, it, 0.9, limit = it % 2 == 0) }
        val share = AutoCalibrationEngine().evaluate(clipped, null, t0).first { it.kind == CorrectionKind.CLIPPING }
        assertEquals(50.0, share.value, 1e-9)
    }

    @Test
    fun forecastBiasCorrection() {
        val report = ForecastAccuracy.evaluate((0 until 60).map { ForecastPair(t0.plusSeconds(it * 3600L), 1.1, 1.0) })
        val c = AutoCalibrationEngine().evaluate(emptyList(), report, t0).first { it.kind == CorrectionKind.FORECAST_BIAS }
        assertTrue(c.ready)
        assertEquals(1 / 1.1, c.value, 1e-9)
    }

    @Test
    fun linearFit() {
        val f = AutoCalibrationEngine.linearFit(listOf(0.0, 1.0, 2.0), listOf(1.0, 3.0, 5.0))
        assertEquals(2.0, f.slope, 1e-12)
        assertEquals(1.0, f.intercept, 1e-12)
        assertEquals(1.0, f.r2, 1e-12)
    }
}

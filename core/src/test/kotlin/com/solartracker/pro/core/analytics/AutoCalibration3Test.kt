package com.solartracker.pro.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AutoCalibration3Test {
    private val zone = ZoneOffset.UTC
    private val start = Instant.parse("2026-05-01T00:00:00Z")

    /** [days] of samples 08–16 h every 10 min; real = model × ratio(condition); alternating clear/overcast days. */
    private fun observations(days: Int, clearRatio: Double = 0.9, overcastRatio: Double = 0.7, noise: (Int) -> Double = { 0.0 }): List<CalibrationObservation> {
        var i = 0
        return (0 until days).flatMap { d ->
            val cond = if (d % 2 == 0) SkyCondition.CLEAR else SkyCondition.OVERCAST
            (8 * 6 until 16 * 6).map { m ->
                val t = start.plusSeconds(d * 86_400L + m * 600L)
                val model = if (cond == SkyCondition.CLEAR) 3.0 else 0.8
                val ratio = (if (cond == SkyCondition.CLEAR) clearRatio else overcastRatio) + noise(i++)
                CalibrationObservation(t, model * ratio, model, 35.0, sunElevationDeg = 45.0, condition = cond)
            }
        }
    }

    @Test
    fun learnsPerConditionAndImprovesHeldOutError() {
        val model = AutoCalibrationEngine().buildModel(observations(20), zone)
        assertTrue(model.ready)
        assertEquals(20, model.trainingDays + 0) // all days usable
        val clear = model.factor(CalibrationContext(start.plusSeconds(12 * 3600), 45.0, SkyCondition.CLEAR))
        val overcast = model.factor(CalibrationContext(start.plusSeconds(12 * 3600), 45.0, SkyCondition.OVERCAST))
        assertTrue("clear $clear vs overcast $overcast", clear > overcast + 0.08)
        val v = model.validation
        assertNotNull(v)
        assertTrue("MAE ${v!!.maeBefore} → ${v.maeAfter}", v.maeAfter < v.maeBefore)
        assertTrue(v.biasBefore > 0 && kotlin.math.abs(v.biasAfter) < v.biasBefore)
        assertTrue(model.describe().contains("MAE"))
    }

    @Test
    fun littleDataBarelyChangesForecast() {
        val small = AutoCalibrationEngine(minSamples = 60).buildModel(observations(1), zone)
        assertFalse(small.ready)
        assertEquals(1.0, small.factor(CalibrationContext(start, 45.0, SkyCondition.CLEAR)), 0.0)
        // Ready but only 4 days → confidence < 1, correction smaller than the learned ratio.
        val few = AutoCalibrationEngine(minSamples = 60).buildModel(observations(4, 0.8, 0.8), zone)
        assertTrue(few.ready)
        val f = few.factor(CalibrationContext(start, 45.0, SkyCondition.CLEAR))
        assertTrue("factor $f should be between 0.8 and 1.0", f > 0.85 && f < 1.0)
    }

    @Test
    fun badSamplesAreExcludedWithReasons() {
        val base = observations(6)
        val t = start.plusSeconds(3600)
        val bad = listOf(
            CalibrationObservation(t, 0.0, 3.0, null, sunElevationDeg = 40.0),
            CalibrationObservation(t, 2.0, 3.0, null, nearLimit = true, sunElevationDeg = 40.0),
            CalibrationObservation(t, 2.0, 3.0, null, fault = true, sunElevationDeg = 40.0),
            CalibrationObservation(t, 2.0, 3.0, null, linkOk = false, sunElevationDeg = 40.0),
            CalibrationObservation(t, 2.0, 3.0, null, invalidTelemetry = true, sunElevationDeg = 40.0),
            CalibrationObservation(t, 2.0, 3.0, null, sunElevationDeg = 5.0),
        )
        val model = AutoCalibrationEngine().buildModel(base + bad, zone)
        assertEquals(6, model.excluded.values.sum())
        assertEquals(base.size, model.sampleCount)
        assertTrue(model.excluded.keys.any { it.contains("clipping") })
    }

    @Test
    fun skyClassification() {
        assertEquals(SkyCondition.SNOW, SkyClassifier.classify(90.0, 0.0, 0.05, -2.0, null))
        assertEquals(SkyCondition.SNOW, SkyClassifier.classify(90.0, 1.0, 0.0, -1.0, null))
        assertEquals(SkyCondition.RAIN, SkyClassifier.classify(90.0, 1.0, 0.0, 10.0, null))
        assertEquals(SkyCondition.CLEAR, SkyClassifier.classify(80.0, 0.0, 0.0, 10.0, 0.9))
        assertEquals(SkyCondition.OVERCAST, SkyClassifier.classify(10.0, 0.0, 0.0, 10.0, 0.2))
        assertEquals(SkyCondition.PARTLY_CLOUDY, SkyClassifier.classify(50.0, null, null, null, null))
        assertEquals(SkyCondition.UNKNOWN, SkyClassifier.classify(null, null, null, null, null))
    }
}

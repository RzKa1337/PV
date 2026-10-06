package com.solartracker.pro.core.health

import com.solartracker.pro.core.pv.Irradiance
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PvPerformanceAnalyzerTest {
    private val loc = GeoLocation(52.23, 21.01)
    private val noon = Instant.parse("2026-06-21T11:00:00Z")
    private val array = PvArrayConfig(5, 400.0, 35.0, 180.0)
    private val sunny = PvConditions(Irradiance(700.0, 150.0), ambientC = 25.0, windMs = 2.0)

    @Test
    fun lossesAndUnknownAddUpToTheGap() {
        val model = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, sunny, actualW = null)
        assertNull(model.performancePercent)
        val actual = model.modelOutputW * 0.95
        val r = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, sunny, actualW = actual, shadingFactor = 0.97,
            clearSkyConditions = PvConditions(Irradiance(880.0, 90.0), 25.0, 2.0))
        val perf = r.performancePercent!!
        assertEquals(100.0 - perf, r.losses.sumOf { it.percent }, 1e-6)
        assertTrue(r.losses.any { it.cause == LossCause.SHADING })
        assertTrue(r.losses.filter { it.cause != LossCause.UNKNOWN }.all { it.kind == DataKind.ESTIMATED })
        val unknown = r.losses.single { it.cause == LossCause.UNKNOWN }
        assertTrue(unknown.percent > 0)
        assertTrue(r.weatherVsClearSkyPercent!! > 0)
        assertTrue(r.status.contains("zgodna"))
    }

    @Test
    fun communicationAndLowLight() {
        val stale = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, sunny, actualW = 1500.0, fresh = false)
        assertNull(stale.performancePercent)
        assertTrue(stale.status.contains("komunikacja"))
        val dark = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, PvConditions(Irradiance(0.0, 20.0)), actualW = 10.0)
        assertNull(dark.performancePercent)
        val low = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, sunny, actualW = 300.0)
        assertTrue(low.status.contains("nieznana"))
        val high = PvPerformanceAnalyzer.analyze(array, LossProfile(), loc, noon, sunny, actualW = low.expectedW * 0.99)
        assertTrue(high.losses.single { it.cause == LossCause.UNKNOWN }.percent < 0)
    }
}

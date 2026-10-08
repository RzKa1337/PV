package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.anenji.AnalysisContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thin high cirrus: Open-Meteo reports 100 % total cloud cover while ~99 % of the clear-sky irradiance gets through. */
class ThinCloudTest {
    @Test
    fun effectiveCloudComesFromIrradianceNotTotalCover() {
        assertEquals(1.0, SkyClassifier.effectiveCloudPercent(0.99, 100.0)!!, 1e-9)
        assertEquals(100.0, SkyClassifier.effectiveCloudPercent(null, 100.0)!!, 0.0) // no irradiance → total cover as fallback
        assertNull(SkyClassifier.effectiveCloudPercent(null, null))
        assertEquals(SkyCondition.CLEAR, SkyClassifier.classify(100.0, 0.0, 0.0, 22.0, 0.99))
        assertEquals(1.0, AnalysisContext(cloudCoverPercent = 100.0, clearSkyIndex = 0.99).effectiveCloudPercent!!, 1e-9)
    }

    @Test
    fun cirrusIsNotBlamedForLowPv() {
        val ctx = ComparisonContext(sunElevationDeg = 47.0, modelUnshadedKw = 2.0, cloudCoverPercent = 100.0, clearSkyIndex = 0.99)
        val r = ModelComparison.compare(2.0, 1.4, ctx)
        assertTrue(r.causes.toString(), r.causes.none { it.cause == DeviationCause.CLOUDS })
        assertTrue(r.causes.toString(), r.causes.any { it.cause == DeviationCause.SOILING })
        val overcast = ModelComparison.compare(2.0, 1.4, ctx.copy(clearSkyIndex = 0.3))
        assertTrue(overcast.causes.any { it.cause == DeviationCause.CLOUDS })
    }
}

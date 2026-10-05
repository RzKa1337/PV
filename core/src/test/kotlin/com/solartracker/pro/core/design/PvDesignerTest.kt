package com.solartracker.pro.core.design

import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PvDesignerTest {
    // Test inputs only (generic 450 W panel, generic single-MPPT inverter) — not a real product's datasheet.
    private val panel = PanelSpec(450.0, 49.5, 41.5, 11.6, 10.85, -0.0027, -0.0035, 1.9, 1.13)
    private val mppt = MpptSpec(trackers = 1, minMpptV = 120.0, maxMpptV = 450.0, maxInputV = 500.0, maxCurrentA = 18.0, ratedAcW = 6200.0)

    @Test
    fun sizesStringWithinVoltageWindow() {
        val r = PvDesigner.design(DesignInput(panel, mppt, targetPowerW = 3000.0, costPerWp = 2.5))
        assertEquals(8, r.maxSeries)
        assertEquals(4, r.minSeries)
        assertEquals(1, r.maxParallel)
        assertEquals(StringLayout(7, 1, 1), r.layout)
        assertEquals(3150.0, r.dcPowerW, 1e-9)
        assertTrue(r.vocColdV < mppt.maxInputV && r.vmpHotV > mppt.minMpptV && r.vmpColdV < mppt.maxMpptV)
        assertEquals(3150.0 * 2.5, r.investment!!, 1e-9)
        assertTrue(r.warnings.isEmpty())
        // Without a target the longest valid configuration is used.
        assertEquals(8, PvDesigner.design(DesignInput(panel, mppt)).layout!!.panels)
        // Two trackers double the capacity.
        assertEquals(16, PvDesigner.design(DesignInput(panel, mppt.copy(trackers = 2))).layout!!.panels)
    }

    @Test
    fun reportsImpossibleDesigns() {
        val small = PvDesigner.design(DesignInput(panel, mppt, areaM2 = 10.0))
        assertNull(small.layout)
        assertTrue(small.warnings.any { it.contains("najkrótszy string") })
        val window = PvDesigner.design(DesignInput(panel, mppt.copy(minMpptV = 400.0, maxMpptV = 420.0)))
        assertNull(window.layout)
        assertTrue(window.warnings.isNotEmpty())
        val current = PvDesigner.design(DesignInput(panel, mppt.copy(maxCurrentA = 8.0)))
        assertNull(current.layout)
    }

    @Test
    fun annualClearSkyYieldAndLocations() {
        val array = PvArrayConfig(10, 400.0, 35.0, 180.0)
        val losses = LossProfile()
        val north = GeoLocation(52.23, 21.01)
        val y = YieldEstimator.annual(array, losses, north, stepMinutes = 60)
        assertTrue(y.specificClearSkyKwhPerKwp in 900.0..2500.0)
        assertTrue(y.monthlyClearSkyKwh[5] > 2.5 * y.monthlyClearSkyKwh[11])
        assertNull(y.expectedKwh)
        assertEquals(DataKind.UNKNOWN, y.expectedKind)
        assertEquals(y.clearSkyKwh * 0.5, YieldEstimator.annual(array, losses, north, 0.5, stepMinutes = 60).expectedKwh!!, 1e-6)

        val sites = LocationComparison.compare(listOf("Północ" to GeoLocation(60.0, 20.0), "Południe" to GeoLocation(37.0, 20.0)), array, losses)
        assertEquals("Południe", sites.first().name)
        val n = sites.first { it.name == "Północ" }
        assertTrue(n.optimalTiltDeg in 30.0..65.0)
        assertTrue(n.optimalTiltGainPercent >= 0.0)
    }
}

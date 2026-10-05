package com.solartracker.pro.core.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureAccessTest {
    @Test
    fun planGating() {
        var state = SubscriptionState.DEFAULT
        val m = FeatureAccessManager { state }
        assertTrue(Feature.entries.all { m.isEnabled(it) })
        assertTrue(m.locked().isEmpty())
        state = SubscriptionState(Plan.FREE, SubscriptionSource.LOCAL_FREE)
        assertTrue(m.isEnabled(Feature.LIVE_SOLAR))
        assertFalse(m.isEnabled(Feature.DESIGNER))
        assertNull(m.reason(Feature.INVERTER_MONITORING))
        assertEquals("Projektant PV wymaga planu PRO", m.reason(Feature.DESIGNER))
        assertTrue(m.locked().all { it.minPlan == Plan.PRO })
    }
}

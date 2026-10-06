package com.solartracker.pro.core.twin

import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.health.FaultLevel
import com.solartracker.pro.core.health.FaultSeverity
import com.solartracker.pro.core.health.FaultWarning
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.SmgModbusMapper
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.SolarPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DigitalTwinTest {
    private val now = Instant.parse("2026-06-21T11:00:00Z")
    private val tel = SmgModbusMapper("anenji-smg").map(AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON), now)

    private fun input(fresh: Boolean = true, link: LinkStatus = LinkStatus.ONLINE, warnings: List<FaultWarning> = emptyList()) =
        TwinInput(now, SolarPosition(60.0, 180.0), 850.0, "prognoza pogody", tel, fresh, link, 3.8, 3.7, null, null, 0.6, warnings)

    @Test
    fun chainWithProvenance() {
        val twin = DigitalTwinBuilder.build(input())
        assertEquals(TwinElement.entries, twin.nodes.map { it.element })
        assertEquals(DataKind.MEASURED, twin.node(TwinElement.PV_ARRAY).measured.getValue("moc").kind)
        assertEquals(3914.0, twin.node(TwinElement.PV_ARRAY).measured.getValue("moc").value!!, 0.0)
        assertEquals(DataKind.FORECAST, twin.node(TwinElement.PV_ARRAY).forecast.getValue("prognoza teraz").kind)
        assertEquals(DataKind.ESTIMATED, twin.node(TwinElement.SUN).forecast.getValue("nasłonecznienie paneli").kind)
        assertEquals(TwinHealth.OK, twin.node(TwinElement.INVERTER).health)
    }

    @Test
    fun healthFollowsLinkAndWarnings() {
        val stale = DigitalTwinBuilder.build(input(fresh = false, link = LinkStatus.OFFLINE))
        assertEquals(DataKind.LAST_KNOWN, stale.node(TwinElement.BATTERY).measured.getValue("SOC").kind)
        assertEquals(TwinHealth.FAULT, stale.node(TwinElement.INVERTER).health)
        assertEquals(TwinHealth.UNKNOWN, stale.node(TwinElement.PV_ARRAY).health)
        val w = FaultWarning("battery-depletion", "x", FaultSeverity.CRITICAL, FaultLevel.ANOMALY, now, "r", listOf("e"), 0.9, "a")
        assertEquals(TwinHealth.FAULT, DigitalTwinBuilder.build(input(warnings = listOf(w))).node(TwinElement.BATTERY).health)
    }
}

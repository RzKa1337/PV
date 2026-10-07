package com.solartracker.pro.core.energy

import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.inverter.SmgModbusMapper
import com.solartracker.pro.core.quality.DataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SocEstimatorTest {
    private val now = Instant.parse("2026-06-21T22:00:00Z")
    private val mapper = SmgModbusMapper("anenji-smg")
    private fun read(s: AnenjiFixtures.Snapshot, t: Instant) = mapper.map(AnenjiFixtures.blocks(s), t)

    @Test
    fun measuredWhenReportedEstimatedOnlyAtRest() {
        val withSoc = read(AnenjiFixtures.BATTERY_LOW, now)
        assertEquals(DataKind.MEASURED, SocEstimator.estimate(withSoc, emptyList(), BatteryType.AGM, 48.0).kind)

        // Inverter without SOC (e.g. lead-acid without BMS): simulate by dropping the value.
        fun noSoc(t: Instant, amps: Double) = read(AnenjiFixtures.BATTERY_LOW.copy(batV = 49.0, batA = amps, batW = (amps * 49).toInt()), t)
            .let { it.copy(battery = it.battery.copy(socPercent = null)) }
        val rest = (0..20).map { noSoc(now.minusSeconds(600L - it * 30), 0.5) }
        val q = SocEstimator.estimate(rest.last(), rest.dropLast(1), BatteryType.AGM, 48.0)
        assertEquals(DataKind.ESTIMATED, q.kind)
        assertEquals(50.8, q.value!!, 1.0) // 49.0 V / 24 cells = 2.042 V ≈ 50% on the AGM curve
        assertTrue(q.uncertainty!! <= 10)

        val loaded = (0..20).map { noSoc(now.minusSeconds(600L - it * 30), -12.0) }
        assertEquals(DataKind.UNKNOWN, SocEstimator.estimate(loaded.last(), loaded.dropLast(1), BatteryType.AGM, 48.0).kind)
        // Too short a rest window → still UNKNOWN.
        assertEquals(DataKind.UNKNOWN, SocEstimator.estimate(rest.last(), rest.takeLast(3), BatteryType.AGM, 48.0).kind)
        assertEquals(DataKind.UNKNOWN, SocEstimator.estimate(null, emptyList(), BatteryType.AGM, 48.0).kind)
    }
}

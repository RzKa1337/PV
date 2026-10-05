package com.solartracker.pro.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ForecastPairingTest {
    private val h0 = Instant.parse("2026-06-01T10:00:00Z")

    private fun row(start: Instant, minutes: Long, kwh: Double) =
        HistorySample(start, start.plusSeconds(minutes * 60), 30, null, null, null, null, null, null, null, null, null, null,
            kwh, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptySet(), emptySet())

    @Test
    fun pairsOnlyWellCoveredHours() {
        val samples = (0 until 4).map { row(h0.plusSeconds(it * 900L), 15, 0.5) } + // full hour → 2.0 kWh
            row(h0.plusSeconds(3600), 15, 0.4) // 15 min of the next hour → skipped
        val forecasts = mapOf(h0 to 2.4, h0.plusSeconds(3600) to 2.0, h0.plusSeconds(7200) to 1.0)
        val pairs = ForecastAccuracy.pairHourly(forecasts, samples)
        assertEquals(1, pairs.size)
        assertEquals(2.0, pairs[0].actual, 1e-9)
        val r = ForecastAccuracy.evaluate(pairs)
        assertEquals(20.0, r.biasPercent!!, 1e-9)
    }
}

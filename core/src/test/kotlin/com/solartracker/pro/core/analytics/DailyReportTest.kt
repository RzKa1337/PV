package com.solartracker.pro.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DailyReportTest {
    private val zone = ZoneOffset.UTC
    private fun day(date: LocalDate, pvKwh: Double, loadKwh: Double): List<HistorySample> = (0 until 96).map { i ->
        val start = date.atStartOfDay(zone).toInstant().plusSeconds(i * 900L)
        val sun = i in 28..72
        HistorySample(start, start.plusSeconds(900), 30, if (sun) pvKwh / 45 * 4000 else 0.0, if (sun) pvKwh / 45 * 4400 else 0.0, loadKwh / 96 * 4000,
            null, null, 52.0, null, 40.0 + i * 0.5, 30.0, null,
            if (sun) pvKwh / 45 else 0.0, loadKwh / 96, 0.01, 0.0, if (sun) 0.05 else 0.0, if (sun) 0.0 else 0.06, null, emptySet(), emptySet())
    }

    @Test
    fun reportWithComparisons() {
        val d = LocalDate.of(2026, 6, 21)
        val rows = (1..7).flatMap { day(d.minusDays(it.toLong()), 10.0, 12.0) } + day(d, 12.0, 12.0)
        val r = DailyReportBuilder.build(d, rows, zone, healthScore = 92, alerts = 1)!!
        assertEquals(12.0, r.pvKwh, 1e-6)
        assertEquals(12.0, r.loadKwh, 1e-6)
        assertEquals(40.0, r.socMin!!, 0.0)
        assertEquals(87.5, r.socMax!!, 0.0)
        assertEquals(1.0, r.coverage, 1e-9)
        assertEquals(20.0, r.vsYesterday.single { it.metric == "PV" }.percent!!, 1e-6)
        assertEquals(0.0, r.vs7DayAverage.single { it.metric == "Zużycie" }.percent!!, 1e-6)
        assertNull(r.vsYesterday.single { it.metric == "Eksport" }.percent) // 0 vs 0
        val text = r.text()
        assertTrue(text, text.contains("PV: 12.0 kWh (+20%)") && text.contains("92/100"))
        assertNull(DailyReportBuilder.build(d.plusDays(1), rows, zone))
    }
}

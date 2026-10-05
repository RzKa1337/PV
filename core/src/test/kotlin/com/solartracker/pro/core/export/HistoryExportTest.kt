package com.solartracker.pro.core.export

import com.solartracker.pro.core.analytics.HistoryPeriod
import com.solartracker.pro.core.analytics.HistoryPeriods
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.inverter.OperatingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryExportTest {
    private fun sample(start: String, pvKwh: Double, loadKwh: Double, imp: Double, exp: Double, soc: Double? = 50.0) = Instant.parse(start).let {
        HistorySample(it, it.plusSeconds(3600), 60, pvKwh * 1000, pvKwh * 1200, loadKwh * 1000, null, null, 52.0, null, soc, 40.0, null,
            pvKwh, loadKwh, imp, exp, 0.0, 0.0, OperatingMode.GRID, setOf(2), emptySet())
    }

    private val samples = listOf(
        sample("2026-06-01T10:00:00Z", 3.0, 1.0, 0.0, 2.0),
        sample("2026-06-01T20:00:00Z", 0.0, 1.0, 1.0, 0.0, soc = null),
        sample("2026-06-03T10:00:00Z", 2.0, 2.0, 0.0, 0.0),
    )

    @Test
    fun periodTotals() {
        val days = HistoryPeriods.totals(samples, HistoryPeriod.DAY, ZoneOffset.UTC, Instant.parse("2026-07-01T00:00:00Z"))
        assertEquals(2, days.size)
        val d = days.first()
        assertEquals(LocalDate.of(2026, 6, 1), d.periodStart)
        assertEquals(3.0, d.pvKwh, 1e-9)
        assertEquals(1.0 / 3, d.selfConsumption!!, 1e-9)
        assertEquals(0.5, d.autarky!!, 1e-9)
        assertEquals(2.0 / 24, d.coverage, 1e-9)
        assertEquals(3600.0, d.peakPvW!!, 1e-9)
        val week = HistoryPeriods.totals(samples, HistoryPeriod.WEEK, ZoneOffset.UTC).single()
        assertEquals(LocalDate.of(2026, 6, 1), week.periodStart) // a Monday
        assertEquals(5.0, week.pvKwh, 1e-9)
        assertEquals(1, HistoryPeriods.totals(samples, HistoryPeriod.LIFETIME, ZoneOffset.UTC).size)
        assertNull(HistoryPeriods.totals(listOf(sample("2026-06-01T20:00:00Z", 0.0, 0.0, 0.0, 0.0)), HistoryPeriod.DAY, ZoneOffset.UTC).single().autarky)
    }

    @Test
    fun csvKeepsMissingValuesEmpty() {
        val csv = HistoryExport.samplesCsv(samples).lines()
        assertTrue(csv[0].startsWith("start,end,samples,pv_w"))
        assertEquals(4, csv.count { it.isNotBlank() })
        val second = csv[2].split(",")
        assertEquals("", second[10]) // soc missing → empty, not 0
        assertEquals("GRID", second[19])
        assertTrue(HistoryExport.totalsCsv(HistoryPeriods.totals(samples, HistoryPeriod.MONTH, ZoneOffset.UTC)).lines()[1].startsWith("MONTH,2026-06-01,5.0000"))
    }

    @Test
    fun jsonIsValidAndComplete() {
        val text = HistoryExport.json(samples, HistoryPeriods.totals(samples, HistoryPeriod.DAY, ZoneOffset.UTC), Instant.parse("2026-07-01T00:00:00Z"), "0.8.0")
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("solar-tracker-pro-history/1", o["format"]!!.jsonPrimitive.content)
        assertEquals(3, o["samples"]!!.jsonArray.size)
        assertEquals("null", o["samples"]!!.jsonArray[1].jsonObject["soc_pct"].toString())
        assertEquals(2, o["totals"]!!.jsonArray.size)
    }
}

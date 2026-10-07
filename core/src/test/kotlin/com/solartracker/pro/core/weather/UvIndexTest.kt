package com.solartracker.pro.core.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class UvIndexTest {
    @Test
    fun levelsAndSummary() {
        assertEquals(UvLevel.LOW, UvLevel.of(2.9))
        assertEquals(UvLevel.MODERATE, UvLevel.of(3.0))
        assertEquals(UvLevel.HIGH, UvLevel.of(6.0))
        assertEquals(UvLevel.VERY_HIGH, UvLevel.of(10.9))
        assertEquals(UvLevel.EXTREME, UvLevel.of(11.0))

        val t0 = Instant.parse("2026-10-07T00:00:00Z")
        val times = (1..24).map { t0.plusSeconds(it * 3600L) }
        val uv = (1..24).map { h -> if (h in 7..17) 6.5 - kotlin.math.abs(h - 12) * 1.2 else 0.0 }
        val json = """{"hourly":{"time":[${times.joinToString { it.epochSecond.toString() }}],"uv_index":[${uv.joinToString()}],
            "uv_index_clear_sky":[${uv.joinToString { (it * 1.1).toString() }}]}}"""
        val f = OpenMeteo.parseForecast(json, t0)
        val s = UvIndex.summary(f, Instant.parse("2026-10-07T09:30:00Z"), ZoneOffset.UTC)!!
        assertEquals(6.5 - 2 * 1.2, s.now!!, 1e-9) // hour ending 10:00
        assertEquals(6.5, s.todayMax!!, 1e-9)
        assertEquals(Instant.parse("2026-10-07T12:00:00Z"), s.todayMaxAt)
        assertEquals(UvLevel.HIGH, s.levelMax)
        val noUv = OpenMeteo.parseForecast("""{"hourly":{"time":[${t0.epochSecond}],"cloud_cover":[50]}}""", t0)
        assertNull(UvIndex.summary(noUv, t0, ZoneOffset.UTC))
    }
}

package com.solartracker.pro.core.shading

import com.solartracker.pro.core.pv.PvSystem
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

enum class DayPart(val label: String) { MORNING("rano"), MIDDAY("w południe"), AFTERNOON("po południu"), EVENING("wieczorem") }

/** Human-readable shadow event with the obstacle's description. */
data class ShadowForecast(
    val event: ShadowEvent,
    val obstacleName: String,
    val obstacleHeightLabel: String,
    val dayPart: DayPart,
)

data class MonthShading(val month: YearMonth, val lossKwh: Double, val unshadedKwh: Double, val eventDays: Int)

/** Answers "when will the shadow come, from what, how much energy is lost" for any period. */
class ShadingForecastService(
    private val engine: ShadingAnalysisEngine,
    private val system: PvSystem,
    private val zone: ZoneId,
) {
    private val names = engine.site.allObstacles.associate { it.obstacle.id to it.obstacle }

    fun forDay(date: LocalDate, stepMinutes: Int = 5): Pair<DayShading, List<ShadowForecast>> {
        val day = engine.day(system, date, zone, stepMinutes)
        return day to day.events.map(::describe)
    }

    /** The next shadow event starting after [now] (today or the next days, up to [days]). */
    fun nextEvent(now: Instant, days: Int = 2): ShadowForecast? {
        val today = now.atZone(zone).toLocalDate()
        for (d in 0..days) {
            val (_, events) = forDay(today.plusDays(d.toLong()))
            events.firstOrNull { it.event.end.isAfter(now) }?.let { return it }
        }
        return null
    }

    /** Shadow currently active at [now], if any. */
    fun current(now: Instant): ShadeSnapshot = engine.snapshot(system, now)

    fun month(month: YearMonth, sampleEveryDays: Int = 5): MonthShading {
        val samples = (1..month.lengthOfMonth() step sampleEveryDays).map { engine.day(system, month.atDay(it), zone, 10) }
        val scale = month.lengthOfMonth().toDouble() / samples.size
        return MonthShading(month, samples.sumOf { it.lossKwh } * scale, samples.sumOf { it.unshadedKwh } * scale, (samples.count { it.events.isNotEmpty() } * scale).toInt())
    }

    fun year(year: Int): List<MonthShading> = Month.entries.map { month(YearMonth.of(year, it), sampleEveryDays = 10) }

    private fun describe(e: ShadowEvent): ShadowForecast {
        val o = names[e.obstacleId]
        val hour = e.start.atZone(zone).hour + e.start.atZone(zone).minute / 60.0
        val part = when {
            hour < 10.5 -> DayPart.MORNING
            hour < 13.5 -> DayPart.MIDDAY
            hour < 17 -> DayPart.AFTERNOON
            else -> DayPart.EVENING
        }
        val name = when {
            e.obstacleId == HorizonBuilder.TERRAIN_ID -> "Ukształtowanie terenu"
            o == null -> e.obstacleId
            else -> o.name ?: o.type.label
        }
        val height = o?.height?.let { h -> h.meters?.let { "${fmt(it)} m (${h.source.label})" } ?: "nieznana" } ?: "—"
        return ShadowForecast(e, name, height, part)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
}

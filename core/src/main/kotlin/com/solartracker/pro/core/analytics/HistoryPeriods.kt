package com.solartracker.pro.core.analytics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class HistoryPeriod(val label: String) { DAY("Dzień"), WEEK("Tydzień"), MONTH("Miesiąc"), YEAR("Rok"), LIFETIME("Całość") }

/** Energy totals for one period, integrated from recorded samples (CALCULATED from measurements). */
data class PeriodTotals(
    val periodStart: LocalDate,
    val period: HistoryPeriod,
    val pvKwh: Double,
    val loadKwh: Double,
    val gridImportKwh: Double,
    val gridExportKwh: Double,
    val batteryChargeKwh: Double,
    val batteryDischargeKwh: Double,
    val peakPvW: Double?,
    val samples: Int,
    /** Share of recorded time vs. the period length (data completeness). */
    val coverage: Double,
) {
    /** Share of PV used on site; null without PV. */
    val selfConsumption: Double? get() = if (pvKwh > 0) ((pvKwh - gridExportKwh) / pvKwh).coerceIn(0.0, 1.0) else null
    /** Share of the load not taken from the grid; null without load. */
    val autarky: Double? get() = if (loadKwh > 0) (1 - gridImportKwh / loadKwh).coerceIn(0.0, 1.0) else null
}

object HistoryPeriods {
    fun startOf(date: LocalDate, period: HistoryPeriod): LocalDate = when (period) {
        HistoryPeriod.DAY -> date
        HistoryPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        HistoryPeriod.MONTH -> date.withDayOfMonth(1)
        HistoryPeriod.YEAR -> date.withDayOfYear(1)
        HistoryPeriod.LIFETIME -> LocalDate.of(2000, 1, 1)
    }

    private fun endOf(start: LocalDate, period: HistoryPeriod): LocalDate = when (period) {
        HistoryPeriod.DAY -> start.plusDays(1)
        HistoryPeriod.WEEK -> start.plusWeeks(1)
        HistoryPeriod.MONTH -> start.plusMonths(1)
        HistoryPeriod.YEAR -> start.plusYears(1)
        HistoryPeriod.LIFETIME -> start
    }

    /** Totals per period, oldest first. Coverage for LIFETIME is relative to first→last sample. */
    fun totals(samples: List<HistorySample>, period: HistoryPeriod, zone: ZoneId, now: Instant = Instant.now()): List<PeriodTotals> =
        samples.groupBy { startOf(it.start.atZone(zone).toLocalDate(), period) }.toSortedMap().map { (start, list) ->
            val recordedS = list.sumOf { it.duration.seconds }.toDouble()
            val periodS = if (period == HistoryPeriod.LIFETIME) {
                (list.maxOf { it.end }.epochSecond - list.minOf { it.start }.epochSecond).toDouble()
            } else {
                val from = start.atStartOfDay(zone).toInstant()
                val to = minOf(endOf(start, period).atStartOfDay(zone).toInstant(), maxOf(now, from.plusSeconds(1)))
                (to.epochSecond - from.epochSecond).toDouble()
            }
            PeriodTotals(
                start, period,
                list.sumOf { it.pvEnergyKwh }, list.sumOf { it.loadEnergyKwh },
                list.sumOf { it.gridImportKwh }, list.sumOf { it.gridExportKwh },
                list.sumOf { it.batteryChargeKwh }, list.sumOf { it.batteryDischargeKwh },
                list.mapNotNull { it.pvMaxW ?: it.pvW }.maxOrNull(), list.sumOf { it.samples },
                if (periodS > 0) (recordedS / periodS).coerceIn(0.0, 1.0) else 0.0,
            )
        }
}

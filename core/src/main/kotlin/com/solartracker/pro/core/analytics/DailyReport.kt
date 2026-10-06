package com.solartracker.pro.core.analytics

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Change of a metric against a reference (yesterday or the 7-day average). */
data class MetricDelta(val metric: String, val today: Double, val reference: Double) {
    /** Relative change [%]; null when the reference is ~0. */
    val percent: Double? get() = if (kotlin.math.abs(reference) < 0.05) null else (today - reference) / reference * 100
}

/** Automatic daily energy report built from recorded history (MEASURED / CALCULATED values). */
data class DailyEnergyReport(
    val date: LocalDate,
    val pvKwh: Double,
    val pvPeakW: Double?,
    val loadKwh: Double,
    val batteryChargedKwh: Double,
    val batteryDischargedKwh: Double,
    val gridImportKwh: Double,
    val gridExportKwh: Double,
    val socMin: Double?,
    val socMax: Double?,
    /** Share of the day covered by recorded data (0..1). */
    val coverage: Double,
    val forecastAccuracy: AccuracyReport?,
    val healthScore: Int?,
    val alerts: Int,
    val vsYesterday: List<MetricDelta>,
    val vs7DayAverage: List<MetricDelta>,
) {
    fun text(): String {
        fun f(v: Double, d: Int = 1) = String.format(Locale.ROOT, "%.${d}f", v)
        fun delta(list: List<MetricDelta>, name: String) = list.firstOrNull { it.metric == name }?.percent?.let { " (${if (it >= 0) "+" else ""}${f(it, 0)}%)" } ?: ""
        return buildString {
            appendLine("Raport dnia $date")
            appendLine("PV: ${f(pvKwh)} kWh${delta(vsYesterday, "PV")}" + (pvPeakW?.let { ", szczyt ${f(it / 1000, 2)} kW" } ?: ""))
            appendLine("Zużycie: ${f(loadKwh)} kWh${delta(vsYesterday, "Zużycie")}")
            appendLine("Bateria: +${f(batteryChargedKwh)} / −${f(batteryDischargedKwh)} kWh" + (if (socMin != null && socMax != null) ", SOC ${socMin.toInt()}–${socMax.toInt()}%" else ""))
            appendLine("Sieć: import ${f(gridImportKwh)} kWh, eksport ${f(gridExportKwh)} kWh")
            forecastAccuracy?.accuracyPercent?.let { appendLine("Trafność prognozy: ${f(it, 0)}%") }
            healthScore?.let { appendLine("Zdrowie instalacji: $it/100") }
            appendLine("Alerty: $alerts")
            if (coverage < 0.9) appendLine("Uwaga: dane z ${f(coverage * 100, 0)}% doby")
        }.trim()
    }
}

object DailyReportBuilder {
    /**
     * @param samples history rows covering at least [date] and the 7 days before (summary or 30 s rows)
     */
    fun build(
        date: LocalDate,
        samples: List<HistorySample>,
        zone: ZoneId,
        forecastAccuracy: AccuracyReport? = null,
        healthScore: Int? = null,
        alerts: Int = 0,
    ): DailyEnergyReport? {
        val byDay = samples.groupBy { it.start.atZone(zone).toLocalDate() }
        val today = byDay[date] ?: return null
        val totals = HistoryPeriods.totals(today, HistoryPeriod.DAY, zone, date.plusDays(1).atStartOfDay(zone).toInstant()).single()
        fun metrics(rows: List<HistorySample>) = mapOf(
            "PV" to rows.sumOf { it.pvEnergyKwh }, "Zużycie" to rows.sumOf { it.loadEnergyKwh },
            "Import" to rows.sumOf { it.gridImportKwh }, "Eksport" to rows.sumOf { it.gridExportKwh },
        )
        val now = metrics(today)
        val yesterday = byDay[date.minusDays(1)]?.let { metrics(it) }
        val week = (1..7).mapNotNull { byDay[date.minusDays(it.toLong())]?.let(::metrics) }
        val weekAvg = if (week.isEmpty()) null else now.keys.associateWith { k -> week.map { it.getValue(k) }.average() }
        val socs = today.mapNotNull { it.socPercent }
        return DailyEnergyReport(
            date, totals.pvKwh, totals.peakPvW, totals.loadKwh, totals.batteryChargeKwh, totals.batteryDischargeKwh,
            totals.gridImportKwh, totals.gridExportKwh, socs.minOrNull(), socs.maxOrNull(), totals.coverage,
            forecastAccuracy, healthScore, alerts,
            yesterday?.let { y -> now.map { (k, v) -> MetricDelta(k, v, y.getValue(k)) } }.orEmpty(),
            weekAvg?.let { a -> now.map { (k, v) -> MetricDelta(k, v, a.getValue(k)) } }.orEmpty(),
        )
    }
}

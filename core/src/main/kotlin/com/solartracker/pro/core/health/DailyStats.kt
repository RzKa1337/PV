package com.solartracker.pro.core.health

import com.solartracker.pro.core.analytics.HistorySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day of installation behaviour, derived from MEASURED history rows and the model's expectation
 * for the same rows. All fields are null when the day has no data for them.
 */
data class DayStats(
    val date: LocalDate,
    val realKwh: Double,
    /** Model expectation (with weather and shading) for the intervals that have data. */
    val expectedKwh: Double,
    /** Hours of history coverage between sunrise and sunset. */
    val coverageHours: Double,
    /** Median PV voltage while PV power > 30% of peak (string health indicator). */
    val pvVoltageAtPower: Double?,
    /** Median inverter temperature while output > 50% of rated. */
    val inverterTempAtLoadC: Double?,
    /** Median load 01:00–05:00 [kW]. */
    val nightLoadKw: Double?,
    val faultCodes: Map<Int, Int>,
    val warningCodes: Map<Int, Int>,
) {
    val ratio: Double? get() = if (expectedKwh > 0.2) realKwh / expectedKwh else null
}

/** Extra per-row values the history table does not store (PV voltage is optional). */
fun interface ExpectedPower {
    /** Expected AC power [W] from the model at [time] (weather + shading, without calibration). */
    fun expectedW(time: Instant): Double
}

object DayStatsBuilder {
    fun build(
        rows: List<HistorySample>,
        zone: ZoneId,
        expected: ExpectedPower,
        peakPowerW: Double,
        inverterRatedW: Double,
        pvVoltage: (HistorySample) -> Double? = { null },
    ): List<DayStats> = rows.groupBy { it.start.atZone(zone).toLocalDate() }.toSortedMap().map { (date, list) ->
        val producing = list.filter { (it.pvW ?: 0.0) > 0.0 || expected.expectedW(it.start.plus(it.duration.dividedBy(2))) > 0.0 }
        val real = list.sumOf { it.pvEnergyKwh }
        val exp = list.sumOf { r -> expected.expectedW(r.start.plus(r.duration.dividedBy(2))) / 1000.0 * r.duration.seconds / 3600.0 }
        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        DayStats(
            date = date,
            realKwh = real,
            expectedKwh = exp,
            coverageHours = producing.sumOf { it.duration.seconds } / 3600.0,
            pvVoltageAtPower = median(list.filter { (it.pvW ?: 0.0) > 0.3 * peakPowerW }.mapNotNull(pvVoltage)),
            inverterTempAtLoadC = median(list.filter { (it.loadW ?: 0.0) > 0.5 * inverterRatedW }.mapNotNull { it.inverterTemperatureC }),
            nightLoadKw = median(list.filter { it.start.atZone(zone).hour in 1..4 }.mapNotNull { it.loadW?.div(1000.0) }),
            faultCodes = list.flatMap { it.faultCodes }.groupingBy { it }.eachCount(),
            warningCodes = list.flatMap { it.warningCodes }.groupingBy { it }.eachCount(),
        )
    }
}

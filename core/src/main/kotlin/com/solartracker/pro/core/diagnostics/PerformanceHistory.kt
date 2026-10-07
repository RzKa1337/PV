package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.CalibrationObservation
import com.solartracker.pro.core.analytics.SkyCondition
import com.solartracker.pro.core.analytics.Stats
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Builds the inputs of [SoilingDetector] and [DegradationAnalyzer] from the stored model-vs-measurement
 * observations (the same ones AutoCalibration uses). Only clean clear-sky samples count: fresh link, no fault, no
 * rejected telemetry, not near the inverter limit, sun ≥ 15°, little modelled shading.
 */
object PerformanceHistory {
    const val MIN_ELEVATION = 15.0
    const val CLEAR_INDEX = 0.85
    const val MIN_SHADING_FACTOR = 0.8
    /** Observations are stored about once a minute. */
    const val SAMPLE_HOURS = 1.0 / 60.0

    fun isClearClean(o: CalibrationObservation): Boolean =
        o.linkOk && !o.fault && !o.invalidTelemetry && !o.nearLimit && o.modelKw > 0.05 &&
            (o.sunElevationDeg ?: 0.0) >= MIN_ELEVATION && (o.shadingFactor ?: 1.0) >= MIN_SHADING_FACTOR &&
            ((o.clearSkyIndex ?: 0.0) >= CLEAR_INDEX || o.condition == SkyCondition.CLEAR)

    /**
     * One [DailyPerformance] per local day: PI = Σ real / Σ model over the clear clean samples.
     * [rainMmByDate] comes from the weather data; days without it keep rain = null (unknown, never 0).
     */
    fun daily(observations: List<CalibrationObservation>, zone: ZoneId, rainMmByDate: Map<LocalDate, Double> = emptyMap(),
              snowDates: Set<LocalDate> = emptySet()): List<DailyPerformance> =
        observations.groupBy { it.time.atZone(zone).toLocalDate() }.toSortedMap().map { (date, obs) ->
            val clear = obs.filter(::isClearClean)
            val model = clear.sumOf { it.modelKw }
            DailyPerformance(
                date = date,
                performanceIndex = if (model > 0) clear.sumOf { it.realKw } / model else null,
                clearSkyHours = clear.size * SAMPLE_HOURS,
                rainMm = rainMmByDate[date],
                snow = date in snowDates,
            )
        }

    /** Monthly PI = P90 of the month's clear days (temporary soiling does not pull it down). */
    fun monthly(days: List<DailyPerformance>): List<MonthlyPerformance> =
        days.filter { it.performanceIndex != null && it.clearSkyHours >= SoilingDetector.MIN_CLEAR_HOURS && !it.snow }
            .groupBy { YearMonth.from(it.date) }.toSortedMap()
            .map { (m, d) -> MonthlyPerformance(m, Stats.percentile(d.map { it.performanceIndex!! }, 90.0), d.size) }
}

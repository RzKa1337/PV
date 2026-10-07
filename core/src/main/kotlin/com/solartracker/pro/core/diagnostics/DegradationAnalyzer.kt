package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.Stats
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * Monthly clear-sky performance index (measured / model with temperature, AOI, shading and snow), taken
 * as a high percentile of the month's clear days so that temporary soiling does not pull it down.
 */
data class MonthlyPerformance(val month: YearMonth, val performanceIndex: Double?, val clearDays: Int)

enum class DegradationTrend(val label: String) {
    INSUFFICIENT_DATA("Za krótka historia"),
    STABLE("Stabilna"),
    NORMAL("Typowa degradacja"),
    ELEVATED("Podwyższona degradacja"),
}

data class CapacityProjection(val year: Int, val capacityKwp: Double)

data class DegradationAssessment(
    /** Estimated yearly power loss [%/year] (positive = losing power); null without enough data. */
    val ratePercentPerYear: Double?,
    val confidence: Double,
    val trend: DegradationTrend,
    val pairs: Int,
    val spanMonths: Long,
    val projection: List<CapacityProjection>,
    val evidence: List<String>,
)

/**
 * Long-term degradation with the year-over-year (YoY) method: each month is compared with the same month a
 * year later, so seasonality (sun height, temperature, snow) cancels out; the median of the yearly ratios is
 * robust to single soiled or shaded months. Needs at least two years of data.
 */
object DegradationAnalyzer {
    const val MIN_SPAN_MONTHS = 24L
    const val MIN_PAIRS = 6
    const val MIN_CLEAR_DAYS = 4

    fun assess(
        months: List<MonthlyPerformance>,
        nameplateKwp: Double,
        commissioningYear: Int?,
        projectYears: List<Int> = emptyList(),
    ): DegradationAssessment {
        val usable = months.filter { it.performanceIndex != null && it.performanceIndex > 0 && it.clearDays >= MIN_CLEAR_DAYS }
            .associateBy { it.month }
        val span = if (usable.isEmpty()) 0L else ChronoUnit.MONTHS.between(usable.keys.min(), usable.keys.max()) + 1
        val rates = usable.mapNotNull { (m, a) -> usable[m.plusYears(1)]?.let { b -> b.performanceIndex!! / a.performanceIndex!! - 1.0 } }
        if (span < MIN_SPAN_MONTHS || rates.size < MIN_PAIRS) {
            return DegradationAssessment(null, 0.0, DegradationTrend.INSUFFICIENT_DATA, rates.size, span, emptyList(),
                listOf("Potrzeba ≥ $MIN_SPAN_MONTHS miesięcy i ≥ $MIN_PAIRS par rok-do-roku (jest $span mies., ${rates.size} par)"))
        }
        val rate = -Stats.median(rates)!! * 100
        val spread = (Stats.iqr(rates) ?: 0.0) * 100
        val confidence = ((rates.size / 12.0).coerceAtMost(1.0) * (1.0 - (spread / 6.0)).coerceIn(0.1, 1.0)).coerceIn(0.05, 0.95)
        val trend = when {
            rate < 0.3 -> DegradationTrend.STABLE
            rate <= 0.8 -> DegradationTrend.NORMAL
            else -> DegradationTrend.ELEVATED
        }
        val baseYear = commissioningYear ?: usable.keys.min().year
        val d = (rate / 100).coerceAtLeast(0.0)
        val projection = projectYears.distinct().sorted().filter { it >= baseYear }
            .map { CapacityProjection(it, nameplateKwp * (1 - d).pow(it - baseYear)) }
        return DegradationAssessment(rate, confidence, trend, rates.size, span, projection, listOf(
            "Par rok-do-roku: ${rates.size}, historia: $span mies.",
            "Mediana zmian r/r: ${"%.2f".format(-rate)}%/rok, rozrzut (IQR): ${"%.2f".format(spread)} p.p.",
            "Sezonowość wyeliminowana przez porównanie tych samych miesięcy; zabrudzenie ograniczone percentylem dni pogodnych",
        ))
    }
}

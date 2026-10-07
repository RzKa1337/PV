package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.Stats
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One day of performance used for soiling/degradation analysis.
 *
 * @property performanceIndex measured / modelled energy over the day's clear-sky hours (the model already
 *   contains temperature, AOI, shading and snow, so those do not show up here); null = not computable
 * @property clearSkyHours hours with clear sky (clear-sky index ≥ 0.85) and fresh measurements
 * @property rainMm daily precipitation [mm] from the weather data; null = unknown
 * @property snow snow on the panels expected on that day (day excluded)
 * @property shadingChanged the shading model changed (new obstacle) – comparisons across it are invalid
 */
data class DailyPerformance(
    val date: LocalDate,
    val performanceIndex: Double?,
    val clearSkyHours: Double,
    val rainMm: Double?,
    val snow: Boolean = false,
    val shadingChanged: Boolean = false,
)

enum class SoilingState(val label: String) {
    INSUFFICIENT_DATA("Za mało pogodnych dni do oceny"),
    NONE("Brak oznak zabrudzenia"),
    SUSPECTED("Podejrzenie zabrudzenia"),
    CONFIRMED("Zabrudzenie potwierdzone (poprawa po deszczu)"),
}

data class RainRecovery(val rainDate: LocalDate, val rainMm: Double, val deficitBeforePercent: Double, val deficitAfterPercent: Double) {
    val recoveryPercentPoints: Double get() = deficitBeforePercent - deficitAfterPercent
}

data class SoilingAssessment(
    val state: SoilingState,
    /** Current estimated soiling loss [% of output]; null when not determined. */
    val lossPercent: Double?,
    /** Loss growth since the last cleaning rain [percentage points / day]; null when not determined. */
    val ratePerDay: Double?,
    val confidence: Double,
    val evidence: List<String>,
    val recovery: RainRecovery?,
    val usableDays: Int,
)

/**
 * Soiling detection without a soiling sensor. Uses only clear-sky days (clouds make the modelled irradiance
 * unreliable), compares them with the clean reference level (high percentile, or the level right after a
 * cleaning rain) and checks the natural experiment: did performance recover after rain?
 */
object SoilingDetector {
    /** Rain that is expected to wash dust off (literature: ~1–5 mm; conservative choice). */
    const val CLEANING_RAIN_MM = 2.0
    const val MIN_CLEAR_HOURS = 3.0
    const val MIN_USABLE_DAYS = 5
    /** Deficit (vs clean reference) that starts to count as soiling [%]. */
    const val SUSPECT_DEFICIT_PERCENT = 4.0
    /** Recovery after rain needed to confirm soiling [percentage points]. */
    const val CONFIRM_RECOVERY_PP = 3.0
    const val CONSECUTIVE_DAYS = 3

    fun assess(days: List<DailyPerformance>, windowDays: Long = 60): SoilingAssessment {
        val sorted = days.sortedBy { it.date }
        val lastDate = sorted.lastOrNull()?.date ?: return insufficient(0)
        // A shading change breaks comparability: only use days after the latest change.
        val sinceChange = sorted.lastOrNull { it.shadingChanged }?.let { c -> sorted.filter { it.date >= c.date } } ?: sorted
        val window = sinceChange.filter { ChronoUnit.DAYS.between(it.date, lastDate) < windowDays }
        val usable = window.filter { it.performanceIndex != null && it.clearSkyHours >= MIN_CLEAR_HOURS && !it.snow && it.performanceIndex > 0 }
        if (usable.size < MIN_USABLE_DAYS) return insufficient(usable.size)

        val rains = window.filter { (it.rainMm ?: 0.0) >= CLEANING_RAIN_MM }
        val reference = Stats.percentile(usable.map { it.performanceIndex!! }, 90.0)!!
        fun deficit(pi: Double) = ((1 - pi / reference) * 100).coerceAtLeast(0.0)

        val recent = usable.takeLast(CONSECUTIVE_DAYS + 2)
        val recentDeficit = deficit(Stats.median(recent.map { it.performanceIndex!! })!!)
        val consecutive = usable.reversed().takeWhile { deficit(it.performanceIndex!!) >= SUSPECT_DEFICIT_PERCENT }.size

        // Natural experiment: the most recent cleaning rain with usable clear days before and after it.
        val recovery = rains.reversed().firstNotNullOfOrNull { rain ->
            // State right before the rain = the last few clear days (soiling grows, an older median would understate it).
            val before = usable.filter { it.date < rain.date && ChronoUnit.DAYS.between(it.date, rain.date) <= 10 }.takeLast(3)
            val after = usable.filter { it.date > rain.date && ChronoUnit.DAYS.between(rain.date, it.date) <= 5 }.take(3)
            if (before.size >= 2 && after.isNotEmpty()) {
                RainRecovery(rain.date, rain.rainMm ?: 0.0,
                    deficit(Stats.median(before.map { it.performanceIndex!! })!!), deficit(Stats.median(after.map { it.performanceIndex!! })!!))
            } else null
        }

        // Trend since the last cleaning rain (soiling builds up gradually; a step change suggests something else).
        val lastRain = rains.lastOrNull()?.date
        val sinceRain = usable.filter { lastRain == null || it.date > lastRain }
        val rate = if (sinceRain.size >= 3) Stats.slope(sinceRain.map { ChronoUnit.DAYS.between(sinceRain.first().date, it.date).toDouble() },
            sinceRain.map { deficit(it.performanceIndex!!) }) else null

        val evidence = mutableListOf(
            "Dni pogodne użyte w analizie: ${usable.size}",
            "Poziom odniesienia (czyste panele, P90): ${"%.3f".format(reference)}",
            "Ostatnie pogodne dni: ${"%.1f".format(recentDeficit)}% poniżej poziomu odniesienia",
        )
        if (consecutive > 0) evidence += "$consecutive kolejnych pogodnych dni z deficytem ≥ ${SUSPECT_DEFICIT_PERCENT.toInt()}%"
        rate?.let { evidence += "Narastanie od ostatniego deszczu: ${"%.2f".format(it)} p.p./dzień" }
        recovery?.let {
            evidence += "Przed deszczem (${it.rainDate}, ${"%.1f".format(it.rainMm)} mm): −${"%.1f".format(it.deficitBeforePercent)}%"
            evidence += "Po deszczu: −${"%.1f".format(it.deficitAfterPercent)}%"
        }

        val confirmedByRain = recovery != null && recovery.recoveryPercentPoints >= CONFIRM_RECOVERY_PP &&
            recovery.deficitBeforePercent >= SUSPECT_DEFICIT_PERCENT
        val suspected = recentDeficit >= SUSPECT_DEFICIT_PERCENT && consecutive >= CONSECUTIVE_DAYS
        val dataFactor = (usable.size / 15.0).coerceAtMost(1.0)
        return when {
            confirmedByRain && suspected -> SoilingAssessment(SoilingState.CONFIRMED, recentDeficit, rate,
                (0.6 + 0.35 * dataFactor).coerceAtMost(0.95), evidence, recovery, usable.size)
            confirmedByRain -> SoilingAssessment(SoilingState.CONFIRMED, recovery!!.deficitAfterPercent.takeIf { recentDeficit < SUSPECT_DEFICIT_PERCENT } ?: recentDeficit,
                rate, (0.5 + 0.3 * dataFactor), evidence + "Deszcz poprawił wydajność – panele były zabrudzone", recovery, usable.size)
            suspected -> SoilingAssessment(SoilingState.SUSPECTED, recentDeficit, rate,
                (0.35 + 0.3 * dataFactor + if ((rate ?: 0.0) > 0.02) 0.1 else 0.0).coerceAtMost(0.8), evidence, recovery, usable.size)
            else -> SoilingAssessment(SoilingState.NONE, recentDeficit, rate, (0.4 + 0.4 * dataFactor), evidence, recovery, usable.size)
        }
    }

    private fun insufficient(n: Int) = SoilingAssessment(SoilingState.INSUFFICIENT_DATA, null, null, 0.0,
        listOf("Potrzeba co najmniej $MIN_USABLE_DAYS pogodnych dni z pomiarami (jest $n)"), null, n)
}

package com.solartracker.pro.core.health

import com.solartracker.pro.core.pv.PvLossBreakdown
import com.solartracker.pro.core.quality.DataKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One reason why the score is below 100, with the evidence used. */
data class Deduction(val points: Int, val category: String, val reason: String, val evidence: String)

data class HealthReport(
    /** 0..100, or null when there is not enough data to score honestly. */
    val score: Int?,
    val deductions: List<Deduction>,
    val confidence: Double,
    val kind: DataKind,
    /** Things that could not be assessed (missing data). */
    val unknowns: List<String>,
) {
    /** "WHY IS MY PV SCORE LOWER?" */
    fun explanation(): String = when {
        score == null -> "Za mało danych do oceny: ${unknowns.joinToString()}"
        deductions.isEmpty() -> "Instalacja pracuje zgodnie z modelem ($score/100)."
        else -> "$score/100: " + deductions.joinToString(", ") { "−${it.points} ${it.category}" }
    }
}

/** Inputs for a health assessment over a recent period (typically 7 days). */
data class HealthInput(
    val days: List<DayStats>,
    /** Model loss breakdown over the same period (energy-weighted), for temperature/shading/clipping. */
    val modelLosses: PvLossBreakdown?,
    /** Confidence of the shading model (0..1); shading deductions need ≥ 0.4. */
    val shadingConfidence: Double?,
    /** Measured inverter efficiency (AC out / DC in), if the inverter reports both. */
    val inverterEfficiency: Double?,
    /** Link quality 0..1 over the period. */
    val linkQuality: Double?,
    val activeFaults: Int,
    val activeWarnings: Int,
    /** Estimated battery capacity / nominal capacity, if measured. */
    val batteryCapacityRatio: Double?,
)

/**
 * PV Health Score 0–100. Points are deducted only for effects backed by data; what cannot be assessed
 * is listed as unknown instead of guessed. Weather (clouds) is not a health problem: expectations
 * already use the weather, so only the remaining gap is "unexplained".
 */
object PvHealthEngine {
    const val MIN_COVERAGE_HOURS = 12.0
    const val MIN_DAYS = 2

    fun assess(i: HealthInput): HealthReport {
        val unknowns = mutableListOf<String>()
        val d = mutableListOf<Deduction>()
        val usable = i.days.filter { it.ratio != null }
        val coverage = usable.sumOf { it.coverageHours }
        if (usable.size < MIN_DAYS || coverage < MIN_COVERAGE_HOURS) {
            unknowns += "pomiary produkcji (dni: ${usable.size}/$MIN_DAYS, godziny: ${coverage.toInt()}/${MIN_COVERAGE_HOURS.toInt()})"
            return HealthReport(null, emptyList(), 0.0, DataKind.UNKNOWN, unknowns + otherUnknowns(i))
        }
        val real = usable.sumOf { it.realKwh }
        val expected = usable.sumOf { it.expectedKwh }

        val m = i.modelLosses
        if (m != null && m.idealDcW > 0) {
            val ideal = m.idealDcW + m.bifacialGainW
            val shadingShare = m.shadingLossW / ideal
            if (i.shadingConfidence == null || i.shadingConfidence < 0.4) unknowns += "zacienienie (model o niskiej pewności)"
            else if (shadingShare >= 0.005) d += Deduction(pts(shadingShare * 100, 25), "shading", "Zacienienie obniża uzysk o ${pct(shadingShare)}", "model zacienienia, pewność ${(i.shadingConfidence * 100).toInt()}%")
            val tempShare = m.temperatureLossW / ideal
            if (tempShare >= 0.005) d += Deduction(pts(tempShare * 100, 10), "temperature", "Wysoka temperatura ogniw: −${pct(tempShare)}", "model temperatury z danych pogodowych")
            val clipShare = m.clippingLossW / ideal
            if (clipShare >= 0.005) d += Deduction(pts(clipShare * 100, 10), "clipping", "Ograniczenie mocy falownika: −${pct(clipShare)}", "model: moc DC powyżej limitu falownika")
        } else unknowns += "rozkład strat modelu"

        i.inverterEfficiency?.let { eff ->
            if (eff < 0.95) d += Deduction(pts((0.96 - eff) * 100, 10), "inverter efficiency", "Sprawność falownika ${pct(eff)} (typowo ≥ 95%)", "pomiar: moc AC / moc DC")
        } ?: unknowns.add("sprawność falownika (brak pomiaru DC i AC)")

        // Gap between measurement and the full model (which already contains weather, shading, temperature).
        val gap = 1 - real / expected
        if (gap > 0.05) d += Deduction(pts((gap - 0.05) * 100, 30), "unexplained underproduction",
            "Produkcja ${pct(gap)} poniżej modelu bez wyjaśnienia", "${"%.1f".format(real)} kWh zmierzone vs ${"%.1f".format(expected)} kWh oczekiwane, ${usable.size} dni")

        if (i.activeFaults > 0) d += Deduction(min(30, 15 * i.activeFaults), "faults", "Aktywne błędy falownika: ${i.activeFaults}", "kody błędów z falownika")
        if (i.activeWarnings > 0) d += Deduction(min(10, 3 * i.activeWarnings), "warnings", "Aktywne ostrzeżenia: ${i.activeWarnings}", "kody ostrzeżeń z falownika")
        i.linkQuality?.let { if (it < 0.9) d += Deduction(pts((0.9 - it) * 50, 10), "communication", "Niestabilna komunikacja (${pct(it)} udanych odczytów)", "statystyka połączenia") }
            ?: unknowns.add("jakość komunikacji")
        i.batteryCapacityRatio?.let { if (it < 0.8) d += Deduction(pts((0.8 - it) * 50, 10), "battery", "Szacowana pojemność baterii ${pct(it)} nominalnej", "bilans energii i zmian SOC") }

        val kept = d.filter { it.points > 0 }.sortedByDescending { it.points }
        val score = (100 - kept.sumOf { it.points }).coerceIn(0, 100)
        val confidence = (min(1.0, coverage / 40.0) * (if (unknowns.isEmpty()) 1.0 else 0.85)).coerceIn(0.0, 1.0)
        return HealthReport(score, kept, confidence, DataKind.CALCULATED, unknowns)
    }

    private fun otherUnknowns(i: HealthInput) = buildList {
        if (i.inverterEfficiency == null) add("sprawność falownika")
        if (i.linkQuality == null) add("jakość komunikacji")
    }

    private fun pts(value: Double, cap: Int) = max(0, min(cap, value.roundToInt()))
    private fun pct(f: Double) = "${"%.1f".format(f * 100)}%"
}

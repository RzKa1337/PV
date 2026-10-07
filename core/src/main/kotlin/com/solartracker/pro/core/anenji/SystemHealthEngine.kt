package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.diagnostics.DegradationAssessment
import com.solartracker.pro.core.diagnostics.MpptAnalysis
import com.solartracker.pro.core.diagnostics.SoilingAssessment
import com.solartracker.pro.core.diagnostics.SoilingState
import kotlin.math.roundToInt

/** Health categories with their weight in the overall score. */
enum class HealthCategory(val label: String, val weight: Int) {
    PV("PV", 20),
    BATTERY("Bateria", 20),
    INVERTER("Falownik", 15),
    MPPT("MPPT", 5),
    COMMUNICATION("Komunikacja", 10),
    CONFIGURATION("Konfiguracja", 10),
    FORECAST("Prognoza", 10),
    DATA_QUALITY("Jakość danych", 10),
}

data class Deduction(val reason: String, val points: Int)

/** Score = 100 − Σ deductions (or a measured percentage, see [basis]); null = not assessable (N/A), never a default. */
data class CategoryScore(val category: HealthCategory, val score: Int?, val deductions: List<Deduction>, val basis: String, val confidence: Double)

data class SystemHealth(val overall: Int?, val categories: List<CategoryScore>, val explanation: String) {
    fun of(c: HealthCategory): CategoryScore = categories.first { it.category == c }
}

/** Everything the score is computed from; each field optional. */
data class HealthEvidence(
    /** Median daily measured / modelled PV energy over clean days (1.0 = as modelled). */
    val pvPerformanceRatio: Double? = null,
    val soiling: SoilingAssessment? = null,
    val degradation: DegradationAssessment? = null,
    val events: List<AnenjiEvent> = emptyList(),
    val days: Int = 0,
    val findings: List<Finding> = emptyList(),
    val communication: CommunicationReport? = null,
    val mppt: MpptAnalysis? = null,
    val forecastAccuracyPercent: Double? = null,
    val dataQuality: DataQualityReport? = null,
    val hasBatteryData: Boolean = false,
    val hasPvData: Boolean = false,
    val hasInverterData: Boolean = false,
    val settingsKnown: Boolean = false,
    val minSoc: Double? = null,
    val maxInverterTempC: Double? = null,
    val conversionEfficiency: Double? = null,
)

/**
 * "SystemHealthEngine": explainable 0–100 scores per category and a weighted overall score over the categories that
 * could be assessed. Every point lost is listed with its reason.
 */
object SystemHealthEngine {
    fun assess(e: HealthEvidence): SystemHealth {
        val perDay = { n: Int -> if (e.days > 0) n.toDouble() / e.days else n.toDouble() }
        fun count(pred: (AnenjiEvent) -> Boolean) = e.events.count(pred)
        val cats = mutableListOf<CategoryScore>()

        cats += if (!e.hasPvData) na(HealthCategory.PV, "brak danych PV") else {
            val d = mutableListOf<Deduction>()
            e.pvPerformanceRatio?.let { r -> if (r < 0.95) d += Deduction("PV ${((1 - r) * 100).roundToInt()}% poniżej modelu w dni pogodne", ((0.95 - r) * 100).roundToInt().coerceAtMost(40)) }
            e.soiling?.takeIf { it.state == SoilingState.SUSPECTED || it.state == SoilingState.CONFIRMED }?.lossPercent?.let { d += Deduction("Zabrudzenie ~${it.roundToInt()}%", it.roundToInt().coerceAtMost(20)) }
            e.degradation?.ratePercentPerYear?.takeIf { it > 0.8 }?.let { d += Deduction("Degradacja ${"%.2f".format(it)}%/rok", 5) }
            val pvEvents = count { it.category == EventCategory.PV && it.severity != EventSeverity.INFO }
            if (pvEvents > 0) d += Deduction("Ostrzeżenia PV: $pvEvents", minOf(15, (perDay(pvEvents) * 10).roundToInt().coerceAtLeast(1)))
            e.findings.firstOrNull { it.title.startsWith("PV osiąga limit") }?.let { d += Deduction("Clipping (limit falownika)", 3) }
            score(HealthCategory.PV, d, if (e.pvPerformanceRatio != null) "porównanie z modelem + zdarzenia" else "tylko zdarzenia (brak modelu)",
                if (e.pvPerformanceRatio != null) 0.75 else 0.45)
        }

        cats += if (!e.hasBatteryData) na(HealthCategory.BATTERY, "brak danych baterii") else {
            val d = mutableListOf<Deduction>()
            val low = count { it.category == EventCategory.BATTERY }
            if (low > 0) d += Deduction("Zdarzenia baterii: $low", minOf(30, (perDay(low) * 15).roundToInt().coerceAtLeast(2)))
            e.minSoc?.let { if (it < 10) d += Deduction("SOC spadało do ${it.roundToInt()}%", 15) else if (it < 20) d += Deduction("SOC spadało do ${it.roundToInt()}%", 7) }
            e.findings.filter { it.title.contains("pojemności", ignoreCase = true) && it.severity != FindingSeverity.INFO }.forEach { d += Deduction(it.title, 10) }
            e.findings.filter { it.title.contains("pełnego naładowania") }.forEach { d += Deduction(it.title, 5) }
            score(HealthCategory.BATTERY, d, "zdarzenia, SOC, pojemność z liczenia ładunku", 0.6)
        }

        cats += if (!e.hasInverterData) na(HealthCategory.INVERTER, "brak danych falownika") else {
            val d = mutableListOf<Deduction>()
            val faults = count { it.severity == EventSeverity.CRITICAL && it.category != EventCategory.COMMUNICATION }
            if (faults > 0) d += Deduction("Awarie: $faults", minOf(40, faults * 10))
            val temp = count { it.category == EventCategory.TEMPERATURE }
            if (temp > 0) d += Deduction("Ostrzeżenia temperaturowe: $temp", minOf(15, temp * 3))
            val restarts = count { it.description.startsWith("Restart") }
            if (restarts > 0) d += Deduction("Restarty: $restarts", minOf(15, restarts * 5))
            e.maxInverterTempC?.let { if (it > 75) d += Deduction("Temperatura falownika do ${it.roundToInt()} °C", 10) }
            e.conversionEfficiency?.let { if (it < 0.8) d += Deduction("Sprawność konwersji ~${(it * 100).roundToInt()}%", 10) }
            score(HealthCategory.INVERTER, d, "awarie, temperatury, restarty, sprawność", 0.65)
        }

        cats += e.mppt?.takeIf { it.available }?.let { m ->
            val d = mutableListOf<Deduction>()
            if (m.abnormal.isNotEmpty()) d += Deduction("Nieprawidłowe MPPT: ${m.abnormal.joinToString()}", 25 * m.abnormal.size)
            if (m.stringMismatch) d += Deduction("Rozrzut napięć stringów", 10)
            score(HealthCategory.MPPT, d, "porównanie MPPT", m.confidence)
        } ?: na(HealthCategory.MPPT, "falownik nie raportuje MPPT")

        cats += e.communication?.score?.let { s ->
            CategoryScore(HealthCategory.COMMUNICATION, s, e.communication.penalties.map { Deduction(it.first, it.second) }, "odczyty, luki, przerwy", e.communication.confidence)
        } ?: na(HealthCategory.COMMUNICATION, "brak danych o komunikacji")

        cats += if (!e.settingsKnown && e.findings.none { it.title.contains("napięc") || it.title.contains("pojemności") || it.title.contains("limicie") }) {
            na(HealthCategory.CONFIGURATION, "ustawienia falownika niedostępne (brak zweryfikowanych rejestrów i importu)")
        } else {
            val d = e.findings.filter { it.severity != FindingSeverity.INFO || it.title.startsWith("Potencjalnie") }
                .map { Deduction(it.title, when (it.severity) { FindingSeverity.CRITICAL -> 25; FindingSeverity.WARNING -> 10; FindingSeverity.INFO -> 3 }) }
            score(HealthCategory.CONFIGURATION, d, "doradca konfiguracji (tylko odczyt)", 0.55)
        }

        cats += e.forecastAccuracyPercent?.let { a ->
            CategoryScore(HealthCategory.FORECAST, a.roundToInt().coerceIn(0, 100), emptyList(), "trafność prognozy dnia następnego (100 − ważony MAPE)", 0.8)
        } ?: na(HealthCategory.FORECAST, "za mało par prognoza–pomiar")

        cats += e.dataQuality?.let { q ->
            val d = mutableListOf<Deduction>()
            if (q.coverage < 0.98) d += Deduction("Pokrycie danymi ${(q.coverage * 100).roundToInt()}%", ((1 - q.coverage) * 50).roundToInt().coerceAtLeast(1))
            if (q.invalidShare > 0) d += Deduction("Odrzucone wartości ${"%.1f".format(q.invalidShare * 100)}%", (q.invalidShare * 200).roundToInt().coerceIn(1, 30))
            if (q.missingChannels.isNotEmpty()) d += Deduction("Brak kanałów: ${q.missingChannels.size}", minOf(10, q.missingChannels.size))
            score(HealthCategory.DATA_QUALITY, d, "pokrycie, walidacja, kompletność", 0.85)
        } ?: na(HealthCategory.DATA_QUALITY, "brak danych")

        val assessed = cats.filter { it.score != null }
        val weight = assessed.sumOf { it.category.weight }
        val overall = if (weight == 0) null else (assessed.sumOf { it.score!! * it.category.weight }.toDouble() / weight).roundToInt()
        return SystemHealth(overall, cats,
            if (overall == null) "Brak danych do oceny" else "Średnia ważona ${assessed.size} ocenionych kategorii (wagi: " +
                assessed.joinToString { "${it.category.label} ${it.category.weight}" } + "); kategorie N/A nie wpływają na wynik")
    }

    private fun score(c: HealthCategory, d: List<Deduction>, basis: String, confidence: Double) =
        CategoryScore(c, (100 - d.sumOf { it.points }).coerceIn(0, 100), d, basis, confidence)

    private fun na(c: HealthCategory, why: String) = CategoryScore(c, null, emptyList(), why, 0.0)
}

package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.IssueType
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.TelemetryIssue
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class DiagnosisType(val label: String) {
    NORMAL("Instalacja pracuje prawidłowo"),
    CURTAILMENT_BATTERY_FULL("Produkcja ograniczona – bateria pełna"),
    SOILING_SUSPECTED("Podejrzenie zabrudzenia paneli"),
    SNOW_SUSPECTED("Podejrzenie śniegu na panelach"),
    SHADING_SUSPECTED("Podejrzenie niemodelowanego zacienienia"),
    TEMPERATURE_LOSS("Duża strata temperaturowa"),
    MPPT_ANOMALY("Anomalia MPPT"),
    INVERTER_EFFICIENCY_ANOMALY("Niska sprawność konwersji"),
    STRING_MISMATCH("Niedopasowanie stringów"),
    SENSOR_ANOMALY("Anomalia odczytu / czujnika"),
    COMMUNICATION_PROBLEM("Problem z komunikacją"),
    PV_DEGRADATION("Degradacja modułów"),
    UNEXPECTED_LOW_OUTPUT("Nieoczekiwanie niska produkcja"),
    UNEXPECTED_HIGH_OUTPUT("Nieoczekiwanie wysoka produkcja"),
}

enum class DiagnosisSeverity { INFO, WARNING, CRITICAL }

enum class PvHealthStatus(val label: String) { OK("SYSTEM OK"), ATTENTION("UWAGA"), PROBLEM("PROBLEM"), UNKNOWN("BRAK OCENY") }

data class Diagnosis(
    val type: DiagnosisType,
    val severity: DiagnosisSeverity,
    val confidence: Double,
    val evidence: List<String>,
    /** Estimated power impact [W] (positive = power lost); null when not quantifiable. */
    val impactW: Double?,
    val recommendation: String,
)

/** One past comparison (fresh measurement vs model) used for "sustained" and time-of-day patterns. */
data class RealitySample(
    val time: Instant,
    val expectedW: Double,
    val actualW: Double,
    val clearSkyIndex: Double?,
    val snowExpected: Boolean = false,
)

/** Battery/flow context needed to recognise curtailment (off-grid hybrid limits PV when the battery is full). */
data class FlowContext(
    val socPercent: Double?,
    val maxSocPercent: Double,
    val loadW: Double?,
    val batteryPowerW: Double?,
    /** True when surplus can be exported (grid-tied); off-grid systems curtail instead. */
    val canExport: Boolean,
)

data class DiagnosticInput(
    val now: Instant,
    val zone: ZoneId,
    val peakW: Double,
    val reality: PvReality?,
    val recent: List<RealitySample> = emptyList(),
    val link: LinkStatus? = null,
    val freshness: Freshness? = null,
    val linkFailures: Int = 0,
    val validationIssues: List<TelemetryIssue> = emptyList(),
    val simulated: Boolean = false,
    val snowExpected: Boolean = false,
    val soiling: SoilingAssessment? = null,
    val degradation: DegradationAssessment? = null,
    val mppt: MpptAnalysis? = null,
    val flow: FlowContext? = null,
    /** Median measured conversion efficiency (see [ConversionEfficiency]); null = not measurable. */
    val conversionEfficiency: Double? = null,
    val conversionSamples: Int = 0,
)

data class PvDiagnosis(
    val status: PvHealthStatus,
    val primary: Diagnosis,
    val all: List<Diagnosis>,
    val confidence: Double,
)

/**
 * PV Doctor: turns the reality check, history, weather, MPPT, soiling/degradation analyses and link state into
 * ranked diagnoses. Each diagnosis carries severity, confidence, evidence, expected impact and one clear
 * action. Conservative: no fault is claimed without a sustained deviation beyond the model uncertainty.
 */
object PvDiagnosticEngine {
    const val SUSTAINED_MINUTES = 30L
    const val MIN_SUSTAINED_SAMPLES = 3
    const val LOW_OUTPUT_PERCENT = 15.0
    const val HIGH_OUTPUT_PERCENT = 15.0
    const val TEMPERATURE_LOSS_PERCENT = 10.0
    const val CONVERSION_MIN = 0.80
    const val DEGRADATION_ALERT = 0.8

    fun diagnose(i: DiagnosticInput): PvDiagnosis {
        val out = mutableListOf<Diagnosis>()
        val r = i.reality

        // 1. Communication – without fresh data nothing else about the array can be judged.
        val offline = i.link == LinkStatus.OFFLINE || i.freshness == Freshness.LAST_KNOWN || i.freshness == Freshness.STALE
        if (offline || i.linkFailures >= 3) {
            out += Diagnosis(DiagnosisType.COMMUNICATION_PROBLEM, if (i.link == LinkStatus.OFFLINE) DiagnosisSeverity.WARNING else DiagnosisSeverity.INFO,
                0.9, listOfNotNull(
                    i.link?.let { "Stan łącza: ${it.name}" },
                    i.freshness?.let { "Świeżość danych: ${it.name}" },
                    "Kolejne błędy odczytu: ${i.linkFailures}".takeIf { i.linkFailures > 0 },
                ), null, "Sprawdź zasilanie i połączenie mostka RS232/RS485 (Wi-Fi, kabel, adres). Diagnoza produkcji wstrzymana do czasu odzyskania danych.")
        }

        // 2. Readings rejected by validation (impossible values, inconsistent P vs V×I, frozen values).
        val pvIssues = i.validationIssues.filter { it.type != IssueType.ZERO_PV && (it.field == null || it.field.name.startsWith("PV_")) }
        if (pvIssues.isNotEmpty()) {
            out += Diagnosis(DiagnosisType.SENSOR_ANOMALY, DiagnosisSeverity.WARNING, 0.75, pvIssues.map { "${it.type.label}: ${it.detail}" }, null,
                "Odczyty PV odrzucone przez walidację – zweryfikuj mapę rejestrów (log rejestrów w Diagnostyce) i porównaj z wyświetlaczem falownika.")
        }

        val compared = r != null && r.deviationPercent != null && !offline
        val band = r?.let { maxOf(it.uncertainty, PvRealityEngine.MIN_BAND) * 100 } ?: 0.0

        // 3. Snow (forecast says covered, measurement confirms near-zero output).
        if (i.snowExpected && r != null && r.status != RealityStatus.NIGHT) {
            val unshaded = r.theoreticalW
            val actual = r.actualW
            val confirmed = actual != null && unshaded > i.peakW * 0.1 && actual < unshaded * 0.2
            out += Diagnosis(DiagnosisType.SNOW_SUSPECTED, if (confirmed) DiagnosisSeverity.WARNING else DiagnosisSeverity.INFO,
                if (confirmed) 0.8 else 0.5, listOfNotNull(
                    "Prognoza: pokrywa śnieżna przy temperaturze ≤ 1 °C",
                    actual?.let { "Produkcja ${"%.0f".format(it)} W przy możliwych ${"%.0f".format(unshaded)} W bez śniegu" },
                ), if (confirmed) unshaded - (actual ?: 0.0) else null,
                "Jeśli to bezpieczne, usuń śnieg miękką szczotką – nie wchodź na dach i nie używaj ostrych narzędzi.")
        }

        // 4. Curtailment: battery full and no export – the inverter limits PV on purpose.
        val flow = i.flow
        val curtailed = compared && flow != null && !flow.canExport && flow.socPercent != null &&
            flow.socPercent >= flow.maxSocPercent - 3 && r!!.deviationPercent!! < -band
        if (curtailed) {
            out += Diagnosis(DiagnosisType.CURTAILMENT_BATTERY_FULL, DiagnosisSeverity.INFO, 0.8, listOfNotNull(
                "SOC ${"%.0f".format(flow!!.socPercent)}% (maks. ${"%.0f".format(flow.maxSocPercent)}%)",
                flow.loadW?.let { "Odbiory ${"%.0f".format(it)} W – nadwyżki nie ma gdzie oddać" },
                "Produkcja ${"%.1f".format(r!!.deviationPercent)}% względem modelu",
            ), r.expectedW - (r.actualW ?: 0.0), "To normalne zachowanie systemu off-grid. Nadwyżkę możesz wykorzystać: włącz teraz odbiory elastyczne (bojler, chłodnia, pranie).")
        }

        // 5. Sustained deviation from the model beyond its uncertainty.
        val window = i.recent.filter { Duration.between(it.time, i.now).toMinutes() in 0..SUSTAINED_MINUTES && it.expectedW >= i.peakW * PvRealityEngine.MIN_THEORETICAL_SHARE }
        val devs = window.map { (it.actualW - it.expectedW) / it.expectedW * 100 }
        val sustainedLow = devs.size >= MIN_SUSTAINED_SAMPLES && devs.count { it < -maxOf(band, LOW_OUTPUT_PERCENT) } >= devs.size * 0.7
        val sustainedHigh = devs.size >= MIN_SUSTAINED_SAMPLES && devs.count { it > maxOf(band, HIGH_OUTPUT_PERCENT) } >= devs.size * 0.7
        val explainedLow = curtailed || out.any { it.type == DiagnosisType.SNOW_SUSPECTED && it.severity == DiagnosisSeverity.WARNING }

        // 6. Time-of-day pattern on clear days → an obstacle the shading model does not know.
        shadingPattern(i)?.let { out += it }

        // 7. Long-term analyses.
        i.soiling?.takeIf { it.state == SoilingState.SUSPECTED || it.state == SoilingState.CONFIRMED }?.let { s ->
            out += Diagnosis(DiagnosisType.SOILING_SUSPECTED,
                if ((s.lossPercent ?: 0.0) >= 8) DiagnosisSeverity.WARNING else DiagnosisSeverity.INFO,
                s.confidence, listOf(s.state.label) + s.evidence, r?.let { rr -> s.lossPercent?.let { rr.expectedW * it / 100 } },
                "Umyj panele (miękka szczotka, woda demineralizowana, rano lub wieczorem) i porównaj produkcję w kolejne pogodne dni.")
        }
        i.degradation?.let { d -> d.ratePercentPerYear?.takeIf { it >= DEGRADATION_ALERT }?.let { d to it } }?.let { (d, rate) ->
            out += Diagnosis(DiagnosisType.PV_DEGRADATION, DiagnosisSeverity.INFO, d.confidence, d.evidence,
                r?.let { it.expectedW * rate / 100 }, "Degradacja ${"%.2f".format(rate)}%/rok jest wyższa niż typowe 0,3–0,8%/rok – sprawdź gwarancję mocy producenta modułów.")
        }

        // 8. MPPT / strings.
        i.mppt?.takeIf { it.available }?.let { m ->
            if (m.abnormal.isNotEmpty()) {
                val bad = m.statuses.filter { it.index in m.abnormal }
                out += Diagnosis(DiagnosisType.MPPT_ANOMALY, DiagnosisSeverity.WARNING, m.confidence,
                    bad.map { s -> "${s.label}: ${s.powerW?.let { "%.0f W".format(it) } ?: "N/A"}" +
                        (s.expectedW?.let { " / oczekiwane %.0f W".format(it) } ?: "") +
                        (s.deviationPercent?.let { " (%.1f%%)".format(it) } ?: "") +
                        (s.peerDeviationPercent?.let { ", względem pozostałych %.1f%%".format(it) } ?: "") } + "Możliwe przyczyny: ${m.possibleCauses.joinToString()}",
                    bad.sumOf { s -> s.expectedW?.let { e -> (e - (s.powerW ?: 0.0)).coerceAtLeast(0.0) } ?: 0.0 }.takeIf { it > 0 },
                    "Obejrzyj string tego MPPT (zacienienie, złącza MC4, bezpiecznik). Nie otwieraj obwodów DC pod obciążeniem.")
            }
            if (m.stringMismatch) {
                out += Diagnosis(DiagnosisType.STRING_MISMATCH, DiagnosisSeverity.INFO, m.confidence * 0.8,
                    listOf("Rozrzut napięć MPP: ${"%.0f".format(m.voltageSpreadPercent)}% przy tej samej liczbie modułów"), null,
                    "Sprawdź, czy stringi mają tyle samo modułów tego samego typu i tę samą orientację.")
            }
        }

        // 9. Conversion efficiency (PV in → AC loads + battery out), only with enough samples.
        i.conversionEfficiency?.takeIf { i.conversionSamples >= 10 && it < CONVERSION_MIN }?.let { eff ->
            out += Diagnosis(DiagnosisType.INVERTER_EFFICIENCY_ANOMALY, DiagnosisSeverity.INFO, 0.4, listOf(
                "Mediana sprawności konwersji: ${"%.0f".format(eff * 100)}% z ${i.conversionSamples} odczytów",
                "Mapa rejestrów niezweryfikowana na urządzeniu – możliwy błąd skalowania",
            ), null, "Porównaj moc PV i moc wyjściową z wyświetlaczem falownika; jeśli się zgadzają, sprawdź temperaturę i wentylację falownika.")
        }

        // 10. Temperature (explains part of the gap – information, not a fault).
        r?.losses?.firstOrNull { it.step == LossStep.TEMPERATURE }?.takeIf { it.percentOfTheoretical >= TEMPERATURE_LOSS_PERCENT }?.let { t ->
            out += Diagnosis(DiagnosisType.TEMPERATURE_LOSS, DiagnosisSeverity.INFO, 0.7, listOfNotNull(
                r.cellTemperatureC?.let { "Temperatura ogniw ok. ${"%.0f".format(it)} °C" },
                "Strata temperaturowa ${"%.1f".format(t.percentOfTheoretical)}% mocy teoretycznej",
            ), t.watts, "Normalne w upale. Zapewnij przepływ powietrza pod panelami; nie polewaj gorących paneli zimną wodą.")
        }

        // 11. Unexplained deviation.
        if (compared && sustainedLow && !explainedLow && out.none { it.type in EXPLAINS_LOW }) {
            val median = Stats.median(devs)!!
            out += Diagnosis(DiagnosisType.UNEXPECTED_LOW_OUTPUT, if (median < -40) DiagnosisSeverity.CRITICAL else DiagnosisSeverity.WARNING,
                (r!!.confidence * 0.9).coerceIn(0.2, 0.9), listOf(
                    "Od ${SUSTAINED_MINUTES} min produkcja ${"%.1f".format(median)}% względem modelu (${devs.size} odczytów)",
                    "Niepewność modelu ±${"%.0f".format(band)}% (${r.irradianceBasis.label})",
                ), r.unexplainedW?.coerceAtLeast(0.0), "Sprawdź wyłączniki DC, bezpieczniki i komunikaty falownika; jeśli niebo jest zachmurzone mimo prognozy, odczekaj.")
        } else if (compared && sustainedHigh) {
            out += Diagnosis(DiagnosisType.UNEXPECTED_HIGH_OUTPUT, DiagnosisSeverity.INFO, 0.6, listOf(
                "Produkcja wyższa od modelu o ${"%.1f".format(Stats.median(devs))}% od $SUSTAINED_MINUTES min",
                "Najczęściej: prognoza zaniżyła nasłonecznienie (chmury rozjaśniające) albo moc instalacji w ustawieniach jest za niska",
            ), null, "Sprawdź moc szczytową i orientację w ustawieniach; jeśli są poprawne – nic nie rób, model nauczy się z kalibracji.")
        }

        if (out.none { it.severity != DiagnosisSeverity.INFO } && (r?.status == RealityStatus.OK || out.isEmpty())) {
            out += Diagnosis(DiagnosisType.NORMAL, DiagnosisSeverity.INFO, r?.confidence?.takeIf { it > 0 } ?: 0.5, listOfNotNull(
                r?.deviationPercent?.let { "Odchylenie ${"%.1f".format(it)}% mieści się w niepewności ±${"%.0f".format(band)}%" },
                r?.status?.takeIf { it == RealityStatus.LOW_LIGHT || it == RealityStatus.NIGHT || it == RealityStatus.NO_MEASUREMENT }?.label,
                "Dane z symulatora – diagnoza demonstracyjna".takeIf { i.simulated },
            ), null, "Brak działań.")
        }

        // Severity first; among equals "NORMAL" leads (information items only add context to it).
        val ranked = out.sortedWith(compareByDescending<Diagnosis> { it.severity }.thenByDescending { it.type == DiagnosisType.NORMAL }.thenByDescending { it.confidence * (1 + (it.impactW ?: 0.0) / i.peakW) })
        val primary = ranked.first()
        val status = when {
            ranked.any { it.severity == DiagnosisSeverity.CRITICAL } -> PvHealthStatus.PROBLEM
            ranked.any { it.severity == DiagnosisSeverity.WARNING } -> PvHealthStatus.ATTENTION
            r == null || r.status == RealityStatus.NO_MEASUREMENT -> PvHealthStatus.UNKNOWN
            else -> PvHealthStatus.OK
        }
        val confidence = if (i.simulated) primary.confidence * 0.5 else primary.confidence
        return PvDiagnosis(status, primary, ranked, confidence)
    }

    /** Diagnoses that already explain low output (no extra "unexplained" alarm). */
    private val EXPLAINS_LOW = setOf(
        DiagnosisType.CURTAILMENT_BATTERY_FULL, DiagnosisType.SNOW_SUSPECTED, DiagnosisType.COMMUNICATION_PROBLEM,
        DiagnosisType.SENSOR_ANOMALY, DiagnosisType.MPPT_ANOMALY, DiagnosisType.SHADING_SUSPECTED,
    )

    /**
     * Clear-sky hours with a repeatable deficit at the same local hour on ≥ 2 days, while the other hours match
     * the model → an obstacle missing from the shading model (or a wrong azimuth).
     */
    internal fun shadingPattern(i: DiagnosticInput): Diagnosis? {
        val clear = i.recent.filter { (it.clearSkyIndex ?: 0.0) >= 0.85 && !it.snowExpected && it.expectedW >= i.peakW * 0.1 }
        val byHour = clear.groupBy { it.time.atZone(i.zone).hour }
            .filterValues { s -> s.map { it.time.atZone(i.zone).toLocalDate() }.distinct().size >= 2 }
            .mapValues { (_, s) -> Stats.median(s.map { it.actualW / it.expectedW })!! }
        if (byHour.size < 3) return null
        val bad = byHour.filterValues { it < 0.8 }
        val good = byHour.filterValues { it >= 0.93 }
        if (bad.isEmpty() || good.size < 2) return null
        val worst = bad.values.min()
        return Diagnosis(DiagnosisType.SHADING_SUSPECTED, DiagnosisSeverity.INFO, (0.45 + 0.1 * bad.size).coerceAtMost(0.8), listOf(
            "Powtarzalny spadek w godzinach: ${bad.keys.sorted().joinToString { "%02d:00".format(it) }} (do ${"%.0f".format((1 - worst) * 100)}% poniżej modelu)",
            "Pozostałe pogodne godziny zgodne z modelem (${good.size} h)",
            "Wzór powtarza się w różne dni – to nie chmury",
        ), null, "Sprawdź, co zasłania panele o tej porze (drzewo, komin, antena) i dodaj przeszkodę w analizie zacienienia.")
    }
}

/**
 * Conversion efficiency estimate from one reading: (AC load + battery charge + export) / PV, only when PV is the
 * sole source (battery not discharging, no grid import). ESTIMATED – depends on the unverified register map.
 */
object ConversionEfficiency {
    fun estimate(t: InverterTelemetry, minPvW: Double = 300.0): Double? {
        val pv = t.pv.powerW?.takeIf { it >= minPvW } ?: return null
        val load = t.load.powerW ?: return null
        val battery = t.battery.powerW ?: return null
        if (battery < -20.0) return null
        val grid = t.grid.powerW
        if (grid != null && grid > 20.0) return null
        val out = load + battery.coerceAtLeast(0.0) + (grid?.let { (-it).coerceAtLeast(0.0) } ?: 0.0)
        return (out / pv).takeIf { it in 0.3..1.1 }
    }

    /** Median over readings where the estimate is defined; pair = (median, samples). */
    fun median(readings: List<InverterTelemetry>): Pair<Double?, Int> {
        val v = readings.mapNotNull { estimate(it) }
        return Stats.median(v) to v.size
    }
}

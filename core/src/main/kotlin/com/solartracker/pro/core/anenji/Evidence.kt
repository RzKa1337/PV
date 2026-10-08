package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.diagnostics.MpptAnalyzer
import com.solartracker.pro.core.diagnostics.MpptConfig
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EvidenceType(val label: String) {
    OBSERVATION("pomiar"), EXPECTATION("model PV"), BASELINE("baseline historyczny"), WEATHER("pogoda"), SOLAR("pozycja słońca"),
    SHADING("zacienienie"), MPPT("MPPT"), BATTERY("bateria"), LOAD("obciążenie"), GRID("sieć"), INVERTER("falownik"),
    SETTINGS("ustawienia"), EVENT("zdarzenie"), COMMUNICATION("komunikacja"), DATA_QUALITY("jakość danych"),
}

/** How a piece of evidence relates to the diagnosis. */
enum class EvidenceRole { SUPPORTS, CONTRADICTS, CONTEXT, UNKNOWN }

data class EvidenceNode(
    val id: String,
    val type: EvidenceType,
    val label: String,
    val timestamp: Instant?,
    val source: TelemetrySource,
    val value: Double?,
    val valueText: String,
    val expectedValue: Double?,
    /** (value − expected) / expected; null when not comparable. */
    val deviation: Double?,
    val confidence: Double,
    val role: EvidenceRole,
    /** "normalny" / "nieprawidłowy" / "brak danych". */
    val status: String,
    /** Raw source of this evidence (file/line/register) when it comes from Anenji data. */
    val raw: RawRef? = null,
)

/** "EvidenceGraph": the diagnosis at the root, evidence nodes below – answers "how do you know?". */
data class EvidenceGraph(val diagnosis: String, val nodes: List<EvidenceNode>) {
    val supporting: List<EvidenceNode> get() = nodes.filter { it.role == EvidenceRole.SUPPORTS }
    val contradicting: List<EvidenceNode> get() = nodes.filter { it.role == EvidenceRole.CONTRADICTS }

    fun render(): String = buildString {
        appendLine(diagnosis)
        nodes.forEachIndexed { i, n ->
            val last = i == nodes.lastIndex
            append(if (last) "└── " else "├── ")
            append("${n.label}: ${n.valueText}")
            n.expectedValue?.let { append(" (oczekiwane ${fmt(it)})") }
            n.deviation?.let { append(" ${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}%") }
            append(" [${n.status}]")
            n.raw?.let { append(" ← ${it.source}, ${it.locator}") }
            appendLine()
        }
    }.trimEnd()

    private fun fmt(v: Double) = if (abs(v) >= 100) "%.0f".format(v) else "%.2f".format(v)
}

/** Evidence-based certainty; FAULT wording is reserved for CONFIRMED. */
enum class Certainty(val label: String) { CONFIRMED("POTWIERDZONE"), LIKELY("PRAWDOPODOBNE"), POSSIBLE("MOŻLIWE"), INSUFFICIENT_DATA("ZA MAŁO DANYCH") }

data class CauseAssessment(val cause: String, val status: String, val reason: String, val score: Double)

data class RootCause(
    val observed: String,
    val possible: List<CauseAssessment>,
    val eliminated: List<CauseAssessment>,
    val mostLikely: String?,
    val certainty: Certainty,
)

data class EnergyImpact(
    val energyKwh: Double?,
    val cost: Double?,
    val batteryImpact: String?,
    val downtime: Duration?,
    val confidence: Double,
    val basis: String,
) {
    val available: Boolean get() = energyKwh != null || downtime != null || batteryImpact != null

    companion object {
        fun na(reason: String) = EnergyImpact(null, null, null, null, 0.0, reason)
    }
}

enum class DiagnosisSeverityLevel { INFO, WARNING, CRITICAL }

/** One forensic diagnosis: traceable from the conclusion through evidence and normalised data to the raw Anenji source. */
data class ForensicDiagnosis(
    val id: String,
    val anomaly: Anomaly,
    val title: String,
    val severity: DiagnosisSeverityLevel,
    /** How sure we are of the cause ([RootCause.certainty], capped by confidence). */
    val certainty: Certainty,
    /** How sure we are that the anomaly itself happened (measurement/event vs model-only). Drives severity. */
    val observation: Certainty,
    val confidence: ConfidenceBreakdown,
    val evidence: EvidenceGraph,
    val rootCause: RootCause,
    val impact: EnergyImpact,
    val recommendation: String,
)

/**
 * "AnenjiCorrelationEngine": gathers independent signals around an anomaly – model PV, baseline, weather, sun, shading,
 * MPPT, battery, load, grid, events, settings and data quality – each as an [EvidenceNode] with its expected value.
 */
object AnenjiCorrelationEngine {
    fun evidence(a: Anomaly, f: ForensicContext): List<EvidenceNode> {
        val mid = Instant.ofEpochSecond((a.start.epochSecond + a.end.epochSecond) / 2)
        val win = f.samples.filter { !it.time.isBefore(a.start.minus(Duration.ofMinutes(5))) && !it.time.isAfter(a.end.plus(Duration.ofMinutes(5))) }
        val near = a.samples.minByOrNull { abs(Duration.between(it.time, mid).seconds) } ?: win.minByOrNull { abs(Duration.between(it.time, mid).seconds) }
        val src = near?.origin?.let { TelemetrySource.of(it) } ?: TelemetrySource.UNKNOWN
        val ctx = f.context?.at(mid)
        val nodes = mutableListOf<EvidenceNode>()
        var id = 0
        fun add(type: EvidenceType, label: String, value: Double?, text: String, expected: Double? = null, role: EvidenceRole = EvidenceRole.CONTEXT,
                status: String = "normalny", source: TelemetrySource = src, conf: Double = 0.7, raw: RawRef? = null) {
            nodes += EvidenceNode("e${++id}", type, label, mid, source, value, text, expected,
                if (value != null && expected != null && abs(expected) > 1e-9) (value - expected) / expected else null, conf, role, status, raw)
        }
        fun median(c: Channel) = Stats.median(win.mapNotNull { it[c] })

        // Observation itself (with its raw source).
        add(EvidenceType.OBSERVATION, a.type.label, a.observed, a.detail, a.expected, EvidenceRole.SUPPORTS, "nieprawidłowy", raw = near?.raw)

        if (a.type.category == AnomalyCategory.PV || a.type == AnomalyType.LOW_BATTERY) {
            val pv = median(Channel.PV_POWER)
            ctx?.expectedPvW?.let { e -> add(EvidenceType.EXPECTATION, "PV oczekiwane (model)", pv, w(pv), e, source = TelemetrySource.PV_MODEL,
                status = if (pv != null && pv < e * 0.7) "nieprawidłowy" else "normalny", conf = 0.65) }
                ?: add(EvidenceType.EXPECTATION, "PV oczekiwane (model)", null, "N/A", role = EvidenceRole.UNKNOWN, status = "brak danych", source = TelemetrySource.PV_MODEL, conf = 0.0)
            f.baseline.pvAt(mid)?.let { b -> add(EvidenceType.BASELINE, "Typowe PV w tych warunkach", pv, w(pv), b.median,
                status = if (pv != null && pv < b.low) "nieprawidłowy" else "normalny", conf = b.quality) }
                ?: add(EvidenceType.BASELINE, "Typowe PV w tych warunkach", null, "INSUFFICIENT DATA", role = EvidenceRole.UNKNOWN, status = "brak danych", conf = 0.0)
            when {
                ctx?.effectiveCloudPercent != null -> ctx.effectiveCloudPercent!!.let { eff -> add(EvidenceType.WEATHER, "Zachmurzenie", eff,
                    "${eff.roundToInt()}% słońca zasłonięte" + (ctx.cloudCoverPercent?.takeIf { ctx.clearSkyIndex != null }?.let { " (całkowite ${it.roundToInt()}%)" } ?: ""),
                    source = ctx.weatherSource, status = if (eff >= 60) "pochmurno" else "stabilne", conf = 0.6) }
                else -> add(EvidenceType.WEATHER, "Zachmurzenie", null, "N/A", role = EvidenceRole.UNKNOWN, status = "brak danych", source = TelemetrySource.UNKNOWN, conf = 0.0)
            }
            ctx?.clearSkyIndex?.let { add(EvidenceType.WEATHER, "Indeks czystego nieba", it, "%.2f".format(it), source = ctx.weatherSource, status = if (it >= 0.8) "stabilne" else "zachmurzenie", conf = 0.6) }
            ctx?.sunElevationDeg?.let { add(EvidenceType.SOLAR, "Wysokość słońca", it, "${it.roundToInt()}°", source = TelemetrySource.PV_MODEL, conf = 0.95) }
            ctx?.ambientC?.let { add(EvidenceType.WEATHER, "Temperatura powietrza", it, "${it.roundToInt()} °C", source = ctx.weatherSource, status = if (it > 35) "wysoka" else "normalna") }
            ctx?.shadingFactor?.let { add(EvidenceType.SHADING, "Zacienienie (model)", (1 - it) * 100, "${((1 - it) * 100).roundToInt()}% straty",
                source = TelemetrySource.PV_MODEL, status = if (it < 0.9) "zacienienie" else "bez zmian", conf = 0.6) }
                ?: add(EvidenceType.SHADING, "Zacienienie (model)", null, "N/A", role = EvidenceRole.UNKNOWN, status = "brak danych", conf = 0.0)
            val withMppt = win.lastOrNull { it.mppts.size >= 2 }
            if (withMppt != null) {
                val m = MpptAnalyzer.analyze(withMppt.mppts, withMppt.mppts.map { MpptConfig(it.index, 1000.0) })
                m.statuses.forEach { st -> add(EvidenceType.MPPT, st.label, st.peerDeviationPercent, st.peerDeviationPercent?.let { "${it.roundToInt()}% vs pozostałe" } ?: "N/A",
                    status = if (st.index in m.abnormal) "nieprawidłowy" else "normalny", conf = m.confidence, raw = withMppt.raw) }
            } else add(EvidenceType.MPPT, "MPPT", null, "N/A (falownik nie raportuje)", role = EvidenceRole.UNKNOWN, status = "brak danych", conf = 0.0)
        }
        median(Channel.SOC)?.let { soc -> add(EvidenceType.BATTERY, "SOC", soc, "${soc.roundToInt()}%", status = if (soc < 25) "niski" else if (soc >= 97) "pełna" else "normalny") }
        median(Channel.LOAD_POWER)?.let { load ->
            val b = f.baseline.hourly(Channel.LOAD_POWER, mid)
            add(EvidenceType.LOAD, "Obciążenie", load, w(load), b?.median, status = if (b != null && load > b.high * 1.5) "wysokie" else "normalne", conf = b?.quality ?: 0.5)
        }
        median(Channel.GRID_POWER)?.let { g -> add(EvidenceType.GRID, "Sieć", g, w(g), status = if (g > 50) "pobór" else "brak poboru") }
        median(Channel.INVERTER_TEMPERATURE)?.let { t -> add(EvidenceType.INVERTER, "Temperatura falownika", t, "${t.roundToInt()} °C", status = if (t > 70) "wysoka" else "normalna") }
        f.events.filter { e -> !e.start.isBefore(a.start.minus(Duration.ofMinutes(30))) && !e.start.isAfter(a.end.plus(Duration.ofMinutes(15))) && e.description != a.detail }
            .take(5).forEach { e -> add(EvidenceType.EVENT, e.category.label, null, e.description, status = e.severity.name.lowercase(), conf = 0.8) }
        f.settings?.values?.filter { it.status != SettingStatus.NOT_AVAILABLE }?.takeIf { it.isNotEmpty() }?.let { v ->
            add(EvidenceType.SETTINGS, "Ustawienia (snapshot)", null, v.take(3).joinToString { "${it.key.label} ${it.display}" }, source = TelemetrySource.USER_INPUT, status = "niezweryfikowane", conf = 0.5)
        }
        val gaps = win.zipWithNext().count { (x, y) -> Duration.between(x.time, y.time) > Duration.ofMinutes(15) }
        add(EvidenceType.DATA_QUALITY, "Ciągłość danych", gaps.toDouble(), if (gaps == 0) "bez luk (${win.size} próbek)" else "$gaps luk w oknie",
            status = if (gaps == 0) "dobra" else "luki", conf = if (gaps == 0) 0.9 else 0.5)
        return nodes
    }

    private fun w(x: Double?) = x?.let { if (abs(it) >= 1000) "%.2f kW".format(it / 1000) else "%.0f W".format(it) } ?: "N/A"
}

/**
 * "RootCauseAnalyzer": Observed → evidence → possible causes → eliminated causes → most likely cause → certainty. A cause is
 * eliminated only by evidence; unknown evidence keeps a cause "possible". Never certain without enough independent support.
 */
object RootCauseAnalyzer {
    fun analyze(a: Anomaly, nodes: List<EvidenceNode>, f: ForensicContext, all: List<Anomaly>): RootCause {
        fun node(type: EvidenceType, label: String? = null) = nodes.firstOrNull { it.type == type && (label == null || it.label == label) }
        val cloud = node(EvidenceType.WEATHER, "Zachmurzenie")?.value
        val csi = node(EvidenceType.WEATHER, "Indeks czystego nieba")?.value
        val shade = node(EvidenceType.SHADING)?.value
        val ambient = node(EvidenceType.WEATHER, "Temperatura powietrza")?.value
        val soc = node(EvidenceType.BATTERY)?.value
        val loadNode = node(EvidenceType.LOAD)
        val mpptNodes = nodes.filter { it.type == EvidenceType.MPPT && it.value != null }
        val overlapping = { t: AnomalyType -> all.any { it.type == t && !it.end.isBefore(a.start) && !it.start.isAfter(a.end) } }
        val c = mutableListOf<CauseAssessment>()
        fun cause(name: String, supported: Boolean?, reasonYes: String, reasonNo: String, weight: Double = 1.0) {
            c += when (supported) {
                true -> CauseAssessment(name, "wspierana", reasonYes, weight)
                false -> CauseAssessment(name, "wyeliminowana", reasonNo, 0.0)
                null -> CauseAssessment(name, "możliwa", "brak danych do oceny", 0.2 * weight)
            }
        }
        when (a.type) {
            AnomalyType.LOW_PV, AnomalyType.RAPID_PV_DROP -> {
                cause("Zachmurzenie", csi?.let { it < 0.5 } ?: cloud?.let { it >= 60 }, "chmury zasłaniają ${cloud?.roundToInt()}% słońca", "niebo stabilne (zasłonięte ${cloud?.roundToInt() ?: "?"}% słońca, indeks ${csi?.let { "%.2f".format(it) } ?: "?"})")
                cause("Zacienienie", shade?.let { it > 10 }, "model zacienienia: ${shade?.roundToInt()}% straty", "model zacienienia bez zmian")
                cause("Anomalia MPPT", if (mpptNodes.isEmpty()) null else overlapping(AnomalyType.MPPT_IMBALANCE), "jedno wejście MPPT wyraźnie słabsze", "wszystkie MPPT pracują podobnie", 1.5)
                cause("Temperatura", ambient?.let { it > 35 }, "upał ${ambient?.roundToInt()} °C", "temperatura normalna")
                cause("Ograniczenie (pełna bateria)", soc?.let { it >= 97 && !f.canExport }, "SOC ${soc?.roundToInt()}% bez możliwości oddania energii", "bateria nie była pełna")
                cause("Śnieg", f.context?.at(a.start)?.snowExpected, "prognoza śniegu na panelach", "brak śniegu w prognozie")
                cause("Zabrudzenie", null, "", "")
                cause("Błąd odczytu", if (overlapping(AnomalyType.PV_CURRENT_ABNORMAL)) true else false, "moc PV niespójna z V×I", "moc zgodna z V×I", 0.8)
            }
            AnomalyType.LOW_BATTERY, AnomalyType.SOC_DROP -> {
                val high = loadNode?.status == "wysokie"
                cause("Duże obciążenie", loadNode?.let { high }, "obciążenie ${loadNode?.valueText} przy typowym ${loadNode?.expectedValue?.roundToInt()} W", "obciążenie typowe")
                val prevDay = a.start.atZone(f.zone).toLocalDate().minusDays(1)
                val prevPv = BaselineEnergy.day(f.samples, prevDay, f.zone)
                val typPv = f.baseline.dailyEnergy(Channel.PV_POWER, prevDay)
                cause("Mało PV poprzedniego dnia", if (prevPv == null || typPv == null) null else prevPv < typPv.low * 0.8,
                    "PV poprzedniego dnia ${"%.1f".format(prevPv)} kWh (typowo ${"%.1f".format(typPv?.median)})", "PV poprzedniego dnia typowe")
                val morningPv = nodes.firstOrNull { it.type == EvidenceType.EXPECTATION }
                cause("Mało PV rano", morningPv?.value?.let { it < 300 }, "PV ${morningPv?.valueText}", "PV wystarczające")
                cause("Model SOC / pojemność", if (a.type == AnomalyType.SOC_DROP) true else null, "SOC spada szybciej niż wynika z przepływu energii", "")
            }
            AnomalyType.UNEXPECTED_GRID -> {
                cause("Ustawienie priorytetu źródła", f.settings?.value(SettingKey.OUTPUT_SOURCE_PRIORITY)?.takeIf { it.status != SettingStatus.NOT_AVAILABLE }?.let { true },
                    "priorytet: ${f.settings?.value(SettingKey.OUTPUT_SOURCE_PRIORITY)?.display}", "")
                cause("Przeciążenie", if (overlapping(AnomalyType.OVERLOAD)) true else loadNode?.let { it.status == "wysokie" }, "obciążenie wysokie", "obciążenie typowe")
                cause("Bateria niedostępna", f.events.any { "niepodłączona" in it.description || "bms" in it.description.lowercase() }.takeIf { it }, "zdarzenie baterii", "")
            }
            AnomalyType.RESTART -> {
                val before = f.events.filter { it.start.isBefore(a.start) && Duration.between(it.start, a.start) <= Duration.ofMinutes(15) }
                cause("Awaria przed restartem", before.any { it.severity == EventSeverity.CRITICAL }, before.firstOrNull { it.severity == EventSeverity.CRITICAL }?.description ?: "", "brak awarii przed restartem")
                cause("Przeciążenie", before.any { "przeciąż" in it.description.lowercase() }, "przeciążenie przed restartem", "brak przeciążenia")
                cause("Przegrzanie", before.any { it.category == EventCategory.TEMPERATURE }, "ostrzeżenie temperaturowe", "brak ostrzeżeń temperatury")
                cause("Zanik zasilania / ręczne wyłączenie", null, "", "")
            }
            AnomalyType.GRID_OUTAGE -> {
                cause("Zanik zasilania po stronie sieci", a.samples.isNotEmpty(), "falownik raportował dane, a napięcie sieci było ~0 V", "")
                cause("Odłączenie wejścia AC / zabezpieczenie w instalacji", null, "", "")
            }
            AnomalyType.CLIPPING -> cause("Moc paneli większa niż limit falownika", true, "PV na limicie ${f.pvLimitW?.toInt()} W", "")
            AnomalyType.MPPT_IMBALANCE -> {
                cause("Zacienienie części stringu", shade?.let { it > 10 }, "model zacienienia", "model zacienienia bez zmian")
                cause("Złącze / bezpiecznik / moduł / konfiguracja", null, "", "")
            }
            else -> Unit // no cause rules for this type: the cause stays INSUFFICIENT DATA rather than restating the symptom
        }
        val supported = c.filter { it.status == "wspierana" }.sortedByDescending { it.score }
        val eliminated = c.filter { it.status == "wyeliminowana" }
        val possible = c.filter { it.status != "wyeliminowana" }
        val keyUnknown = nodes.count { it.role == EvidenceRole.UNKNOWN }
        val certainty = when {
            supported.size == 1 && eliminated.size >= 2 && keyUnknown <= 1 -> Certainty.CONFIRMED
            supported.isNotEmpty() && eliminated.isNotEmpty() -> Certainty.LIKELY
            supported.isNotEmpty() || possible.isNotEmpty() && keyUnknown <= 2 -> Certainty.POSSIBLE
            else -> Certainty.INSUFFICIENT_DATA
        }.let { if (keyUnknown >= 3 && it == Certainty.CONFIRMED) Certainty.LIKELY else it }
        val most = supported.firstOrNull()?.cause ?: possible.singleOrNull()?.cause
        return RootCause(a.type.label + " – " + a.detail, possible, eliminated, most, if (most == null && supported.isEmpty()) Certainty.INSUFFICIENT_DATA else certainty)
    }
}

/** Daily energy helper shared by root-cause rules (gaps not bridged). */
object BaselineEnergy {
    fun day(s: List<AnalyzerSample>, day: java.time.LocalDate, zone: ZoneId, c: Channel = Channel.PV_POWER): Double? {
        val d = s.filter { it.time.atZone(zone).toLocalDate() == day }
        return if (d.size < 10) null else SystemBaselineEngine.energyKwh(d, c)
    }
}

object EnergyImpactCalculator {
    fun impact(a: Anomaly, f: ForensicContext): EnergyImpact {
        fun integrate(list: List<AnalyzerSample>, value: (AnalyzerSample) -> Double?): Double? {
            var sum = 0.0; var ok = false
            for ((x, y) in list.sortedBy { it.time }.zipWithNext()) {
                val dt = Duration.between(x.time, y.time).toMillis() / 3_600_000.0
                if (dt <= 0 || dt > 0.25) continue
                val v = value(x) ?: continue
                sum += v * dt / 1000; ok = true
            }
            return if (ok) sum else null
        }
        val window = f.samples.filter { !it.time.isBefore(a.start) && !it.time.isAfter(a.end) }
        val price = f.pricePerKwh
        return when (a.type) {
            AnomalyType.LOW_PV, AnomalyType.MPPT_IMBALANCE, AnomalyType.RAPID_PV_DROP, AnomalyType.CLIPPING -> {
                val ctx = f.context ?: return EnergyImpact.na("brak modelu PV – utraconej energii nie da się policzyć")
                val lost = integrate(window) { x -> ctx.at(x.time)?.expectedPvW?.let { e -> (e - (x[Channel.PV_POWER] ?: return@let null)).coerceAtLeast(0.0) } }
                    ?: return EnergyImpact.na("za mało próbek w oknie")
                // Energy only counts as lost if it could have been used (off-grid with a full battery it would be curtailed anyway).
                val usable = if (!f.canExport && window.all { (it[Channel.SOC] ?: 0.0) >= 97 }) 0.0 else lost
                val b = f.baseline.pvAt(Instant.ofEpochSecond((a.start.epochSecond + a.end.epochSecond) / 2))
                EnergyImpact(usable, price?.let { usable * it }, null, null, (b?.quality ?: 0.4) * 0.9,
                    "Σ(model − pomiar) w oknie anomalii" + if (usable == 0.0 && lost > 0) " – bateria pełna, energia i tak niewykorzystana" else "")
            }
            AnomalyType.UNEXPECTED_GRID, AnomalyType.LOW_BATTERY -> {
                val w = if (a.type == AnomalyType.LOW_BATTERY) f.samples.filter { !it.time.isBefore(a.start) && it.time.isBefore(a.start.plus(Duration.ofHours(3))) } else window
                val grid = integrate(w) { it[Channel.GRID_POWER]?.coerceAtLeast(0.0) }
                val socDrop = window.mapNotNull { it[Channel.SOC] }.let { if (it.size >= 2) it.first() - it.min() else null }
                if (grid == null && socDrop == null) EnergyImpact.na("brak danych sieci i SOC")
                else EnergyImpact(grid, if (grid != null) price?.let { grid * it } else null, socDrop?.let { "SOC spadło o ${it.roundToInt()} p.p." }, null, 0.75,
                    "energia pobrana z sieci/agregatu po zdarzeniu (do 3 h)")
            }
            AnomalyType.GRID_OUTAGE -> {
                val soc = window.mapNotNull { it[Channel.SOC] }
                val drop = if (soc.size >= 2) soc.first() - soc.min() else null
                val outage = Duration.between(a.start, a.end).plusMinutes(5)
                EnergyImpact(integrate(window) { it[Channel.LOAD_POWER] }, null, drop?.let { "Zasilanie z baterii; SOC −${it.roundToInt()} p.p." }, outage, 0.85,
                    "energia odbiorów pokryta z baterii/PV w czasie braku sieci")
            }
            AnomalyType.COMM_TIMEOUT, AnomalyType.COMM_INVALID_FRAME, AnomalyType.COMM_MISSING ->
                EnergyImpact(null, null, null, Duration.between(a.start, a.end), 0.95, "czas bez danych; energia N/A")
            AnomalyType.RESTART -> EnergyImpact(null, null, null, null, 0.3, "wpływ energetyczny restartu nieznany (N/A)")
            else -> EnergyImpact.na("brak wiarygodnej metody wyliczenia wpływu")
        }
    }
}

/** Assembles diagnoses: evidence → root cause → confidence → certainty → impact → one recommendation. */
object ForensicDiagnosisBuilder {
    fun build(anomalies: List<Anomaly>, f: ForensicContext): List<ForensicDiagnosis> = anomalies.mapIndexed { i, a ->
        val nodes0 = AnenjiCorrelationEngine.evidence(a, f)
        val root = RootCauseAnalyzer.analyze(a, nodes0, f, anomalies)
        // Mark context evidence as supporting/contradicting the chosen cause where the rules decided it.
        val nodes = nodes0.map { n ->
            when {
                n.role != EvidenceRole.CONTEXT -> n
                root.eliminated.any { e -> n.label.lowercase().take(5) in e.cause.lowercase() || e.reason.contains(n.valueText) } -> n.copy(role = EvidenceRole.CONTRADICTS)
                root.possible.any { p -> p.status == "wspierana" && p.reason.contains(n.valueText) } -> n.copy(role = EvidenceRole.SUPPORTS)
                else -> n
            }
        }
        val origin = a.samples.firstOrNull()?.origin ?: f.samples.firstOrNull()?.origin ?: DataOrigin.IMPORTED
        val supporting = root.possible.count { it.status == "wspierana" } + 1
        val contradicting = root.eliminated.size.coerceAtMost(1) * 0 + nodes.count { it.role == EvidenceRole.CONTRADICTS && it.type == EvidenceType.OBSERVATION }
        val known = nodes.count { it.role != EvidenceRole.UNKNOWN }
        val conf = ConfidenceBreakdown(
            dataQuality = nodes.firstOrNull { it.type == EvidenceType.DATA_QUALITY }?.confidence ?: 0.6,
            sampleCount = ConfidenceEngine.sampleScore(maxOf(a.samples.size, 1), 6),
            sourceReliability = ConfidenceEngine.sourceReliability(origin),
            agreement = ConfidenceEngine.agreement(supporting, contradicting) * (known.toDouble() / nodes.size).coerceIn(0.4, 1.0),
            baselineQuality = nodes.firstOrNull { it.type == EvidenceType.BASELINE }?.confidence?.takeIf { it > 0 },
            modelCertainty = if (a.type.category == AnomalyCategory.PV) nodes.firstOrNull { it.type == EvidenceType.EXPECTATION }?.confidence?.takeIf { it > 0 } else null,
        )
        val certainty = if (conf.value < 0.35 && root.certainty == Certainty.CONFIRMED) Certainty.LIKELY else root.certainty
        val observation = observation(a, nodes).let { if (conf.value < 0.35 && it == Certainty.CONFIRMED) Certainty.LIKELY else it }
        val severity = when {
            observation == Certainty.CONFIRMED && a.type in CRITICAL_TYPES -> DiagnosisSeverityLevel.CRITICAL
            observation == Certainty.INSUFFICIENT_DATA || observation == Certainty.POSSIBLE -> DiagnosisSeverityLevel.INFO
            a.type in INFO_TYPES -> DiagnosisSeverityLevel.INFO
            else -> DiagnosisSeverityLevel.WARNING
        }
        val title = (root.mostLikely?.takeIf { it != a.type.label }?.let { "${a.type.label}: $it" } ?: a.type.label)
        ForensicDiagnosis("d${i + 1}", a, title, severity, certainty, observation, conf, EvidenceGraph("${certainty.label}: $title", nodes), root,
            EnergyImpactCalculator.impact(a, f), recommendation(a, root))
    }

    /** Model-based anomalies need the installation's own baseline to agree; direct measurements/events need a sustained run. */
    private val MODEL_TYPES = setOf(AnomalyType.LOW_PV, AnomalyType.HIGH_PV, AnomalyType.RAPID_PV_DROP, AnomalyType.SOC_DROP, AnomalyType.EFFICIENCY)
    private val EVENT_TYPES = setOf(AnomalyType.RESTART, AnomalyType.LOW_BATTERY, AnomalyType.GRID_TRANSITIONS, AnomalyType.COMM_TIMEOUT,
        AnomalyType.COMM_INVALID_FRAME, AnomalyType.COMM_RECONNECT, AnomalyType.COMM_MISSING, AnomalyType.COMM_STALE)

    private fun observation(a: Anomaly, nodes: List<EvidenceNode>): Certainty = when {
        a.type in EVENT_TYPES -> Certainty.CONFIRMED
        a.type in MODEL_TYPES -> {
            val baseline = nodes.firstOrNull { it.type == EvidenceType.BASELINE }
            val expected = nodes.firstOrNull { it.type == EvidenceType.EXPECTATION }
            when {
                baseline?.value != null && baseline.status == "nieprawidłowy" && a.samples.size >= ForensicAnomalyDetector.MIN_RUN -> Certainty.LIKELY
                a.type == AnomalyType.SOC_DROP && a.samples.size >= ForensicAnomalyDetector.MIN_RUN -> Certainty.LIKELY
                expected?.value != null || baseline?.value != null -> Certainty.POSSIBLE
                else -> Certainty.INSUFFICIENT_DATA
            }
        }
        a.samples.size >= ForensicAnomalyDetector.MIN_RUN -> Certainty.CONFIRMED
        a.samples.isNotEmpty() -> Certainty.LIKELY
        else -> Certainty.POSSIBLE
    }

    private val CRITICAL_TYPES = setOf(AnomalyType.OVERLOAD, AnomalyType.TEMPERATURE, AnomalyType.DISCHARGE_CURRENT_ABNORMAL, AnomalyType.GRID_OUTAGE)
    private val INFO_TYPES = setOf(AnomalyType.CLIPPING, AnomalyType.HIGH_PV, AnomalyType.COMM_RECONNECT, AnomalyType.COMM_MISSING)

    private fun recommendation(a: Anomaly, r: RootCause): String = when (a.type) {
        AnomalyType.LOW_PV, AnomalyType.RAPID_PV_DROP -> when (r.mostLikely) {
            "Anomalia MPPT" -> "Sprawdź napięcie i prąd słabszego MPPT, złącza MC4 i bezpieczniki stringu"
            "Zachmurzenie" -> "Nic nie rób – spadek wynika z pogody"
            "Ograniczenie (pełna bateria)" -> "Wykorzystaj nadwyżkę odbiorami elastycznymi – to nie awaria"
            "Zacienienie" -> "Sprawdź przeszkodę zacieniającą panele o tej porze"
            else -> "Porównaj produkcję z wyświetlaczem falownika i obejrzyj panele (zabrudzenie, uszkodzenia, złącza)"
        }
        AnomalyType.MPPT_IMBALANCE -> "Sprawdź napięcie/prąd tego MPPT, zacienienie i złącza – nie otwieraj obwodów DC pod obciążeniem"
        AnomalyType.CLIPPING -> "Normalne przy przewymiarowanych panelach; rozważ kąt/orientację lub większy falownik, jeśli strata jest duża"
        AnomalyType.LOW_BATTERY, AnomalyType.SOC_DROP -> "Ogranicz duże odbiory nocą/rano lub przesuń je na godziny z PV; sprawdź pojemność baterii"
        AnomalyType.SOC_JUMP -> "Sprawdź kalibrację SOC w BMS/falowniku (pełne naładowanie resetuje licznik)"
        AnomalyType.UNEXPECTED_GRID -> "Sprawdź priorytet źródła (np. SBU) i limit rozładowania baterii w falowniku – zmień ręcznie, jeśli trzeba"
        AnomalyType.RESTART -> "Sprawdź dziennik falownika i zasilanie; powtarzające się restarty zgłoś serwisowi"
        AnomalyType.TEMPERATURE -> "Zapewnij przepływ powietrza wokół falownika, sprawdź wentylator"
        AnomalyType.OVERLOAD -> "Rozłóż duże odbiory w czasie"
        AnomalyType.COMM_TIMEOUT, AnomalyType.COMM_INVALID_FRAME, AnomalyType.COMM_MISSING, AnomalyType.COMM_RECONNECT, AnomalyType.COMM_STALE ->
            "Sprawdź mostek RS232/RS485 (zasilanie, kabel, Wi-Fi)"
        AnomalyType.GRID_OUTAGE -> "Zanik sieci – sprawdź, czy dotyczy też sąsiadów (operator) i czy bateria wystarczyła na cały czas przerwy"
        AnomalyType.GRID_VOLTAGE, AnomalyType.GRID_FREQUENCY, AnomalyType.GRID_TRANSITIONS -> "Problem po stronie sieci – obserwuj; przy częstych zdarzeniach zgłoś operatorowi"
        else -> "Zweryfikuj odczyt z wyświetlaczem falownika"
    }

    /** Readable "how do you know?" path down to the raw source. */
    fun trace(d: ForensicDiagnosis, zone: ZoneId): List<String> = buildList {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        add("DIAGNOZA: ${d.title} (zdarzenie: ${d.observation.label}, przyczyna: ${d.certainty.label}, pewność ${(d.confidence.value * 100).roundToInt()}%)")
        add("KIEDY: ${f.format(d.anomaly.start)} – ${f.format(d.anomaly.end)}")
        add("DOWODY:"); addAll(d.evidence.render().lines().drop(1).map { "  $it" })
        add("PRZYCZYNY MOŻLIWE: " + d.rootCause.possible.joinToString { "${it.cause} (${it.status})" })
        add("WYELIMINOWANE: " + d.rootCause.eliminated.joinToString { "${it.cause} – ${it.reason}" }.ifEmpty { "brak" })
        add("PEWNOŚĆ: " + d.confidence.describe().joinToString(" · "))
        d.anomaly.samples.take(3).forEach { s ->
            add("DANE: ${f.format(s.time)} " + s.values.entries.take(6).joinToString { "${it.key.name}=${"%.2f".format(it.value)}" })
            s.raw?.let { r ->
                add("  ŹRÓDŁO: ${r.source}, ${r.locator}" + (r.rawTimestamp?.let { " · czas surowy „$it”" } ?: ""))
                if (r.fields.isNotEmpty()) add("  SUROWE POLA: " + r.fields.entries.take(6).joinToString { "${it.key}=${it.value}" })
                if (r.registers.isNotEmpty()) add("  REJESTRY: " + r.registers.entries.take(6).joinToString { "0x${it.key.toString(16).uppercase()}(${it.key})=${it.value}" })
            }
        }
    }
}

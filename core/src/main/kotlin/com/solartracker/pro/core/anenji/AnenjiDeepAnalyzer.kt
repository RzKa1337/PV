package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.diagnostics.ConversionEfficiency
import com.solartracker.pro.core.diagnostics.DegradationAssessment
import com.solartracker.pro.core.diagnostics.MpptAnalysis
import com.solartracker.pro.core.diagnostics.SoilingAssessment
import com.solartracker.pro.core.inverter.IssueType
import com.solartracker.pro.core.inverter.PlausibilityLimits
import com.solartracker.pro.core.inverter.TelemetryValidator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class DataQualityReport(
    val samples: Int,
    /** Share of the analysed period covered by data (gaps > 3 × typical interval are not covered). */
    val coverage: Double,
    val typicalIntervalSeconds: Long?,
    /** Share of samples with at least one value rejected by the telemetry validator. */
    val invalidShare: Double,
    val issueCounts: Map<IssueType, Int>,
    val missingByChannel: Map<Channel, Double>,
    /** Channels the source never provides (shown as N/A). */
    val missingChannels: List<Channel>,
    val origin: DataOrigin,
    val notes: List<String>,
)

/** Whole-period statistics of one channel. */
data class ChannelSummary(val channel: Channel, val samples: Int, val min: Double, val max: Double, val average: Double, val median: Double, val p95: Double)

data class EnergyTotals(val pvKwh: Double, val loadKwh: Double, val gridImportKwh: Double, val gridExportKwh: Double, val batteryChargeKwh: Double, val batteryDischargeKwh: Double)

data class DeepAnalysisInput(
    val device: String,
    val samples: List<AnalyzerSample>,
    val comm: List<CommRecord> = emptyList(),
    val snapshots: List<AnenjiSettingsSnapshot> = emptyList(),
    val battery: BatteryContext? = null,
    /** Inverter / PV charger power limit [W] (for clipping); null = unknown. */
    val pvLimitW: Double? = null,
    val zone: ZoneId,
    val now: Instant,
    val context: ContextProvider? = null,
    val limits: PlausibilityLimits = PlausibilityLimits(),
    val forecastAccuracyPercent: Double? = null,
    val mppt: MpptAnalysis? = null,
    val soiling: SoilingAssessment? = null,
    val degradation: DegradationAssessment? = null,
    val importIssues: List<String> = emptyList(),
)

/** "ANENJI_FULL_DIAGNOSTIC_REPORT". */
data class DeepAnalysisReport(
    val device: String,
    val generatedAt: Instant,
    val from: Instant?,
    val to: Instant?,
    val origin: DataOrigin,
    /** True when only simulator data was available – results are demonstrations, not a diagnosis. */
    val simulated: Boolean,
    val excludedSimulatorSamples: Int,
    val dataQuality: DataQualityReport,
    val configuration: AnenjiSettingsSnapshot?,
    val configurationChanges: AnenjiSettingsDiff,
    val summaries: List<ChannelSummary>,
    val energy: EnergyTotals,
    val loadProfileW: List<Double?>,
    val events: List<AnenjiEvent>,
    val patterns: List<EventPattern>,
    val communication: CommunicationReport,
    val anomalies: List<Finding>,
    val trends: List<ChannelStats>,
    val findings: List<Finding>,
    val health: SystemHealth,
    val mppt: MpptAnalysis?,
    val unresolved: List<String>,
    val readOnlyNotice: String = READ_ONLY,
) {
    companion object {
        const val READ_ONLY = "Analiza tylko do odczytu – aplikacja nie zmienia żadnych ustawień falownika."
    }
}

/**
 * "AnenjiDeepAnalyzer": analyses the whole available Anenji history (live database or imported log) together with
 * settings snapshots, communication records and model/weather context, and produces one explainable report.
 * READ ONLY by construction: it consumes data and returns findings.
 */
object AnenjiDeepAnalyzer {
    fun analyze(i: DeepAnalysisInput): DeepAnalysisReport {
        // REAL and SIMULATED never mix: simulator samples are dropped when real ones exist.
        val real = i.samples.filter { it.origin != DataOrigin.SIMULATOR }
        val simulated = real.isEmpty() && i.samples.isNotEmpty()
        val s = (if (simulated) i.samples else real).sortedBy { it.time }
        val origin = when { simulated -> DataOrigin.SIMULATOR; s.any { it.origin == DataOrigin.DEVICE } -> DataOrigin.DEVICE; else -> DataOrigin.IMPORTED }
        val excluded = i.samples.size - s.size
        val zone = i.zone

        val diff = AnenjiSettingsDiff.timeline(i.snapshots)
        val latest = i.snapshots.maxByOrNull { it.timestamp }
        val events = AnenjiEventLog.build(s, i.comm, diff.changes)
        val patterns = AnenjiEventLog.patterns(events, s, zone)
        val comm = AnenjiCommunicationAnalyzer.analyze(s, i.comm)
        val quality = dataQuality(s, origin, i.limits, i.importIssues)
        val expected: ((Instant) -> Double?)? = i.context?.let { c -> { t: Instant -> c.at(t)?.expectedPvW } }
        val findings = AnenjiConfigurationAdvisor.analyze(s, latest, i.battery, i.pvLimitW, zone, expected)
        val anomalies = anomalies(s, events, i.context, zone, quality)
        val trends = TrendAnalyzer.analyze(s, i.now, KEY_TREND_CHANNELS)
        val days = s.map { it.time.atZone(zone).toLocalDate() }.distinct().size
        val pvRatio = pvRatio(s, i.context, zone)
        val (eff, _) = ConversionEfficiency.median(s.map { AnalyzerSamples.toTelemetry(it) })
        val health = SystemHealthEngine.assess(HealthEvidence(
            pvPerformanceRatio = pvRatio, soiling = i.soiling, degradation = i.degradation, events = events, days = days, findings = findings,
            communication = comm, mppt = i.mppt, forecastAccuracyPercent = i.forecastAccuracyPercent, dataQuality = quality.takeIf { it.samples > 0 },
            hasBatteryData = s.any { it[Channel.SOC] != null || it[Channel.BATTERY_VOLTAGE] != null },
            hasPvData = s.any { it[Channel.PV_POWER] != null }, hasInverterData = s.isNotEmpty(),
            settingsKnown = latest?.values?.any { it.status != SettingStatus.NOT_AVAILABLE } == true,
            minSoc = s.mapNotNull { it[Channel.SOC] }.minOrNull(), maxInverterTempC = s.mapNotNull { it[Channel.INVERTER_TEMPERATURE] }.maxOrNull(),
            conversionEfficiency = eff,
        ))
        val unresolved = buildList {
            add("Mapa rejestrów Anenji niezweryfikowana na urządzeniu – wartości mogą mieć błędne skalowanie (REAL DEVICE VALIDATION REQUIRED)")
            if (latest == null || latest.values.all { it.status == SettingStatus.NOT_AVAILABLE }) add("Ustawienia falownika niedostępne – brak zweryfikowanych rejestrów ustawień i brak ich w logu")
            if (i.context == null) add("Brak modelu/pogody – nie można porównać PV z oczekiwaną produkcją")
            if (i.mppt == null || !i.mppt.available) add("Brak danych poszczególnych MPPT (N/A)")
            patterns.filter { it.likelyCause.startsWith("Przyczyna nieustalona") }.forEach { add("Nieustalona przyczyna: ${it.description} (${it.occurrences}×)") }
            quality.missingChannels.takeIf { it.isNotEmpty() }?.let { add("Niedostępne kanały: ${it.joinToString { c -> c.label }}") }
            if (simulated) add("Dane wyłącznie z symulatora – raport demonstracyjny, nie diagnoza urządzenia")
        }
        return DeepAnalysisReport(
            device = i.device, generatedAt = i.now, from = s.firstOrNull()?.time, to = s.lastOrNull()?.time, origin = origin, simulated = simulated,
            excludedSimulatorSamples = excluded, dataQuality = quality, configuration = latest, configurationChanges = diff,
            summaries = summaries(s), energy = energy(s), loadProfileW = loadProfile(s, zone), events = events, patterns = patterns,
            communication = comm, anomalies = anomalies, trends = trends, findings = findings, health = health, mppt = i.mppt, unresolved = unresolved,
        )
    }

    val KEY_TREND_CHANNELS = listOf(Channel.PV_POWER, Channel.LOAD_POWER, Channel.SOC, Channel.BATTERY_VOLTAGE, Channel.BATTERY_TEMPERATURE,
        Channel.INVERTER_TEMPERATURE, Channel.GRID_POWER, Channel.GRID_VOLTAGE, Channel.AC_VOLTAGE, Channel.AC_FREQUENCY)

    /** Channels a hybrid inverter normally reports; absent ones are listed as N/A. */
    val EXPECTED_CHANNELS = listOf(Channel.PV_POWER, Channel.PV_VOLTAGE, Channel.BATTERY_VOLTAGE, Channel.SOC, Channel.LOAD_POWER, Channel.AC_VOLTAGE, Channel.INVERTER_TEMPERATURE)

    fun dataQuality(s: List<AnalyzerSample>, origin: DataOrigin, limits: PlausibilityLimits, notes: List<String> = emptyList()): DataQualityReport {
        val interval = AnenjiEventLog.typicalInterval(s)
        val span = if (s.size >= 2) Duration.between(s.first().time, s.last().time).toMillis().toDouble() else 0.0
        val covered = if (interval == null) 0.0 else s.zipWithNext().sumOf { (a, b) -> Duration.between(a.time, b.time).let { if (it > interval.multipliedBy(3)) 0L else it.toMillis() } }.toDouble()
        val validator = TelemetryValidator(limits)
        val counts = mutableMapOf<IssueType, Int>()
        var invalid = 0
        var prev: com.solartracker.pro.core.inverter.InverterTelemetry? = null
        for (x in s) {
            val t = AnalyzerSamples.toTelemetry(x)
            val v = validator.validate(t, prev)
            if (v.invalidFields.isNotEmpty()) invalid++
            v.issues.forEach { counts[it.type] = (counts[it.type] ?: 0) + 1 }
            prev = v.sanitized
        }
        val present = s.flatMap { it.values.keys }.toSet()
        return DataQualityReport(
            samples = s.size, coverage = if (span > 0) (covered / span).coerceIn(0.0, 1.0) else if (s.isNotEmpty()) 1.0 else 0.0,
            typicalIntervalSeconds = interval?.seconds, invalidShare = if (s.isEmpty()) 0.0 else invalid.toDouble() / s.size, issueCounts = counts,
            missingByChannel = present.associateWith { c -> s.count { it[c] == null }.toDouble() / s.size }.filterValues { it > 0 },
            missingChannels = EXPECTED_CHANNELS.filter { it !in present }, origin = origin, notes = notes,
        )
    }

    fun summaries(s: List<AnalyzerSample>): List<ChannelSummary> = Channel.entries.mapNotNull { c ->
        val v = s.mapNotNull { it[c] }
        if (v.isEmpty()) null else ChannelSummary(c, v.size, v.min(), v.max(), v.average(), Stats.median(v)!!, Stats.percentile(v, 95.0)!!)
    }

    /** Energy integrated from power (gaps > 15 min not bridged – no invented energy). */
    fun energy(s: List<AnalyzerSample>): EnergyTotals {
        fun integ(f: (AnalyzerSample) -> Double?): Double = s.zipWithNext().sumOf { (a, b) ->
            val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            if (dt <= 0 || dt > 0.25) 0.0 else (f(a) ?: 0.0) * dt / 1000
        }
        return EnergyTotals(
            integ { it[Channel.PV_POWER]?.coerceAtLeast(0.0) }, integ { it[Channel.LOAD_POWER]?.coerceAtLeast(0.0) },
            integ { it[Channel.GRID_POWER]?.coerceAtLeast(0.0) }, integ { it[Channel.GRID_POWER]?.let { g -> (-g).coerceAtLeast(0.0) } },
            integ { it[Channel.BATTERY_POWER]?.coerceAtLeast(0.0) }, integ { it[Channel.BATTERY_POWER]?.let { b -> (-b).coerceAtLeast(0.0) } },
        )
    }

    /** Average load per local hour (null = no data in that hour). */
    fun loadProfile(s: List<AnalyzerSample>, zone: ZoneId): List<Double?> {
        val byHour = s.mapNotNull { x -> x[Channel.LOAD_POWER]?.let { x.time.atZone(zone).hour to it } }.groupBy({ it.first }, { it.second })
        return (0 until 24).map { h -> byHour[h]?.average() }
    }

    /** Median measured/model PV energy over days with enough model energy; null without context. */
    fun pvRatio(s: List<AnalyzerSample>, context: ContextProvider?, zone: ZoneId): Double? {
        val c = context ?: return null
        val ratios = s.groupBy { it.time.atZone(zone).toLocalDate() }.values.mapNotNull { day ->
            var real = 0.0; var model = 0.0
            for ((a, b) in day.zipWithNext()) {
                val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
                if (dt <= 0 || dt > 0.25) continue
                val ctx = c.at(a.time) ?: continue
                val e = ctx.expectedPvW ?: continue
                if ((ctx.clearSkyIndex ?: 0.0) < 0.85) continue
                val p = a[Channel.PV_POWER] ?: continue
                real += p * dt; model += e * dt
            }
            if (model > 500) real / model else null
        }
        return Stats.median(ratios)
    }

    /** History anomalies: validator issues, restarts, sustained PV deviation vs model, overnight SOC collapse. */
    fun anomalies(s: List<AnalyzerSample>, events: List<AnenjiEvent>, context: ContextProvider?, zone: ZoneId, quality: DataQualityReport): List<Finding> = buildList {
        quality.issueCounts.filter { it.key != IssueType.ZERO_PV && it.value > 0 }.forEach { (type, n) ->
            add(Finding("Anomalia danych: ${type.label}", if (n > 10) FindingSeverity.WARNING else FindingSeverity.INFO, "Walidator odrzucił wartości ($n odczytów)",
                listOf("Wystąpień: $n z ${quality.samples} odczytów"), 0.8, "Odrzucone wartości nie trafiają do statystyk i kalibracji"))
        }
        quality.issueCounts[IssueType.ZERO_PV]?.takeIf { it > 0 }?.let { n ->
            add(Finding("Zerowa produkcja PV przy słońcu", FindingSeverity.WARNING, "PV = 0 W, gdy model oczekuje produkcji", listOf("Odczytów: $n"), 0.6,
                "Utracona produkcja w tych chwilach", possibleCauses = listOf("wyłączony rozłącznik DC", "awaria/zabezpieczenie PV", "śnieg", "błąd odczytu")))
        }
        val restarts = events.filter { it.description.startsWith("Restart") }
        if (restarts.isNotEmpty()) add(Finding("Restarty urządzenia", if (restarts.size >= 3) FindingSeverity.WARNING else FindingSeverity.INFO,
            "Wykryto ponowne uruchomienie falownika", restarts.take(5).map { "${it.start.atZone(zone).toLocalDateTime().withNano(0)}: ${it.description.substringAfter(": ")}" },
            0.7, "Przerwy w zasilaniu odbiorów są możliwe", possibleCauses = listOf("zanik zasilania", "zadziałanie zabezpieczenia", "ręczne wyłączenie")))
        context?.let { c ->
            // Days on which clear-sky PV stayed > 20 % below the model.
            val bad = s.groupBy { it.time.atZone(zone).toLocalDate() }.mapNotNull { (d, day) ->
                val pairs = day.mapNotNull { x -> c.at(x.time)?.takeIf { (it.clearSkyIndex ?: 0.0) >= 0.85 && (it.expectedPvW ?: 0.0) > 300 }?.let { ctx -> x[Channel.PV_POWER]?.let { it to ctx.expectedPvW!! } } }
                if (pairs.size < 10) null else {
                    val r = pairs.sumOf { it.first } / pairs.sumOf { it.second }
                    if (r < 0.8) d to r else null
                }
            }
            if (bad.isNotEmpty()) add(Finding("PV poniżej modelu w pogodne dni", FindingSeverity.WARNING, "Produkcja w dni pogodne była ponad 20% niższa od modelu",
                bad.take(10).map { (d, r) -> "$d: ${((r - 1) * 100).toInt()}%" }, (0.5 + 0.05 * bad.size).coerceAtMost(0.85), "Utracona produkcja w ${bad.size} dniach",
                possibleCauses = listOf("zabrudzenie", "zacienienie", "awaria stringu/MPPT", "błędna konfiguracja instalacji w aplikacji")))
        }
    }
}

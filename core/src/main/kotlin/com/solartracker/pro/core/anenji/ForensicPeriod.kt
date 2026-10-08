package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ln

/** Data availability of an analysed window – shown instead of a result that the data cannot support. */
enum class DataStatus(val label: String) { OK("OK"), NO_DATA("BRAK DANYCH"), STALE("DANE NIEAKTUALNE"), PARTIAL_ANALYSIS("ANALIZA CZĘŚCIOWA") }

data class WindowStatus(val status: DataStatus, val coverage: Double, val samples: Int, val lastSample: Instant?, val note: String)

object DataStatusEvaluator {
    /** Below this coverage the analysis is partial. */
    const val MIN_COVERAGE = 0.8

    fun evaluate(samples: List<AnalyzerSample>, from: Instant, to: Instant, now: Instant): WindowStatus {
        val end = minOf(to, now)
        val s = samples.filter { !it.time.isBefore(from) && !it.time.isAfter(end) }.sortedBy { it.time }
        if (s.isEmpty() || !end.isAfter(from)) return WindowStatus(DataStatus.NO_DATA, 0.0, 0, null, "Brak próbek w wybranym okresie")
        val interval = AnenjiEventLog.typicalInterval(s) ?: Duration.ofMinutes(5)
        val gapLimit = interval.multipliedBy(3)
        val covered = s.zipWithNext().sumOf { (a, b) -> Duration.between(a.time, b.time).let { if (it > gapLimit) 0L else it.toMillis() } } + interval.toMillis()
        val coverage = (covered.toDouble() / Duration.between(from, end).toMillis()).coerceIn(0.0, 1.0)
        val last = s.last().time
        val staleAfter = maxOf(Duration.ofMinutes(30), interval.multipliedBy(3))
        return when {
            !to.isBefore(now) && Duration.between(last, now) > staleAfter ->
                WindowStatus(DataStatus.STALE, coverage, s.size, last, "Ostatnia próbka ${Duration.between(last, now).toMinutes()} min temu")
            coverage < MIN_COVERAGE -> WindowStatus(DataStatus.PARTIAL_ANALYSIS, coverage, s.size, last, "Dane pokrywają ${(coverage * 100).toInt()}% okresu")
            else -> WindowStatus(DataStatus.OK, coverage, s.size, last, "Pokrycie ${(coverage * 100).toInt()}%")
        }
    }
}

data class IncidentSnapshot(val offsetMinutes: Int, val time: Instant, val sample: AnalyzerSample?) {
    val status: DataStatus get() = if (sample == null) DataStatus.NO_DATA else DataStatus.OK
}

/** "WHAT HAPPENED?" around one moment: state at −60…+60 min, timeline, the diagnoses overlapping it. */
data class IncidentReconstruction(
    val report: IncidentReport,
    val snapshots: List<IncidentSnapshot>,
    val diagnoses: List<ForensicDiagnosis>,
    val status: WindowStatus,
)

object IncidentReconstructor {
    val OFFSETS = listOf(-60, -30, -15, 0, 15, 30, 60)

    fun reconstruct(at: Instant, f: ForensicContext, diagnoses: List<ForensicDiagnosis>, now: Instant = at.plus(Duration.ofHours(1))): IncidentReconstruction {
        val from = at.minus(Duration.ofMinutes(60)); val to = at.plus(Duration.ofMinutes(60))
        val window = f.samples.filter { !it.time.isBefore(from.minus(Duration.ofMinutes(10))) && !it.time.isAfter(to.plus(Duration.ofMinutes(10))) }
        val tolerance = maxOf(Duration.ofMinutes(5), (AnenjiEventLog.typicalInterval(window) ?: Duration.ofMinutes(5)).multipliedBy(3).dividedBy(2))
        val snaps = OFFSETS.map { m ->
            val t = at.plus(Duration.ofMinutes(m.toLong()))
            IncidentSnapshot(m, t, window.minByOrNull { abs(Duration.between(it.time, t).seconds) }?.takeIf { abs(Duration.between(it.time, t).seconds) <= tolerance.seconds })
        }
        val related = diagnoses.filter { !it.anomaly.end.isBefore(from) && !it.anomaly.start.isAfter(to) }
            .sortedWith(compareByDescending<ForensicDiagnosis> { it.severity }.thenBy { it.observation.ordinal }.thenByDescending { it.confidence.value })
        return IncidentReconstruction(IncidentAnalyzer.analyze(at, f.samples, f.events, f.zone, f.context, Duration.ofMinutes(60)), snaps, related,
            DataStatusEvaluator.evaluate(f.samples, from, to, now))
    }
}

enum class ForensicPeriod(val label: String) { TODAY("Dziś"), YESTERDAY("Wczoraj"), D7("7 dni"), D30("30 dni"), D90("90 dni"), CUSTOM("Własny") }

/** One problem type in a period, aggregated over its occurrences. */
data class RankedIssue(
    val type: AnomalyType,
    val title: String,
    val occurrences: Int,
    val energyKwh: Double?,
    val cost: Double?,
    val downtime: Duration,
    val confidence: Double,
    val certainty: Certainty,
    val severity: DiagnosisSeverityLevel,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val diagnoses: List<ForensicDiagnosis>,
    val score: Double,
)

data class PeriodReport(
    val period: ForensicPeriod,
    val from: Instant,
    val to: Instant,
    val status: WindowStatus,
    val simulated: Boolean,
    val diagnoses: List<ForensicDiagnosis>,
    val ranking: List<RankedIssue>,
    val bySeverity: Map<DiagnosisSeverityLevel, Int>,
    val byCertainty: Map<Certainty, Int>,
    /** Sum of computable energy losses; null when none could be computed (N/A, not 0). */
    val lostKwh: Double?,
    val cost: Double?,
) {
    val mostImportant: RankedIssue? get() = ranking.firstOrNull()
}

/** "ForensicPeriodAnalyzer": all diagnoses of a period, grouped and ranked by severity × certainty × frequency × impact. */
object ForensicPeriodAnalyzer {
    fun range(period: ForensicPeriod, now: Instant, zone: ZoneId, custom: Pair<Instant, Instant>? = null): Pair<Instant, Instant> {
        val today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        return when (period) {
            ForensicPeriod.TODAY -> today to now
            ForensicPeriod.YESTERDAY -> today.minus(Duration.ofDays(1)) to today
            ForensicPeriod.D7 -> now.minus(Duration.ofDays(7)) to now
            ForensicPeriod.D30 -> now.minus(Duration.ofDays(30)) to now
            ForensicPeriod.D90 -> now.minus(Duration.ofDays(90)) to now
            ForensicPeriod.CUSTOM -> custom ?: (now.minus(Duration.ofDays(1)) to now)
        }
    }

    /**
     * Detection runs on the period (plus 1 h of lead-in for edge effects); the baseline keeps the whole history so a short
     * period is still compared with the installation's normal behaviour.
     */
    fun analyze(f: ForensicContext, period: ForensicPeriod, from: Instant, to: Instant, now: Instant = to): PeriodReport {
        val lead = from.minus(Duration.ofHours(1))
        fun inRange(t: Instant) = !t.isBefore(lead) && !t.isAfter(to)
        val sub = f.copy(samples = f.samples.filter { inRange(it.time) }, events = f.events.filter { inRange(it.start) }, comm = f.comm.filter { inRange(it.time) })
        val status = DataStatusEvaluator.evaluate(f.samples, from, to, now)
        val diagnoses = if (sub.samples.isEmpty() && sub.comm.isEmpty()) emptyList()
        else ForensicDiagnosisBuilder.build(ForensicAnomalyDetector.detect(sub).filter { !it.end.isBefore(from) && !it.start.isAfter(to) }, sub)
        val ranking = diagnoses.groupBy { it.anomaly.type }.map { (type, list) ->
            val energy = list.mapNotNull { it.impact.energyKwh }.takeIf { it.isNotEmpty() }?.sum()
            val cost = list.mapNotNull { it.impact.cost }.takeIf { it.isNotEmpty() }?.sum()
            val best = list.minBy { it.certainty.ordinal }
            val severity = list.maxOf { it.severity }
            val confidence = list.map { it.confidence.value }.average()
            val score = (severity.ordinal + 1) * (Certainty.entries.size - best.certainty.ordinal) * (1 + ln(list.size.toDouble())) * confidence + (energy ?: 0.0)
            RankedIssue(type, best.title, list.size, energy, cost, list.mapNotNull { it.impact.downtime }.fold(Duration.ZERO, Duration::plus), confidence,
                best.certainty, severity, list.minOf { it.anomaly.start }, list.maxOf { it.anomaly.end }, list, score)
        }.sortedByDescending { it.score }
        val losses = diagnoses.mapNotNull { it.impact.energyKwh }
        val costs = diagnoses.mapNotNull { it.impact.cost }
        return PeriodReport(period, from, to, status, sub.samples.isNotEmpty() && sub.samples.all { it.origin == DataOrigin.SIMULATOR }, diagnoses, ranking,
            DiagnosisSeverityLevel.entries.associateWith { s -> diagnoses.count { it.severity == s } },
            Certainty.entries.associateWith { c -> diagnoses.count { it.certainty == c } },
            losses.takeIf { it.isNotEmpty() }?.sum(), costs.takeIf { it.isNotEmpty() }?.sum())
    }
}

data class LongTrend(
    val metric: String,
    val unit: String,
    /** Week start (Monday) → value; weeks without enough data are absent, never interpolated. */
    val weekly: List<Pair<LocalDate, Double>>,
    val changePerMonth: Double?,
    val direction: TrendDirection,
    val sufficient: Boolean,
    val note: String,
)

/**
 * 90-day trends of the installation: PV vs model, apparent battery capacity, conversion efficiency, alarms, communication
 * errors, grid dependency, night consumption and daily load. A trend is reported only with ≥ [MIN_WEEKS] weeks of data.
 */
object LongTrendAnalyzer {
    const val MIN_WEEKS = 3
    private const val MIN_DAY_SAMPLES = 100
    /** A week counts only when most of it has data (no partial-week medians). */
    private const val MIN_DAYS_PER_WEEK = 4

    fun analyze(f: ForensicContext, now: Instant): List<LongTrend> {
        val from = now.minus(Duration.ofDays(90))
        val s = f.samples.filter { !it.time.isBefore(from) && !it.time.isAfter(now) }.sortedBy { it.time }
        val days = s.groupBy { it.time.atZone(f.zone).toLocalDate() }.filterValues { it.size >= MIN_DAY_SAMPLES }
        fun week(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun weekly(perDay: (LocalDate, List<AnalyzerSample>) -> Double?) = days.mapNotNull { (d, x) -> perDay(d, x)?.let { week(d) to it } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size >= MIN_DAYS_PER_WEEK }.mapValues { Stats.median(it.value)!! }.toSortedMap().toList()
        val events = f.events.filter { !it.start.isBefore(from) }
        val comm = f.comm.filter { !it.time.isBefore(from) }
        return listOf(
            trend("PV względem modelu", "%", if (f.context == null) emptyList() else weekly { _, x -> AnenjiDeepAnalyzer.pvRatio(x, f.context, f.zone)?.times(100) },
                if (f.context == null) "Brak modelu PV – trend niedostępny" else "dni z indeksem czystego nieba ≥ 0,85"),
            trend("Pozorna pojemność baterii", "kWh", weekly { _, x -> apparentCapacityKwh(x) }, "z ładowania: Σ energii / Σ przyrostu SOC – zależy od dokładności SOC"),
            trend("Sprawność konwersji", "%", weekly { _, x ->
                val (e, n) = com.solartracker.pro.core.diagnostics.ConversionEfficiency.median(x.map { AnalyzerSamples.toTelemetry(it) }); if (e != null && n >= 10) e * 100 else null
            }, "mediana dzienna"),
            trend("Alarmy", "na tydzień", weeklyCount(events.filter { it.severity != EventSeverity.INFO }.map { it.start }, days.keys, ::week, f.zone), "ostrzeżenia i awarie"),
            trend("Błędy komunikacji", "na tydzień", weeklyCount(comm.filter { !it.ok }.map { it.time }, days.keys, ::week, f.zone), "nieudane odpytania"),
            trend("Zależność od sieci", "%", weekly { _, x ->
                val load = SystemBaselineEngine.energyKwh(x, Channel.LOAD_POWER); if (load < 0.1) null else SystemBaselineEngine.energyKwh(x, Channel.GRID_POWER) / load * 100
            }, "energia z sieci / zużycie"),
            trend("Zużycie nocne", "kWh", weekly { _, x -> x.filter { val h = it.time.atZone(f.zone).hour; h >= 22 || h < 6 }.takeIf { it.size >= 20 }?.let { SystemBaselineEngine.energyKwh(it, Channel.LOAD_POWER) } },
                "22:00–06:00"),
            trend("Zużycie dobowe", "kWh", weekly { _, x -> SystemBaselineEngine.energyKwh(x, Channel.LOAD_POWER).takeIf { it > 0 } }, "profil obciążenia"),
        )
    }

    /** kWh per 100 % SOC while charging; null without SOC/battery power or with too little SOC movement. */
    fun apparentCapacityKwh(day: List<AnalyzerSample>): Double? {
        var energy = 0.0; var soc = 0.0
        for ((a, b) in day.sortedBy { it.time }.zipWithNext()) {
            val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            if (dt <= 0 || dt > 0.25) continue
            val p = a[Channel.BATTERY_POWER] ?: continue
            val sa = a[Channel.SOC] ?: continue; val sb = b[Channel.SOC] ?: continue
            if (p > 50 && sb > sa && sb < 99.5) { energy += p * dt / 1000; soc += sb - sa }
        }
        return if (soc >= 10) energy / soc * 100 else null
    }

    private fun weeklyCount(times: List<Instant>, days: Set<LocalDate>, week: (LocalDate) -> LocalDate, zone: ZoneId): List<Pair<LocalDate, Double>> {
        val weeks = days.groupBy(week).filterValues { it.size >= MIN_DAYS_PER_WEEK }
        val counts = times.groupingBy { week(it.atZone(zone).toLocalDate()) }.eachCount()
        return weeks.keys.sorted().map { w -> w to (counts[w] ?: 0) * 7.0 / weeks[w]!!.size }
    }

    private fun trend(metric: String, unit: String, weekly: List<Pair<LocalDate, Double>>, note: String): LongTrend {
        if (weekly.size < MIN_WEEKS) return LongTrend(metric, unit, weekly, null, TrendDirection.UNKNOWN, false,
            "ZA MAŁO DANYCH (${weekly.size} tyg., potrzeba $MIN_WEEKS) – $note")
        val x = weekly.map { java.time.temporal.ChronoUnit.DAYS.between(weekly.first().first, it.first) / 7.0 }
        val v = weekly.map { it.second }
        val slope = Stats.slope(x, v)
        val perMonth = slope?.times(30.0 / 7)
        val mean = v.average()
        val span = x.last()
        val direction = when {
            slope == null -> TrendDirection.UNKNOWN
            abs(slope * span) < maxOf(0.05 * abs(mean), 1e-6) -> TrendDirection.STABLE
            slope > 0 -> TrendDirection.RISING
            else -> TrendDirection.FALLING
        }
        return LongTrend(metric, unit, weekly, perMonth, direction, true, note)
    }
}

data class ConfigImpact(
    val change: SettingChange,
    /** Diagnoses that started within [ConfigurationForensics.WINDOW] after the change. */
    val incidentsAfter: List<ForensicDiagnosis>,
    /** Daily metric medians in the days before/after (null = no data). */
    val before: Map<String, Double?>,
    val after: Map<String, Double?>,
    val note: String = "Korelacja w czasie – nie dowód przyczyny",
)

/** "Configuration forensics": which setting changes preceded incidents and how daily performance looked before vs after. */
object ConfigurationForensics {
    val WINDOW: Duration = Duration.ofDays(3)

    fun analyze(changes: List<SettingChange>, f: ForensicContext, diagnoses: List<ForensicDiagnosis>): List<ConfigImpact> = changes.map { c ->
        val after = diagnoses.filter { !it.anomaly.start.isBefore(c.seenBetween) && it.anomaly.start.isBefore(c.seenAt.plus(WINDOW)) }
        ConfigImpact(c, after, metrics(f, c.seenBetween.minus(WINDOW), c.seenBetween), metrics(f, c.seenAt, c.seenAt.plus(WINDOW)))
    }

    private fun metrics(f: ForensicContext, from: Instant, to: Instant): Map<String, Double?> {
        val days = f.samples.filter { !it.time.isBefore(from) && it.time.isBefore(to) }.groupBy { it.time.atZone(f.zone).toLocalDate() }.values.filter { it.size >= 100 }
        fun med(g: (List<AnalyzerSample>) -> Double?) = Stats.median(days.mapNotNull(g))
        return linkedMapOf(
            "Min. SOC [%]" to med { d -> d.mapNotNull { it[Channel.SOC] }.minOrNull() },
            "Energia z sieci [kWh/d]" to med { SystemBaselineEngine.energyKwh(it, Channel.GRID_POWER) },
            "PV [kWh/d]" to med { SystemBaselineEngine.energyKwh(it, Channel.PV_POWER) },
            "Zużycie [kWh/d]" to med { SystemBaselineEngine.energyKwh(it, Channel.LOAD_POWER) },
            "Alarmy [/d]" to if (days.isEmpty()) null else f.events.count { e -> e.severity != EventSeverity.INFO && !e.start.isBefore(from) && e.start.isBefore(to) }.toDouble() / days.size,
        )
    }
}

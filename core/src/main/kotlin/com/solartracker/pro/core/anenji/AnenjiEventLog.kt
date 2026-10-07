package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.core.inverter.SmgRegisterMap
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class EventCategory(val label: String) {
    INFO("Informacja"), WARNING("Ostrzeżenie"), FAULT("Awaria"), COMMUNICATION("Komunikacja"), BATTERY("Bateria"),
    PV("PV"), GRID("Sieć"), INVERTER("Falownik"), TEMPERATURE("Temperatura"),
}

enum class EventSeverity { INFO, WARNING, CRITICAL }

/** One event with its duration (end = null while still active at the end of the data). */
data class AnenjiEvent(
    val start: Instant,
    val end: Instant?,
    val category: EventCategory,
    val severity: EventSeverity,
    val code: Int?,
    val rawValue: String?,
    val description: String,
) {
    val duration: Duration? get() = end?.let { Duration.between(start, it) }
    /** Groups repeated events of the same kind. */
    val kindKey: String get() = "${category.name}:${code ?: description}"
}

/** "FAULT ANALYSIS": frequency, duration, repeatability, conditions before and after one kind of event. */
data class EventPattern(
    val category: EventCategory,
    val code: Int?,
    val description: String,
    val occurrences: Int,
    val totalDuration: Duration,
    val averageDuration: Duration?,
    /** Best 90-minute window of the day and the share of occurrences inside it. */
    val dominantWindow: String?,
    val windowShare: Double,
    val precedingSocMedian: Double?,
    val precedingLoadMedianW: Double?,
    val precedingPvMedianW: Double?,
    val precedingInverterTempMedian: Double?,
    /** Share of occurrences followed by grid import within 15 minutes. */
    val followedByGridShare: Double?,
    val likelyCause: String,
    val confidence: Double,
)

/**
 * Builds the event history from samples (warning/fault bits, mode changes, grid import, restarts), communication
 * records (failures, data gaps) and configuration changes, and analyses patterns of repeated events.
 */
object AnenjiEventLog {
    const val GRID_IMPORT_W = 50.0

    fun build(samples: List<AnalyzerSample>, comm: List<CommRecord> = emptyList(), settingsChanges: List<SettingChange> = emptyList(),
              gapFactor: Double = 3.0): List<AnenjiEvent> {
        val s = samples.sortedBy { it.time }
        val out = mutableListOf<AnenjiEvent>()
        val interval = typicalInterval(s)

        // Warning / fault bits as intervals.
        fun bitEvents(codes: (AnalyzerSample) -> Set<Int>, names: Map<Int, String>, fault: Boolean) {
            val open = mutableMapOf<Int, Instant>()
            var prev: AnalyzerSample? = null
            for (x in s) {
                val gap = prev != null && interval != null && Duration.between(prev.time, x.time) > interval.multipliedBy(gapFactor.toLong().coerceAtLeast(1))
                val now = codes(x)
                // Close codes that disappeared (or whose continuity was broken by a data gap).
                for (c in open.keys.toList()) if (c !in now || gap) {
                    out += bitEvent(open.remove(c)!!, prev!!.time, c, names, fault)
                }
                for (c in now) if (c !in open) open[c] = x.time
                prev = x
            }
            open.forEach { (c, start) -> out += bitEvent(start, null, c, names, fault) }
        }
        bitEvents({ it.warnings }, SmgRegisterMap.WARNINGS, fault = false)
        bitEvents({ it.faults }, SmgRegisterMap.FAULTS, fault = true)

        // Mode changes, grid import start, restarts.
        for ((a, b) in s.zipWithNext()) {
            if (a.mode != null && b.mode != null && a.mode != b.mode) {
                out += AnenjiEvent(b.time, null, if (b.mode == OperatingMode.FAULT) EventCategory.FAULT else EventCategory.INFO,
                    if (b.mode == OperatingMode.FAULT) EventSeverity.CRITICAL else EventSeverity.INFO, null, "${a.mode}→${b.mode}",
                    "Zmiana trybu pracy: ${a.mode.name} → ${b.mode.name}")
            }
            val ga = a[Channel.GRID_POWER]; val gb = b[Channel.GRID_POWER]
            if (ga != null && gb != null && ga <= GRID_IMPORT_W && gb > GRID_IMPORT_W) {
                out += AnenjiEvent(b.time, null, EventCategory.GRID, EventSeverity.INFO, null, "%.0f W".format(gb), "Rozpoczęcie poboru z sieci")
            }
            restartReason(a, b, interval)?.let { out += AnenjiEvent(b.time, null, EventCategory.INVERTER, EventSeverity.WARNING, null, null, "Restart urządzenia (wykryty): $it") }
        }

        // Communication: runs of failed polls and data gaps.
        var failStart: CommRecord? = null
        var lastFail: CommRecord? = null
        for (r in comm.sortedBy { it.time }) {
            if (!r.ok) { if (failStart == null) failStart = r; lastFail = r }
            else if (failStart != null) {
                out += AnenjiEvent(failStart.time, r.time, EventCategory.COMMUNICATION, EventSeverity.WARNING, null, failStart.error?.name,
                    "Utrata komunikacji (${failStart.error?.label ?: "błąd"})")
                failStart = null
            }
        }
        failStart?.let { out += AnenjiEvent(it.time, null, EventCategory.COMMUNICATION, EventSeverity.WARNING, null, it.error?.name, "Utrata komunikacji (${it.error?.label ?: "błąd"}) – trwa do końca danych") }
        if (interval != null) for ((a, b) in s.zipWithNext()) {
            val gap = Duration.between(a.time, b.time)
            if (gap > interval.multipliedBy(gapFactor.toLong().coerceAtLeast(1)) && gap > Duration.ofMinutes(2)) {
                out += AnenjiEvent(a.time, b.time, EventCategory.COMMUNICATION, EventSeverity.INFO, null, "${gap.toMinutes()} min", "Brak danych (luka ${gap.toMinutes()} min)")
            }
        }
        settingsChanges.forEach { c ->
            out += AnenjiEvent(c.seenAt, null, EventCategory.INFO, EventSeverity.INFO, null, "${c.old.display}→${c.new.display}",
                "Zmiana konfiguracji: ${c.key.label} ${c.old.display} → ${c.new.display}")
        }
        return out.sortedBy { it.start }
    }

    private fun bitEvent(start: Instant, end: Instant?, code: Int, names: Map<Int, String>, fault: Boolean): AnenjiEvent {
        val name = names[code] ?: "Kod producenta (bit $code)"
        val n = name.lowercase()
        val category = when {
            fault && ("bateri" in n) -> EventCategory.BATTERY
            "bateri" in n || "bms" in n -> EventCategory.BATTERY
            "pv" in n -> EventCategory.PV
            "sieci" in n -> EventCategory.GRID
            "przegrz" in n || "temperatur" in n || "wentylator" in n -> EventCategory.TEMPERATURE
            fault -> EventCategory.FAULT
            else -> EventCategory.INVERTER
        }
        return AnenjiEvent(start, end, category, if (fault) EventSeverity.CRITICAL else EventSeverity.WARNING, code,
            "bit $code", (if (fault) "Awaria: " else "Ostrzeżenie: ") + name)
    }

    /** Uptime or the lifetime energy counter going backwards, or power-on after a gap. */
    private fun restartReason(a: AnalyzerSample, b: AnalyzerSample, interval: Duration?): String? {
        val ua = a[Channel.UPTIME]; val ub = b[Channel.UPTIME]
        if (ua != null && ub != null && ub + 1 < ua) return "licznik czasu pracy spadł z ${ua.toLong()} s do ${ub.toLong()} s"
        val ea = a[Channel.PV_ENERGY_TOTAL]; val eb = b[Channel.PV_ENERGY_TOTAL]
        if (ea != null && eb != null && eb < ea - 0.5) return "licznik energii łącznej spadł (${"%.1f".format(ea)} → ${"%.1f".format(eb)} kWh)"
        val gap = Duration.between(a.time, b.time)
        if (b.mode == OperatingMode.POWER_ON && a.mode != OperatingMode.POWER_ON && interval != null && gap > interval.multipliedBy(3)) return "tryb POWER_ON po przerwie w danych"
        return null
    }

    fun typicalInterval(s: List<AnalyzerSample>): Duration? {
        val d = s.zipWithNext { a, b -> Duration.between(a.time, b.time).toMillis().toDouble() }.filter { it > 0 }
        return Stats.median(d)?.let { Duration.ofMillis(it.toLong()) }
    }

    /** Patterns of repeated events with likely causes (deterministic rules; never a certainty). */
    fun patterns(events: List<AnenjiEvent>, samples: List<AnalyzerSample>, zone: ZoneId, minOccurrences: Int = 2): List<EventPattern> {
        val s = samples.sortedBy { it.time }
        fun window(from: Instant, to: Instant) = s.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        return events.filter { it.category != EventCategory.INFO || it.description.startsWith("Rozpoczęcie") }
            .groupBy { it.kindKey }.values.filter { it.size >= minOccurrences }.map { group ->
                val first = group.first()
                val minutes = group.map { it.start.atZone(zone).let { z -> z.hour * 60 + z.minute } }
                // Best 90-minute window (wrapping midnight).
                val (bestStart, bestCount) = (0 until 1440 step 15).map { w ->
                    w to minutes.count { m -> ((m - w + 1440) % 1440) < 90 }
                }.maxBy { it.second }
                val share = bestCount.toDouble() / group.size
                val before = group.flatMap { window(it.start.minus(Duration.ofMinutes(15)), it.start) }
                val soc = Stats.median(before.mapNotNull { it[Channel.SOC] })
                val load = Stats.median(before.mapNotNull { it[Channel.LOAD_POWER] })
                val pv = Stats.median(before.mapNotNull { it[Channel.PV_POWER] })
                val temp = Stats.median(before.mapNotNull { it[Channel.INVERTER_TEMPERATURE] })
                val gridAfter = group.map { e -> window(e.start, e.start.plus(Duration.ofMinutes(15))).any { (it[Channel.GRID_POWER] ?: 0.0) > GRID_IMPORT_W } }
                val hasGrid = s.any { it[Channel.GRID_POWER] != null }
                val durations = group.mapNotNull { it.duration }
                val total = durations.fold(Duration.ZERO) { a, b -> a.plus(b) }
                val (cause, causeConf) = cause(first, soc, load, pv, temp, bestStart, share)
                EventPattern(
                    first.category, first.code, first.description, group.size, total,
                    if (durations.isEmpty()) null else total.dividedBy(durations.size.toLong()),
                    "%02d:%02d–%02d:%02d".format(bestStart / 60, bestStart % 60, ((bestStart + 90) % 1440) / 60, (bestStart + 90) % 60).takeIf { share >= 0.5 },
                    share, soc, load, pv, temp, if (hasGrid) gridAfter.count { it }.toDouble() / group.size else null, cause,
                    (causeConf * (0.6 + 0.4 * minOf(1.0, group.size / 10.0))).coerceIn(0.1, 0.95),
                )
            }.sortedByDescending { it.occurrences }
    }

    private fun cause(e: AnenjiEvent, soc: Double?, load: Double?, pv: Double?, temp: Double?, windowStartMin: Int, share: Double): Pair<String, Double> {
        val d = e.description.lowercase()
        val morning = windowStartMin in (4 * 60)..(8 * 60)
        val night = windowStartMin >= 20 * 60 || windowStartMin < 6 * 60
        return when {
            ("niskie napięcie baterii" in d || "rozładowana" in d || e.category == EventCategory.BATTERY) && soc != null && soc < 30 && (morning || night) ->
                "Niewystarczająca energia w baterii na noc (SOC przed zdarzeniem ok. ${soc.toInt()}%, zdarzenia ${if (morning) "rano" else "w nocy"})" to 0.6 + 0.3 * share
            ("niskie napięcie baterii" in d || "rozładowana" in d) && soc != null && soc < 30 ->
                "Głębokie rozładowanie baterii (SOC przed zdarzeniem ok. ${soc.toInt()}%)" to 0.6
            "przeciąż" in d && load != null -> "Przeciążenie odbiorami (obciążenie przed zdarzeniem ok. ${load.toInt()} W)" to 0.7
            e.category == EventCategory.TEMPERATURE && temp != null && temp > 55 -> "Wysoka temperatura pracy (falownik ok. ${temp.toInt()} °C przed zdarzeniem)" to 0.7
            e.category == EventCategory.PV && pv != null && pv < 100 -> "Mało światła (PV przed zdarzeniem ok. ${pv.toInt()} W) – typowe rano i wieczorem" to 0.6
            e.category == EventCategory.COMMUNICATION -> "Problem z łączem (mostek, kabel, zasilanie interfejsu)" to 0.5
            e.category == EventCategory.GRID && e.description.startsWith("Rozpoczęcie") && soc != null && soc < 35 -> "Sieć przejmuje zasilanie przy niskim SOC (ok. ${soc.toInt()}%)" to 0.75
            else -> "Przyczyna nieustalona – za mało danych o warunkach" to 0.3
        }
    }
}

/** Communication quality over a period: explainable score (100 − listed penalties). */
data class CommunicationReport(
    val score: Int?,
    val polls: Int,
    val failures: Int,
    val timeouts: Int,
    val crcErrors: Int,
    val invalidFrames: Int,
    val disconnects: Int,
    val reconnects: Int,
    val missingSamples: Int,
    val frozenRuns: Int,
    val longestOutage: Duration,
    val medianLatencyMs: Long?,
    val penalties: List<Pair<String, Int>>,
    val confidence: Double,
)

object AnenjiCommunicationAnalyzer {
    fun analyze(samples: List<AnalyzerSample>, comm: List<CommRecord>, expectedInterval: Duration? = null): CommunicationReport {
        val s = samples.sortedBy { it.time }
        val c = comm.sortedBy { it.time }
        val interval = expectedInterval ?: AnenjiEventLog.typicalInterval(s)
        var missing = 0
        var longestGap = Duration.ZERO
        if (interval != null && !interval.isZero) for ((a, b) in s.zipWithNext()) {
            val gap = Duration.between(a.time, b.time)
            if (gap > interval.multipliedBy(2)) {
                missing += (gap.toMillis() / interval.toMillis() - 1).toInt()
                if (gap > longestGap) longestGap = gap
            }
        }
        // Longest run of failed polls.
        var longestFail = Duration.ZERO
        var runStart: Instant? = null
        for (r in c) {
            if (!r.ok) { if (runStart == null) runStart = r.time; Duration.between(runStart, r.time).let { if (it > longestFail) longestFail = it } }
            else runStart?.let { st -> Duration.between(st, r.time).let { if (it > longestFail) longestFail = it }; runStart = null }
        }
        val failures = c.count { !it.ok }
        val reconnects = c.zipWithNext().count { (a, b) -> !a.ok && b.ok }
        // Frozen: ≥ 5 consecutive samples with identical non-empty values (a live device always fluctuates a little).
        var frozen = 0
        var streak = 0
        for ((a, b) in s.zipWithNext()) {
            if (a.values.isNotEmpty() && a.values == b.values && a.origin != DataOrigin.SIMULATOR) { streak++; if (streak == 4) frozen++ } else streak = 0
        }
        val polls = if (c.isNotEmpty()) c.size else s.size
        if (polls == 0) return CommunicationReport(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, null, emptyList(), 0.0)
        val penalties = mutableListOf<Pair<String, Int>>()
        val failRate = if (c.isNotEmpty()) failures.toDouble() / c.size else 0.0
        if (failRate > 0) penalties += "Nieudane odczyty ${"%.1f".format(failRate * 100)}%" to (failRate * 60).toInt().coerceAtLeast(1)
        val expected = s.size + missing
        if (missing > 0 && expected > 0) penalties += "Brakujące próbki $missing (${"%.1f".format(missing * 100.0 / expected)}%)" to (missing * 30.0 / expected).toInt().coerceAtLeast(1)
        val outage = maxOf(longestGap, longestFail)
        if (outage > Duration.ofMinutes(1)) penalties += "Najdłuższa przerwa ${outage.toMinutes()} min" to minOf(10, (outage.toMinutes() / 6).toInt() + 1)
        if (frozen > 0) penalties += "Zamrożone wartości: $frozen serii" to minOf(10, frozen * 2)
        val score = (100 - penalties.sumOf { it.second }).coerceIn(0, 100)
        return CommunicationReport(
            score, polls, failures, c.count { it.error == CommError.TIMEOUT }, c.count { it.error == CommError.CRC },
            c.count { it.error == CommError.INVALID_FRAME }, c.count { it.error == CommError.DISCONNECTED }, reconnects, missing, frozen, outage,
            Stats.median(c.mapNotNull { it.latencyMs?.toDouble() })?.toLong(), penalties,
            if (c.isNotEmpty()) 0.9 else 0.6, // without poll records only data gaps can be judged
        )
    }
}

package com.solartracker.pro.core.export

import com.solartracker.pro.core.anenji.DeepAnalysisReport
import com.solartracker.pro.core.anenji.Finding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "ANENJI_FULL_DIAGNOSTIC_REPORT" as JSON, CSV (sections) and plain text lines (for the PDF renderer). */
object DeepReportExport {
    const val NAME = "ANENJI_FULL_DIAGNOSTIC_REPORT"

    private fun p(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Number -> if (v is Double && !v.isFinite()) JsonNull else JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to (v as? JsonElement ?: p(v)) })

    private fun finding(f: Finding) = obj("title" to f.title, "severity" to f.severity.name, "reason" to f.reason, "evidence" to JsonArray(f.evidence.map { p(it) }),
        "confidence" to f.confidence, "impact" to f.impact, "impactKwh" to f.impactKwh, "possibleCauses" to JsonArray(f.possibleCauses.map { p(it) }))

    fun json(r: DeepAnalysisReport, appVersion: String): String {
        val root = obj(
            "format" to "$NAME/1", "appVersion" to appVersion, "readOnly" to r.readOnlyNotice,
            "device" to r.device, "generatedAt" to r.generatedAt, "period" to obj("from" to r.from, "to" to r.to),
            "origin" to r.origin.name, "simulated" to r.simulated, "excludedSimulatorSamples" to r.excludedSimulatorSamples,
            "configuration" to (r.configuration?.let { s -> obj("timestamp" to s.timestamp, "verification" to s.verification.name, "values" to JsonArray(s.values.map { v ->
                obj("key" to v.key.name, "label" to v.key.label, "register" to v.register, "raw" to v.rawValue, "decoded" to v.decodedValue, "text" to v.text,
                    "unit" to v.unit, "source" to v.source?.name, "status" to v.status.name)
            })) } ?: JsonNull),
            "configurationChanges" to JsonArray(r.configurationChanges.changes.map { c -> obj("key" to c.key.name, "old" to c.old.display, "new" to c.new.display,
                "between" to c.seenBetween, "seenAt" to c.seenAt) }),
            "summaries" to JsonArray(r.summaries.map { s -> obj("channel" to s.channel.name, "unit" to s.channel.unit, "samples" to s.samples, "min" to s.min, "max" to s.max,
                "average" to s.average, "median" to s.median, "p95" to s.p95) }),
            "energy" to obj("pvKwh" to r.energy.pvKwh, "loadKwh" to r.energy.loadKwh, "gridImportKwh" to r.energy.gridImportKwh, "gridExportKwh" to r.energy.gridExportKwh,
                "batteryChargeKwh" to r.energy.batteryChargeKwh, "batteryDischargeKwh" to r.energy.batteryDischargeKwh),
            "loadProfileW" to JsonArray(r.loadProfileW.map { p(it) }),
            "mppt" to (r.mppt?.let { m -> obj("available" to m.available, "note" to m.note, "abnormal" to JsonArray(m.abnormal.map { p(it) })) } ?: JsonNull),
            "events" to JsonArray(r.events.map { e -> obj("start" to e.start, "end" to e.end, "category" to e.category.name, "severity" to e.severity.name, "code" to e.code,
                "raw" to e.rawValue, "description" to e.description) }),
            "eventPatterns" to JsonArray(r.patterns.map { e -> obj("description" to e.description, "occurrences" to e.occurrences, "dominantWindow" to e.dominantWindow,
                "windowShare" to e.windowShare, "precedingSoc" to e.precedingSocMedian, "likelyCause" to e.likelyCause, "confidence" to e.confidence) }),
            "communication" to obj("score" to r.communication.score, "polls" to r.communication.polls, "failures" to r.communication.failures, "timeouts" to r.communication.timeouts,
                "crcErrors" to r.communication.crcErrors, "invalidFrames" to r.communication.invalidFrames, "disconnects" to r.communication.disconnects,
                "reconnects" to r.communication.reconnects, "missingSamples" to r.communication.missingSamples, "frozenRuns" to r.communication.frozenRuns,
                "longestOutageSeconds" to r.communication.longestOutage.seconds, "confidence" to r.communication.confidence),
            "anomalies" to JsonArray(r.anomalies.map(::finding)),
            "trends" to JsonArray(r.trends.map { t -> obj("channel" to t.channel.name, "window" to t.window.name, "samples" to t.samples, "min" to t.min, "max" to t.max,
                "average" to t.average, "median" to t.median, "p95" to t.p95, "slopePerDay" to t.slopePerDay, "direction" to t.direction.name, "anomalies" to t.anomalies.size) }),
            "recommendations" to JsonArray(r.findings.map(::finding)),
            "health" to obj("overall" to r.health.overall, "explanation" to r.health.explanation, "categories" to JsonArray(r.health.categories.map { c ->
                obj("category" to c.category.name, "score" to c.score, "basis" to c.basis, "confidence" to c.confidence,
                    "deductions" to JsonArray(c.deductions.map { d -> obj("reason" to d.reason, "points" to d.points) }))
            })),
            "dataQuality" to obj("samples" to r.dataQuality.samples, "coverage" to r.dataQuality.coverage, "intervalSeconds" to r.dataQuality.typicalIntervalSeconds,
                "invalidShare" to r.dataQuality.invalidShare, "missingChannels" to JsonArray(r.dataQuality.missingChannels.map { p(it.name) }),
                "notes" to JsonArray(r.dataQuality.notes.map { p(it) })),
            "unresolved" to JsonArray(r.unresolved.map { p(it) }),
        )
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    /** CSV with a "section" column so one file holds the whole report. */
    fun csv(r: DeepAnalysisReport, zone: ZoneId): String = buildString {
        appendLine("section,item,value,detail,confidence")
        fun row(vararg v: Any?) = appendLine(v.joinToString(",") { HistoryExport.csv(it) })
        row("device", "name", r.device, r.origin.name + if (r.simulated) " (SIMULATED)" else "", null)
        row("period", "from", r.from, null, null); row("period", "to", r.to, null, null)
        r.health.categories.forEach { c -> row("health", c.category.name, c.score ?: "N/A", (listOf(c.basis) + c.deductions.map { "${it.reason} (-${it.points})" }).joinToString("; "), c.confidence) }
        row("health", "OVERALL", r.health.overall ?: "N/A", r.health.explanation, null)
        r.configuration?.values?.forEach { v -> row("configuration", v.key.name, v.display, v.status.name + (v.source?.let { " / ${it.name}" } ?: ""), null) }
        r.configurationChanges.changes.forEach { c -> row("configuration_change", c.key.name, "${c.old.display} -> ${c.new.display}", "${c.seenBetween}..${c.seenAt}", null) }
        r.summaries.forEach { s -> row("summary", s.channel.name, s.average, "min ${fmt(s.min)} max ${fmt(s.max)} median ${fmt(s.median)} p95 ${fmt(s.p95)} ${s.channel.unit}", null) }
        listOf("pv" to r.energy.pvKwh, "load" to r.energy.loadKwh, "grid_import" to r.energy.gridImportKwh, "grid_export" to r.energy.gridExportKwh,
            "battery_charge" to r.energy.batteryChargeKwh, "battery_discharge" to r.energy.batteryDischargeKwh).forEach { (k, v) -> row("energy_kwh", k, v, null, null) }
        r.events.forEach { e -> row("event", e.category.name, e.description, "${e.start}..${e.end ?: ""} ${e.severity}", null) }
        r.patterns.forEach { e -> row("event_pattern", e.description, e.occurrences, "${e.dominantWindow ?: ""} ${e.likelyCause}", e.confidence) }
        with(r.communication) { row("communication", "score", score ?: "N/A", "timeouts $timeouts, crc $crcErrors, invalid $invalidFrames, missing $missingSamples, longest ${longestOutage.seconds}s", confidence) }
        (r.anomalies + r.findings).forEach { f -> row("finding", f.severity.name, f.title, (listOf(f.reason, f.impact) + f.evidence).joinToString("; "), f.confidence) }
        r.trends.forEach { t -> row("trend", "${t.channel.name}/${t.window.name}", t.direction.name, "avg ${fmt(t.average)} p95 ${fmt(t.p95)} slope/day ${t.slopePerDay?.let(::fmt) ?: ""}", null) }
        row("data_quality", "coverage", r.dataQuality.coverage, "invalid share ${fmt(r.dataQuality.invalidShare)}", null)
        r.unresolved.forEach { row("unresolved", "", it, null, null) }
    }

    /** Human-readable report lines (PDF / share). */
    fun lines(r: DeepAnalysisReport, zone: ZoneId): List<String> = buildList {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        add(NAME); add(r.readOnlyNotice)
        add("Urządzenie: ${r.device}")
        add("Okres: ${r.from?.let(f::format) ?: "—"} – ${r.to?.let(f::format) ?: "—"} · źródło: ${r.origin.label}")
        if (r.simulated) add("UWAGA: dane wyłącznie z symulatora – raport demonstracyjny")
        add(""); add("SYSTEM HEALTH: ${r.health.overall ?: "N/A"} / 100")
        r.health.categories.forEach { c -> add("  ${c.category.label}: ${c.score ?: "N/A"}" + (if (c.deductions.isNotEmpty()) " (" + c.deductions.joinToString { "${it.reason} −${it.points}" } + ")" else " – ${c.basis}")) }
        add("  ${r.health.explanation}")
        fun section(title: String, list: List<Finding>) {
            add(""); add(title)
            if (list.isEmpty()) add("  brak")
            list.forEach { x -> add("  ${x.title} (pewność ${(x.confidence * 100).toInt()}%)"); add("    ${x.reason}"); x.evidence.forEach { add("    • $it") }; add("    Wpływ: ${x.impact}")
                if (x.possibleCauses.isNotEmpty()) add("    Możliwe przyczyny: ${x.possibleCauses.joinToString()}") }
        }
        val all = r.anomalies + r.findings
        section("CRITICAL", all.filter { it.severity == com.solartracker.pro.core.anenji.FindingSeverity.CRITICAL })
        section("WARNINGS", all.filter { it.severity == com.solartracker.pro.core.anenji.FindingSeverity.WARNING })
        section("INFORMATION", all.filter { it.severity == com.solartracker.pro.core.anenji.FindingSeverity.INFO })
        add(""); add("ZDARZENIA (${r.events.size})")
        r.patterns.forEach { e -> add("  ${e.description}: ${e.occurrences}×" + (e.dominantWindow?.let { w -> ", ${(e.windowShare * 100).toInt()}% w $w" } ?: "") +
            (e.precedingSocMedian?.let { ", SOC przed ~${it.toInt()}%" } ?: "") + " → ${e.likelyCause} (pewność ${(e.confidence * 100).toInt()}%)") }
        add(""); add("KOMUNIKACJA: ${r.communication.score ?: "N/A"} / 100 · timeouty ${r.communication.timeouts} · nieprawidłowe ramki ${r.communication.invalidFrames} · " +
            "najdłuższa przerwa ${r.communication.longestOutage.seconds} s")
        add(""); add("KONFIGURACJA")
        r.configuration?.let { s -> s.values.filter { it.status.name != "NOT_AVAILABLE" }.forEach { add("  ${it.key.label}: ${it.display} [${it.status.label}]") }
            add("  Weryfikacja: ${s.verification.label}") } ?: add("  niedostępna")
        add("  " + r.configurationChanges.describe(zone).replace("\n", "\n  "))
        add(""); add("ENERGIA: PV ${fmt(r.energy.pvKwh)} kWh · zużycie ${fmt(r.energy.loadKwh)} kWh · sieć ${fmt(r.energy.gridImportKwh)} kWh")
        add(""); add("JAKOŚĆ DANYCH: ${r.dataQuality.samples} próbek · pokrycie ${(r.dataQuality.coverage * 100).toInt()}% · odrzucone ${"%.1f".format(r.dataQuality.invalidShare * 100)}%")
        add(""); add("NIEROZSTRZYGNIĘTE"); r.unresolved.forEach { add("  • $it") }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}

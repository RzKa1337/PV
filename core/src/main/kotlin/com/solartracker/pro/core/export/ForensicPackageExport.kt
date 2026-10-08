package com.solartracker.pro.core.export

import com.solartracker.pro.core.anenji.AnalyzerSample
import com.solartracker.pro.core.anenji.AnenjiEvent
import com.solartracker.pro.core.anenji.AnenjiSettingsSnapshot
import com.solartracker.pro.core.anenji.Channel
import com.solartracker.pro.core.anenji.ConfigImpact
import com.solartracker.pro.core.anenji.DataOrigin
import com.solartracker.pro.core.anenji.EvidenceNode
import com.solartracker.pro.core.anenji.ForensicDiagnosis
import com.solartracker.pro.core.anenji.ForensicDiagnosisBuilder
import com.solartracker.pro.core.anenji.LongTrend
import com.solartracker.pro.core.anenji.PeriodReport
import com.solartracker.pro.core.anenji.RawRef
import com.solartracker.pro.core.anenji.SettingStatus
import com.solartracker.pro.core.inverter.ManualReference
import com.solartracker.pro.core.inverter.RegisterLogRecord
import com.solartracker.pro.core.inverter.ValidationMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "ANENJI_FORENSIC_PACKAGE": everything needed to re-check a diagnosis outside the app – conclusions, evidence, normalised
 * telemetry with raw locators, raw registers, events, settings and metadata. report.pdf is rendered by the app from [lines].
 */
object ForensicPackageExport {
    const val NAME = "ANENJI_FORENSIC_PACKAGE"

    data class Input(
        val report: PeriodReport,
        val samples: List<AnalyzerSample>,
        val events: List<AnenjiEvent>,
        val settings: List<AnenjiSettingsSnapshot>,
        val registerLog: List<RegisterLogRecord>,
        val references: List<ManualReference>,
        val trends: List<LongTrend>,
        val configImpacts: List<ConfigImpact>,
        val appVersion: String,
        val device: String,
        val zone: ZoneId,
        val generatedAt: Instant,
    )

    private val pretty = Json { prettyPrint = true }

    private fun p(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Number -> if (v is Double && !v.isFinite()) JsonNull else JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to p(v) })
    private fun arr(list: List<Any?>) = JsonArray(list.map(::p))
    private fun str(e: JsonElement) = pretty.encodeToString(JsonElement.serializer(), e)

    private fun raw(r: RawRef?) = r?.let { obj("source" to it.source, "locator" to it.locator, "rawTimestamp" to it.rawTimestamp,
        "fields" to JsonObject(it.fields.mapValues { (_, v) -> JsonPrimitive(v) }), "registers" to JsonObject(it.registers.entries.associate { (a, v) -> a.toString() to JsonPrimitive(v) })) }

    private fun node(n: EvidenceNode) = obj("id" to n.id, "type" to n.type.name, "label" to n.label, "timestamp" to n.timestamp, "source" to n.source.name,
        "value" to n.value, "valueText" to n.valueText, "expectedValue" to n.expectedValue, "deviation" to n.deviation, "confidence" to n.confidence,
        "role" to n.role.name, "status" to n.status, "raw" to raw(n.raw))

    private fun diagnosis(d: ForensicDiagnosis) = obj(
        "id" to d.id, "title" to d.title, "type" to d.anomaly.type.name, "category" to d.anomaly.type.category.name,
        "start" to d.anomaly.start, "end" to d.anomaly.end, "observed" to d.anomaly.observed, "expected" to d.anomaly.expected, "unit" to d.anomaly.unit,
        "detail" to d.anomaly.detail, "severity" to d.severity.name, "observation" to d.observation.name, "causeCertainty" to d.certainty.name,
        "confidence" to obj("value" to d.confidence.value, "dataQuality" to d.confidence.dataQuality, "sampleCount" to d.confidence.sampleCount,
            "sourceReliability" to d.confidence.sourceReliability, "agreement" to d.confidence.agreement, "baselineQuality" to d.confidence.baselineQuality,
            "modelCertainty" to d.confidence.modelCertainty),
        "rootCause" to obj("observed" to d.rootCause.observed, "mostLikely" to d.rootCause.mostLikely,
            "possible" to JsonArray(d.rootCause.possible.map { obj("cause" to it.cause, "status" to it.status, "reason" to it.reason) }),
            "eliminated" to JsonArray(d.rootCause.eliminated.map { obj("cause" to it.cause, "reason" to it.reason) })),
        "impact" to obj("energyKwh" to d.impact.energyKwh, "cost" to d.impact.cost, "battery" to d.impact.batteryImpact, "downtimeSeconds" to d.impact.downtime?.seconds,
            "confidence" to d.impact.confidence, "basis" to d.impact.basis),
        "recommendation" to d.recommendation,
        "samples" to JsonArray(d.anomaly.samples.take(50).map { s -> obj("time" to s.time, "origin" to s.origin.name, "raw" to raw(s.raw)) }),
    )

    fun files(i: Input): LinkedHashMap<String, ByteArray> {
        val r = i.report
        val out = LinkedHashMap<String, ByteArray>()
        fun put(name: String, text: String) { out[name] = text.toByteArray(Charsets.UTF_8) }
        put("report.json", str(obj(
            "format" to "$NAME/1", "readOnly" to "Analiza tylko do odczytu – aplikacja nie zmienia ustawień falownika",
            "period" to r.period.name, "from" to r.from, "to" to r.to, "dataStatus" to r.status.status.name, "coverage" to r.status.coverage, "statusNote" to r.status.note,
            "simulated" to r.simulated, "lostKwh" to r.lostKwh, "cost" to r.cost,
            "bySeverity" to JsonObject(r.bySeverity.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) }),
            "byCertainty" to JsonObject(r.byCertainty.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) }),
            "ranking" to JsonArray(r.ranking.map { x -> obj("type" to x.type.name, "title" to x.title, "occurrences" to x.occurrences, "energyKwh" to x.energyKwh, "cost" to x.cost,
                "downtimeSeconds" to x.downtime.seconds, "confidence" to x.confidence, "certainty" to x.certainty.name, "severity" to x.severity.name,
                "firstSeen" to x.firstSeen, "lastSeen" to x.lastSeen, "diagnoses" to arr(x.diagnoses.map { it.id })) }),
            "trends90d" to JsonArray(i.trends.map { t -> obj("metric" to t.metric, "unit" to t.unit, "sufficient" to t.sufficient, "direction" to t.direction.name,
                "changePerMonth" to t.changePerMonth, "note" to t.note, "weekly" to JsonArray(t.weekly.map { (w, v) -> obj("week" to w, "value" to v) })) }),
            "configurationForensics" to JsonArray(i.configImpacts.map { c -> obj("key" to c.change.key.name, "old" to c.change.old.display, "new" to c.change.new.display,
                "between" to c.change.seenBetween, "seenAt" to c.change.seenAt, "incidentsAfter" to arr(c.incidentsAfter.map { it.id }),
                "before" to JsonObject(c.before.mapValues { p(it.value) }), "after" to JsonObject(c.after.mapValues { p(it.value) }), "note" to c.note) }),
        )))
        put("diagnoses.json", str(JsonArray(r.diagnoses.map(::diagnosis))))
        put("evidence.json", str(JsonArray(r.diagnoses.map { d -> obj("diagnosis" to d.id, "root" to d.evidence.diagnosis, "nodes" to JsonArray(d.evidence.nodes.map(::node))) })))
        put("events.csv", buildString {
            appendLine("start,end,category,severity,code,raw,description")
            i.events.forEach { e -> appendLine(listOf(e.start, e.end, e.category.name, e.severity.name, e.code, e.rawValue, e.description).joinToString(",") { HistoryExport.csv(it) }) }
        })
        put("telemetry.csv", buildString {
            val channels = Channel.entries.filter { c -> i.samples.any { it[c] != null } }
            appendLine((listOf("timestamp", "origin", "simulated", "raw_source", "raw_locator", "raw_timestamp") + channels.map { "${it.name}_${it.unit}" }).joinToString(","))
            i.samples.forEach { s ->
                appendLine((listOf(s.time, s.origin.name, s.origin == DataOrigin.SIMULATOR, s.raw?.source, s.raw?.locator, s.raw?.rawTimestamp) + channels.map { s[it] })
                    .joinToString(",") { HistoryExport.csv(it) })
            }
        })
        put("registers.csv", if (i.registerLog.isNotEmpty()) RegisterLogExport.csv(i.registerLog) else buildString {
            appendLine("timestamp,raw_source,raw_locator,address,address_hex,raw_dec")
            i.samples.forEach { s -> s.raw?.registers?.forEach { (a, v) ->
                appendLine(listOf(s.time, s.raw.source, s.raw.locator, a, "0x" + a.toString(16).uppercase(), v).joinToString(",") { HistoryExport.csv(it) })
            } }
        })
        put("settings.csv", buildString {
            appendLine("snapshot_time,device,key,label,register,raw,decoded,text,unit,source,status")
            i.settings.forEach { sn -> sn.values.filter { it.status != SettingStatus.NOT_AVAILABLE }.forEach { v ->
                appendLine(listOf(sn.timestamp, sn.device, v.key.name, v.key.label, v.register, v.rawValue, v.decodedValue, v.text, v.unit, v.source?.name, v.status.name)
                    .joinToString(",") { HistoryExport.csv(it) })
            } }
        })
        val origins = i.samples.groupingBy { it.origin.name }.eachCount()
        put("metadata.json", str(obj(
            "format" to "$NAME/1", "appVersion" to i.appVersion, "device" to i.device, "generatedAt" to i.generatedAt, "timeZone" to i.zone.id,
            "samples" to i.samples.size, "origins" to JsonObject(origins.mapValues { JsonPrimitive(it.value) }), "simulated" to r.simulated,
            "registerMap" to "UNVERIFIED – wymaga walidacji na prawdziwym urządzeniu",
            "manualReferences" to i.references.size, "realDeviceReferences" to i.references.count { it.fromRealDevice },
            "verifiedRegisters" to arr(ValidationMode.verifiedAddresses(i.references).sorted()),
            "sha256" to JsonObject(out.mapValues { (_, b) -> JsonPrimitive(sha256(b)) }),
        )))
        return out
    }

    /** Text of report.pdf: summary, ranking, then each diagnosis traced down to the raw source. */
    fun lines(i: Input): List<String> = buildList {
        val r = i.report
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(i.zone)
        add(NAME)
        add("${i.device} · ${f.format(r.from)} – ${f.format(r.to)} · wygenerowano ${f.format(i.generatedAt)}")
        if (r.simulated) add("DANE SYMULOWANE – nie dotyczą prawdziwego urządzenia")
        add("Status danych: ${r.status.status.label} (${r.status.note})")
        add("Analiza tylko do odczytu – aplikacja nie zmienia ustawień falownika. Mapa rejestrów niezweryfikowana na urządzeniu.")
        add("")
        add("NAJWAŻNIEJSZE PROBLEMY")
        if (r.ranking.isEmpty()) add("Brak wykrytych anomalii w tym okresie")
        r.ranking.take(10).forEachIndexed { n, x ->
            add("${n + 1}. ${x.title} – ${x.certainty.label}, ×${x.occurrences}, pewność ${(x.confidence * 100).toInt()}%" +
                (x.energyKwh?.let { ", strata %.2f kWh".format(it) } ?: ", strata N/A"))
        }
        add("Utracona energia łącznie: " + (r.lostKwh?.let { "%.2f kWh".format(it) } ?: "N/A") + (r.cost?.let { " · koszt %.2f".format(it) } ?: ""))
        add("")
        if (i.trends.isNotEmpty()) {
            add("TRENDY 90 DNI")
            i.trends.forEach { t -> add("${t.metric}: " + if (t.sufficient) "${t.direction.label}" + (t.changePerMonth?.let { " (%+.2f %s/mies.)".format(it, t.unit) } ?: "") else t.note) }
            add("")
        }
        if (i.configImpacts.isNotEmpty()) {
            add("ZMIANY KONFIGURACJI")
            i.configImpacts.forEach { c -> add("${c.change.key.label}: ${c.change.old.display} → ${c.change.new.display}; incydenty po zmianie: ${c.incidentsAfter.size} (${c.note})") }
            add("")
        }
        r.diagnoses.sortedByDescending { it.severity }.take(20).forEach { d -> addAll(ForensicDiagnosisBuilder.trace(d, i.zone)); add("ZALECENIE: ${d.recommendation}"); add("") }
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, data) -> z.putNextEntry(ZipEntry("$NAME/$name")); z.write(data); z.closeEntry() }
        }
        return bytes.toByteArray()
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

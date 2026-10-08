package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.inverter.MpptReading
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.core.inverter.Pi30Protocol
import com.solartracker.pro.core.inverter.SmgRegisterMap
import com.solartracker.pro.core.inverter.SmgRegisters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogFormat { CSV, JSON, TXT, UNKNOWN }

/** How rows are laid out: one row per time (WIDE), one row per register (REGISTER_LONG) or "key=value" lines. */
enum class LogLayout { WIDE, REGISTER_LONG, KEY_VALUE }

/** What a column was recognised as. */
sealed interface ColumnRole {
    data object Time : ColumnRole
    data class Measurement(val channel: Channel, val scale: Double) : ColumnRole
    data class Mppt(val index: Int, val quantity: Char, val scale: Double) : ColumnRole
    data class Setting(val key: SettingKey) : ColumnRole
    data object Mode : ColumnRole
    data object Warnings : ColumnRole
    data object Faults : ColumnRole
    data object CommOk : ColumnRole
    data object CommText : ColumnRole
    data object Simulated : ColumnRole
}

data class ColumnInfo(val header: String, val role: ColumnRole?, val unit: String?) {
    val recognized: Boolean get() = role != null
}

data class ImportedLog(
    val format: LogFormat,
    val layout: LogLayout,
    val columns: List<ColumnInfo>,
    val samples: List<AnalyzerSample>,
    val comm: List<CommRecord>,
    /** Settings seen in the log over time (time → values); used for snapshots and change detection. */
    val settings: List<Pair<Instant, Map<SettingKey, Double>>>,
    val settingsText: List<Pair<Instant, Map<SettingKey, String>>>,
    val rowsTotal: Int,
    val rowsParsed: Int,
    val duplicates: Int,
    val missingByChannel: Map<Channel, Int>,
    val issues: List<String>,
    val origin: DataOrigin,
) {
    val from: Instant? get() = samples.firstOrNull()?.time
    val to: Instant? get() = samples.lastOrNull()?.time
}

/**
 * Imports Anenji/inverter logs: our own exports (history CSV/JSON, register log CSV/JSON) and generic CSV/TSV/
 * semicolon, JSON arrays and TXT "key=value" or whitespace tables. Columns are recognised by name, units from
 * the header (kW → W, Wh → kWh, mA → A), timestamps normalised to UTC instants. Nothing is guessed silently:
 * unknown columns and suspicious scales are reported as issues; missing cells stay missing.
 */
object LogImporter {
    private val MISSING = setOf("", "na", "n/a", "null", "none", "-", "—", "nan", "--")

    fun import(text: String, zone: ZoneId, fileName: String? = null): ImportedLog {
        val body = text.removePrefix("﻿").trim()
        val format = detectFormat(body, fileName)
        val src = fileName ?: "import"
        return when (format) {
            LogFormat.JSON -> importJson(body, zone, src)
            LogFormat.CSV -> importTable(body.lines(), detectDelimiter(body), zone, LogFormat.CSV, src)
            LogFormat.TXT -> if (body.lines().count { KV.containsMatchIn(it) } >= body.lines().count { it.isNotBlank() } / 2) importKeyValue(body.lines(), zone, src)
                else importTable(body.lines(), null, zone, LogFormat.TXT, src)
            LogFormat.UNKNOWN -> empty(format, listOf("Nie rozpoznano formatu pliku (oczekiwano CSV, JSON lub TXT)"))
        }
    }

    fun detectFormat(body: String, fileName: String? = null): LogFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return when {
            body.isEmpty() -> LogFormat.UNKNOWN
            body.startsWith("{") || body.startsWith("[") -> LogFormat.JSON
            ext == "json" -> LogFormat.JSON
            ext == "csv" || ext == "tsv" -> LogFormat.CSV
            ext == "txt" || ext == "log" -> LogFormat.TXT
            detectDelimiter(body) != null -> LogFormat.CSV
            else -> LogFormat.TXT
        }
    }

    /** Delimiter used consistently in the first lines (`,` `;` or tab); null = none. */
    fun detectDelimiter(body: String): Char? {
        val lines = body.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.take(5)
        if (lines.size < 2) return null
        return listOf('\t', ';', ',').firstOrNull { d ->
            // Delimiters inside quoted fields ("Kod błędu (u32, starsze słowo)") do not count.
            val counts = lines.map { l -> var q = false; l.count { ch -> if (ch == '"') q = !q; ch == d && !q } }
            counts.first() > 0 && counts.all { it == counts.first() }
        }
    }

    /** Lowercase, units and non-alphanumerics removed: "PV Power (kW)" → "pvpower". */
    fun normalize(header: String): String = header.lowercase()
        .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
        .replace("ą", "a").replace("ę", "e").replace("ó", "o").replace("ś", "s").replace("ł", "l").replace("ż", "z").replace("ź", "z").replace("ć", "c").replace("ń", "n")
        .filter { it.isLetterOrDigit() }

    /** Unit written in the header: "(kW)", "[V]", "_kw", "pv_w"… */
    fun headerUnit(header: String): String? {
        Regex("[\\(\\[]\\s*([^\\)\\]]+?)\\s*[\\)\\]]").find(header)?.let { return it.groupValues[1].lowercase() }
        val last = header.lowercase().split('_', ' ', '-').lastOrNull()
        return last?.takeIf { it in setOf("w", "kw", "v", "a", "ma", "hz", "kwh", "wh", "pct", "c", "va", "kva", "s") }
    }

    private val ALIASES: List<Pair<Channel, List<String>>> = listOf(
        Channel.PV_POWER to listOf("pv", "pvw", "pvpower", "ppv", "mocpv", "pvinputpower", "solarpower", "pvmocw"),
        Channel.PV_VOLTAGE to listOf("pvv", "pvvoltage", "vpv", "napieciepv", "pvinputvoltage"),
        Channel.PV_CURRENT to listOf("pva", "pvcurrent", "ipv", "pradpv", "pvinputcurrent"),
        Channel.PV_CHARGING_POWER to listOf("pvchargingpower", "pvchargepower", "pvchargew"),
        Channel.PV_ENERGY_DAY to listOf("pvenergytoday", "pvtoday", "energytoday", "dailyenergy", "pvdailyenergy"),
        Channel.PV_ENERGY_TOTAL to listOf("pvenergytotal", "totalenergy", "pvtotal", "pvtotalenergy"),
        Channel.BATTERY_VOLTAGE to listOf("batteryv", "batteryvoltage", "batv", "vbat", "ubat", "napieciebaterii"),
        Channel.BATTERY_CURRENT to listOf("batterya", "batterycurrent", "bata", "ibat", "pradbaterii"),
        Channel.BATTERY_POWER to listOf("batteryw", "batterypower", "batw", "pbat", "mocbaterii"),
        Channel.SOC to listOf("soc", "socpct", "batterysoc", "batterypercent", "batterycapacitypercent"),
        Channel.BATTERY_TEMPERATURE to listOf("batteryc", "batterytemperature", "battemp", "batterytemp"),
        Channel.LOAD_POWER to listOf("load", "loadw", "loadpower", "outputpower", "acoutputactivepower", "pload", "mocobciazenia"),
        Channel.LOAD_APPARENT to listOf("loadva", "apparentpower", "acoutputapparentpower", "outputva"),
        Channel.LOAD_PERCENT to listOf("loadpct", "loadpercent", "outputloadpercent", "obciazenie"),
        Channel.LOAD_ENERGY to listOf("loadenergytoday", "loadtoday", "consumptiontoday"),
        Channel.GRID_POWER to listOf("grid", "gridw", "gridpower", "pgrid", "mocsieci"),
        Channel.GRID_VOLTAGE to listOf("gridv", "gridvoltage", "acinputvoltage", "napieciesieci"),
        Channel.GRID_FREQUENCY to listOf("gridhz", "gridfrequency", "acinputfrequency"),
        Channel.GRID_IMPORT_ENERGY to listOf("gridimporttotal", "gridimportenergytotal"),
        Channel.GRID_EXPORT_ENERGY to listOf("gridexporttotal", "gridexportenergytotal"),
        Channel.AC_VOLTAGE to listOf("outputv", "outputvoltage", "acoutputvoltage"),
        Channel.AC_CURRENT to listOf("outputa", "outputcurrent", "acoutputcurrent"),
        Channel.AC_FREQUENCY to listOf("outputhz", "outputfrequency", "acoutputfrequency"),
        Channel.INVERTER_POWER to listOf("inverterw", "inverterpower"),
        Channel.INVERTER_TEMPERATURE to listOf("inverterc", "invertertemperature", "invertertemp", "heatsinktemperature", "invt"),
        Channel.DCDC_TEMPERATURE to listOf("dcdctemperature", "dcdctemp"),
        Channel.UPTIME to listOf("uptime", "uptimes", "runtime"),
    )
    private val TIME = setOf("timestamp", "time", "date", "datetime", "start", "czas", "data", "datetimeutc", "ts")
    private val MPPT = Regex("^(?:mppt|pv)([1-9])(v|a|w|voltage|current|power)$")

    fun role(header: String): ColumnRole? {
        val n = normalize(header)
        val unit = headerUnit(header)
        if (n in TIME) return ColumnRole.Time
        when (n) {
            "mode", "operatingmode", "tryb", "workmode" -> return ColumnRole.Mode
            "warnings", "warning", "warningcode", "warningcodes", "ostrzezenia" -> return ColumnRole.Warnings
            "faults", "fault", "faultcode", "faultcodes", "bledy" -> return ColumnRole.Faults
            "communicationok", "commok", "linkok" -> return ColumnRole.CommOk
            "communication", "commerror", "linkerror", "error" -> return ColumnRole.CommText
            "simulated" -> return ColumnRole.Simulated
        }
        SettingKey.forColumn(n)?.let { return ColumnRole.Setting(it) }
        MPPT.find(n)?.let { m ->
            val q = when (m.groupValues[2]) { "v", "voltage" -> 'V'; "a", "current" -> 'A'; else -> 'W' }
            return ColumnRole.Mppt(m.groupValues[1].toInt(), q, scaleFor(if (q == 'W') "W" else if (q == 'V') "V" else "A", unit))
        }
        // Units in the name ("pv_kw") must not hide the alias: strip a trailing unit token.
        val base = ALIASES.firstOrNull { (_, a) -> n in a }
            ?: unit?.let { u -> ALIASES.firstOrNull { (_, a) -> n.removeSuffix(u.filter { it.isLetterOrDigit() }) in a } }
        return base?.let { (c, _) -> ColumnRole.Measurement(c, scaleFor(c.unit, unit)) }
    }

    /** Multiplier from the header unit to the channel's SI unit; 1 when unknown or equal. */
    fun scaleFor(target: String, unit: String?): Double = when (target.lowercase() to unit?.lowercase()) {
        "w" to "kw", "va" to "kva" -> 1000.0
        "kwh" to "wh" -> 0.001
        "a" to "ma" -> 0.001
        else -> 1.0
    }

    // ---------------------------------------------------------------- tables

    private fun importTable(lines: List<String>, delimiter: Char?, zone: ZoneId, format: LogFormat, src: String): ImportedLog {
        // Keep the original line numbers (1-based) for raw-data forensics.
        val rows = lines.withIndex().filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("#") }
        if (rows.size < 2) return empty(format, listOf("Plik nie zawiera nagłówka i danych"))
        fun split(l: String) = if (delimiter == null) l.trim().split(Regex("\\s+")) else splitQuoted(l, delimiter)
        val header = split(rows.first().value)
        val decimalComma = delimiter == ';'
        val norm = header.map { normalize(it) }
        // Our register log: one row per register → pivot by timestamp.
        if ("address" in norm && ("rawdec" in norm || "raw" in norm)) {
            return importRegisterLong(header, rows.drop(1).map { split(it.value) }, zone, format, src, rows.drop(1).map { "linia ${it.index + 1}" })
        }
        val cols = header.map { ColumnInfo(it, role(it), headerUnit(it)) }
        val data = rows.drop(1).map { (i, r) -> "linia ${i + 1}" to cols.indices.associate { c -> cols[c] to split(r).getOrNull(c)?.trim().orEmpty() } }
        return assemble(format, LogLayout.WIDE, cols, data, zone, decimalComma, src)
    }

    private fun importKeyValue(lines: List<String>, zone: ZoneId, src: String): ImportedLog {
        val numbered = lines.withIndex().filter { it.value.isNotBlank() }
        val rows = numbered.map { it.value }
        val parsed = rows.map { line ->
            val pairs = KV.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val timeText = line.substringBefore(KV.find(line)?.value ?: line).trim().ifEmpty { pairs["time"] ?: pairs["timestamp"] ?: "" }
            pairs + ("timestamp" to timeText)
        }
        val keys = parsed.flatMap { it.keys }.distinct()
        val cols = keys.map { ColumnInfo(it, role(it), unitFromValues(parsed.mapNotNull { p -> p[it] })) }.map { c ->
            // Values like "1.2kW" carry the unit inline.
            val r = c.role
            if (r is ColumnRole.Measurement && c.unit != null) c.copy(role = r.copy(scale = scaleFor(r.channel.unit, c.unit))) else c
        }
        val data = parsed.mapIndexed { i, p -> "linia ${numbered[i].index + 1}" to cols.associateWith { c -> p[c.header]?.replace(Regex("[a-zA-Z%°]+$"), "").orEmpty() } }
        return assemble(LogFormat.TXT, LogLayout.KEY_VALUE, cols, data, zone, decimalComma = false, src = src)
    }

    private fun unitFromValues(values: List<String>): String? =
        values.firstNotNullOfOrNull { Regex("[0-9]\\s*([a-zA-Z%°]+)$").find(it.trim())?.groupValues?.get(1)?.lowercase() }

    private fun importRegisterLong(header: List<String>, rows: List<List<String>>, zone: ZoneId, format: LogFormat, src: String, locators: List<String>): ImportedLog {
        val idx = header.map { normalize(it) }.withIndex().associate { (i, h) -> h to i }
        fun cell(r: List<String>, name: String) = idx[name]?.let { r.getOrNull(it)?.trim() }
        val locatorOf = java.util.IdentityHashMap<List<String>, String>().apply { rows.forEachIndexed { i, r -> put(r, locators.getOrElse(i) { "wiersz ${i + 1}" }) } }
        val byTime = rows.groupBy { cell(it, "timestamp").orEmpty() }
        val issues = mutableListOf<String>()
        val samples = mutableListOf<AnalyzerSample>()
        val comm = mutableListOf<CommRecord>()
        var simulated = false
        val specs = SmgRegisters.LIVE.associateBy { it.address }
        for ((ts, group) in byTime) {
            val time = parseTime(ts, zone) ?: continue
            val ok = cell(group.first(), "communicationok")?.toBooleanStrictOrNull() ?: true
            if (cell(group.first(), "simulated") == "true") simulated = true
            comm += CommRecord(time, ok, if (ok) null else CommError.classify(cell(group.first(), "communication")), detail = cell(group.first(), "communication"))
            if (!ok) continue
            val values = mutableMapOf<Channel, Double>()
            var mode: OperatingMode? = null
            var warnings = 0L
            var faults = 0L
            for (r in group) {
                val address = cell(r, "address")?.toIntOrNull() ?: continue
                val raw = cell(r, "rawdec")?.toIntOrNull() ?: cell(r, "raw")?.toIntOrNull() ?: continue
                val decoded = cell(r, "decoded")?.toDoubleOrNull()
                val quality = cell(r, "quality")
                when (address) {
                    SmgRegisterMap.FAULT_CODE -> faults = faults or (raw.toLong() shl 16)
                    SmgRegisterMap.FAULT_CODE + 1 -> faults = faults or raw.toLong()
                    SmgRegisterMap.WARNING_CODE -> warnings = warnings or (raw.toLong() shl 16)
                    SmgRegisterMap.WARNING_CODE + 1 -> warnings = warnings or raw.toLong()
                    SmgRegisterMap.OPERATING_MODE -> mode = SmgRegisterMap.MODES[raw]
                    else -> {
                        val spec = specs[address] ?: continue
                        val ch = channelFor(spec.field) ?: continue
                        if (quality != "INVALID" && decoded != null) values[ch] = decoded
                    }
                }
            }
            fun bits(v: Long) = (0 until 32).filter { v and (1L shl it) != 0L }.toSet()
            val rawRegs = group.mapNotNull { r -> cell(r, "address")?.toIntOrNull()?.let { a -> (cell(r, "rawdec")?.toIntOrNull() ?: cell(r, "raw")?.toIntOrNull())?.let { a to it } } }.toMap()
            val first = locatorOf[group.first()] ?: "?"
            val last = locatorOf[group.last()] ?: first
            samples += AnalyzerSample(time, values, DataOrigin.IMPORTED, mode, bits(warnings), bits(faults),
                raw = RawRef(src, if (first == last) first else "$first–${last.removePrefix("linia ").removePrefix("rekord ")}", ts, registers = rawRegs))
        }
        if (simulated) issues += "Log pochodzi z symulatora – wyniki są demonstracyjne"
        val sorted = samples.sortedBy { it.time }
        val origin = if (simulated) DataOrigin.SIMULATOR else DataOrigin.IMPORTED
        return ImportedLog(format, LogLayout.REGISTER_LONG, header.map { ColumnInfo(it, null, null) }, sorted.map { it.copy(origin = origin) },
            comm.sortedBy { it.time }, emptyList(), emptyList(), rows.size, rows.size, 0, missing(sorted), issues, origin)
    }

    private fun channelFor(f: com.solartracker.pro.core.inverter.TelemetryField?): Channel? = when (f) {
        null -> null
        com.solartracker.pro.core.inverter.TelemetryField.PV_VOLTAGE -> Channel.PV_VOLTAGE
        com.solartracker.pro.core.inverter.TelemetryField.PV_CURRENT -> Channel.PV_CURRENT
        com.solartracker.pro.core.inverter.TelemetryField.PV_POWER -> Channel.PV_POWER
        com.solartracker.pro.core.inverter.TelemetryField.PV_CHARGING_POWER -> Channel.PV_CHARGING_POWER
        com.solartracker.pro.core.inverter.TelemetryField.BATTERY_VOLTAGE -> Channel.BATTERY_VOLTAGE
        com.solartracker.pro.core.inverter.TelemetryField.BATTERY_CURRENT -> Channel.BATTERY_CURRENT
        com.solartracker.pro.core.inverter.TelemetryField.BATTERY_POWER -> Channel.BATTERY_POWER
        com.solartracker.pro.core.inverter.TelemetryField.BATTERY_SOC -> Channel.SOC
        com.solartracker.pro.core.inverter.TelemetryField.GRID_VOLTAGE -> Channel.GRID_VOLTAGE
        com.solartracker.pro.core.inverter.TelemetryField.GRID_POWER -> Channel.GRID_POWER
        com.solartracker.pro.core.inverter.TelemetryField.GRID_FREQUENCY -> Channel.GRID_FREQUENCY
        com.solartracker.pro.core.inverter.TelemetryField.LOAD_POWER -> Channel.LOAD_POWER
        com.solartracker.pro.core.inverter.TelemetryField.LOAD_APPARENT_POWER -> Channel.LOAD_APPARENT
        com.solartracker.pro.core.inverter.TelemetryField.LOAD_PERCENT -> Channel.LOAD_PERCENT
        com.solartracker.pro.core.inverter.TelemetryField.INVERTER_POWER -> Channel.INVERTER_POWER
        com.solartracker.pro.core.inverter.TelemetryField.OUTPUT_VOLTAGE -> Channel.AC_VOLTAGE
        com.solartracker.pro.core.inverter.TelemetryField.OUTPUT_CURRENT -> Channel.AC_CURRENT
        com.solartracker.pro.core.inverter.TelemetryField.OUTPUT_FREQUENCY -> Channel.AC_FREQUENCY
        com.solartracker.pro.core.inverter.TelemetryField.INVERTER_TEMPERATURE -> Channel.INVERTER_TEMPERATURE
        else -> null
    }

    // ---------------------------------------------------------------- JSON

    private fun importJson(body: String, zone: ZoneId, src: String): ImportedLog {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrElse { return empty(LogFormat.JSON, listOf("Nieprawidłowy JSON: ${it.message}")) }
        val obj = root as? JsonObject
        val format = (obj?.get("format") as? JsonPrimitive)?.content
        // Our register log JSON: records[].registers[] → the same pivot as the CSV variant.
        if (format?.startsWith("solar-tracker-pro-register-log") == true) {
            val header = listOf("timestamp", "communication_ok", "communication", "simulated", "address", "raw_dec", "decoded", "quality")
            val sim = (obj["simulated"] as? JsonPrimitive)?.content == "true"
            val rows = (obj["records"] as? JsonArray).orEmpty().flatMap { rec ->
                val r = rec as JsonObject
                val head = listOf(prim(r["timestamp"]), prim(r["communicationOk"]), prim(r["communication"]), sim.toString())
                val regs = (r["registers"] as? JsonArray).orEmpty()
                if (regs.isEmpty()) listOf(head + listOf("", "", "", ""))
                else regs.map { g -> val o = g as JsonObject; head + listOf(prim(o["address"]), prim(o["raw"]), prim(o["decoded"]), prim(o["quality"])) }
            }
            return importRegisterLong(header, rows, zone, LogFormat.JSON, src, rows.indices.map { "rekord JSON ${it + 1}" })
        }
        val array: List<JsonObject> = when {
            root is JsonArray -> root.filterIsInstance<JsonObject>()
            obj?.get("samples") is JsonArray -> (obj["samples"] as JsonArray).filterIsInstance<JsonObject>()
            obj?.get("data") is JsonArray -> (obj["data"] as JsonArray).filterIsInstance<JsonObject>()
            obj?.get("records") is JsonArray -> (obj["records"] as JsonArray).filterIsInstance<JsonObject>()
            else -> return empty(LogFormat.JSON, listOf("JSON bez tablicy rekordów (oczekiwano tablicy lub pola samples/data/records)"))
        }
        val keys = array.flatMap { it.keys }.distinct()
        val cols = keys.map { ColumnInfo(it, role(it), headerUnit(it)) }
        val data = array.mapIndexed { i, o -> "rekord JSON ${i + 1}" to cols.associateWith { c -> prim(o[c.header]) } }
        return assemble(LogFormat.JSON, LogLayout.WIDE, cols, data, zone, decimalComma = false, src = src)
    }

    private fun prim(e: JsonElement?): String = when (e) {
        null, is JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> e.toString()
    }

    // ---------------------------------------------------------------- assembly

    private fun assemble(format: LogFormat, layout: LogLayout, cols: List<ColumnInfo>, located: List<Pair<String, Map<ColumnInfo, String>>>, zone: ZoneId,
                         decimalComma: Boolean, src: String): ImportedLog {
        val rows = located.map { it.second }
        val issues = mutableListOf<String>()
        val timeCol = cols.firstOrNull { it.role == ColumnRole.Time }
            ?: return empty(format, listOf("Brak kolumny czasu (timestamp/time/date)"), cols)
        cols.filter { !it.recognized && it != timeCol }.takeIf { it.isNotEmpty() }?.let { u ->
            issues += "Nierozpoznane kolumny (pominięte): ${u.joinToString { it.header }}"
        }
        fun num(s: String): Double? {
            val t = s.trim()
            if (t.lowercase() in MISSING) return null
            return (if (decimalComma) t.replace(',', '.') else t).toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        val samples = mutableListOf<AnalyzerSample>()
        val comm = mutableListOf<CommRecord>()
        val settings = mutableListOf<Pair<Instant, Map<SettingKey, Double>>>()
        val settingsText = mutableListOf<Pair<Instant, Map<SettingKey, String>>>()
        var badTime = 0
        var simulated = false
        for ((locator, row) in located) {
            val time = parseTime(row[timeCol].orEmpty(), zone) ?: run { badTime++; null } ?: continue
            val values = mutableMapOf<Channel, Double>()
            val mppt = mutableMapOf<Int, Triple<Double?, Double?, Double?>>()
            var mode: OperatingMode? = null
            var warnings = emptySet<Int>()
            var faults = emptySet<Int>()
            var commOk: Boolean? = null
            var commText: String? = null
            val set = mutableMapOf<SettingKey, Double>()
            val setText = mutableMapOf<SettingKey, String>()
            for ((c, raw) in row) {
                when (val r = c.role) {
                    is ColumnRole.Measurement -> num(raw)?.let { values[r.channel] = it * r.scale }
                    is ColumnRole.Mppt -> num(raw)?.let { v ->
                        val (pv, pa, pw) = mppt[r.index] ?: Triple(null, null, null)
                        val x = v * r.scale
                        mppt[r.index] = when (r.quantity) { 'V' -> Triple(x, pa, pw); 'A' -> Triple(pv, x, pw); else -> Triple(pv, pa, x) }
                    }
                    is ColumnRole.Setting -> raw.trim().takeIf { it.lowercase() !in MISSING }?.let { t -> num(t)?.let { set[r.key] = it } ?: run { setText[r.key] = t } }
                    ColumnRole.Mode -> mode = parseMode(raw)
                    ColumnRole.Warnings -> warnings = parseCodes(raw)
                    ColumnRole.Faults -> faults = parseCodes(raw)
                    ColumnRole.CommOk -> commOk = raw.trim().lowercase().let { it == "true" || it == "1" || it == "ok" }
                    ColumnRole.CommText -> commText = raw.trim().takeIf { it.isNotEmpty() }
                    ColumnRole.Simulated -> if (raw.trim().lowercase() == "true") simulated = true
                    ColumnRole.Time, null -> Unit
                }
            }
            if (commOk != null) comm += CommRecord(time, commOk!!, if (commOk == true) null else CommError.classify(commText), detail = commText)
            if (set.isNotEmpty()) settings += time to set
            if (setText.isNotEmpty()) settingsText += time to setText
            if (commOk == false && values.isEmpty()) continue
            if (values.isEmpty() && mppt.isEmpty() && mode == null && warnings.isEmpty() && faults.isEmpty()) continue
            samples += AnalyzerSample(time, values, DataOrigin.IMPORTED, mode, warnings, faults,
                mppt.toSortedMap().map { (i, t) -> MpptReading(i, t.first, t.second, t.third ?: if (t.first != null && t.second != null) t.first!! * t.second!! else null) },
                RawRef(src, locator, row[timeCol], row.entries.associate { (c, v) -> c.header to v }))
        }
        if (badTime > 0) issues += "Wiersze z nieczytelnym czasem: $badTime (pominięte)"
        val sorted = samples.sortedBy { it.time }
        val dedup = sorted.distinctBy { it.time }
        val duplicates = sorted.size - dedup.size
        if (duplicates > 0) issues += "Zduplikowane znaczniki czasu: $duplicates (zachowano pierwszy)"
        scaleWarnings(dedup, cols)?.let { issues += it }
        if (simulated) issues += "Log pochodzi z symulatora – wyniki są demonstracyjne"
        val origin = if (simulated) DataOrigin.SIMULATOR else DataOrigin.IMPORTED
        return ImportedLog(format, layout, cols, dedup.map { it.copy(origin = origin) }, comm.sortedBy { it.time }, settings.sortedBy { it.first },
            settingsText.sortedBy { it.first }, rows.size, samples.size, duplicates, missing(dedup), issues, origin)
    }

    /** PV power that looks like kW without a unit in the header (values ≤ 20 while V × I says hundreds of W). */
    private fun scaleWarnings(samples: List<AnalyzerSample>, cols: List<ColumnInfo>): String? {
        val pvCol = cols.firstOrNull { (it.role as? ColumnRole.Measurement)?.channel == Channel.PV_POWER } ?: return null
        if (pvCol.unit != null) return null
        val suspicious = samples.count { s ->
            val p = s[Channel.PV_POWER] ?: return@count false
            val v = s[Channel.PV_VOLTAGE] ?: return@count false
            val a = s[Channel.PV_CURRENT] ?: return@count false
            v * a > 200 && p in 0.01..20.0
        }
        return if (suspicious >= 3) "Kolumna „${pvCol.header}” wygląda na kW (P ≪ V×I), a nagłówek nie podaje jednostki – sprawdź jednostkę" else null
    }

    private fun missing(samples: List<AnalyzerSample>): Map<Channel, Int> {
        val present = samples.flatMap { it.values.keys }.toSet()
        return present.associateWith { c -> samples.count { it[c] == null } }.filterValues { it > 0 }
    }

    fun parseMode(raw: String): OperatingMode? {
        val t = raw.trim()
        if (t.isEmpty() || t.lowercase() in MISSING) return null
        t.toIntOrNull()?.let { return SmgRegisterMap.MODES[it] }
        if (t.length == 1) Pi30Protocol.MODES[t[0].uppercaseChar()]?.let { return it }
        return OperatingMode.entries.firstOrNull { it.name.equals(t.replace(' ', '_').replace('-', '_'), ignoreCase = true) }
            ?: when (t.lowercase()) { "line", "grid mode", "line mode" -> OperatingMode.GRID; "battery", "battery mode" -> OperatingMode.OFF_GRID; else -> null }
    }

    /** "1 5 9", "1|5", "1;5" → codes; empty → none. */
    fun parseCodes(raw: String): Set<Int> = raw.split(' ', '|', ';', ',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    private val FORMATS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm").map { DateTimeFormatter.ofPattern(it) }

    /** ISO instant, ISO/local date-time in [zone], epoch seconds or milliseconds; null when unreadable. */
    fun parseTime(raw: String, zone: ZoneId): Instant? {
        val t = raw.trim().trim('"')
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { n ->
            return when {
                n > 100_000_000_000L -> Instant.ofEpochMilli(n)
                n > 100_000_000L -> Instant.ofEpochSecond(n)
                else -> null
            }
        }
        runCatching { return Instant.parse(t) }
        runCatching { return java.time.OffsetDateTime.parse(t).toInstant() }
        val local = t.substringBefore('.').replace('T', ' ').let { s -> FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDateTime.parse(s, f) }.getOrNull() } }
            ?: FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDateTime.parse(t, f) }.getOrNull() }
        return local?.atZone(zone)?.toInstant()
    }

    private fun splitQuoted(line: String, d: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == d && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private val KV = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\s=]+)")

    private fun empty(format: LogFormat, issues: List<String>, cols: List<ColumnInfo> = emptyList()) =
        ImportedLog(format, LogLayout.WIDE, cols, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, 0, emptyMap(), issues, DataOrigin.IMPORTED)
}

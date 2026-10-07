package com.solartracker.pro.core.export

import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.analytics.PeriodTotals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.Locale

/**
 * Export of recorded history. Values are written exactly as stored (measured power and energy
 * integrated from it); missing values stay empty/null — nothing is filled in.
 */
object HistoryExport {
    private val sampleColumns = listOf(
        "start", "end", "samples", "pv_w", "pv_max_w", "load_w", "battery_w", "grid_w", "battery_v", "battery_a", "soc_pct",
        "inverter_c", "battery_c", "pv_kwh", "load_kwh", "grid_import_kwh", "grid_export_kwh", "battery_charge_kwh",
        "battery_discharge_kwh", "mode", "faults", "warnings",
    )

    fun samplesCsv(samples: List<HistorySample>): String = buildString {
        appendLine(sampleColumns.joinToString(","))
        samples.forEach { s ->
            appendLine(sampleRow(s).joinToString(",") { csv(it) })
        }
    }

    fun totalsCsv(totals: List<PeriodTotals>): String = buildString {
        appendLine("period,start,pv_kwh,load_kwh,grid_import_kwh,grid_export_kwh,battery_charge_kwh,battery_discharge_kwh,peak_pv_w,self_consumption,autarky,coverage")
        totals.forEach { t ->
            appendLine(listOf(t.period.name, t.periodStart.toString(), num(t.pvKwh), num(t.loadKwh), num(t.gridImportKwh), num(t.gridExportKwh),
                num(t.batteryChargeKwh), num(t.batteryDischargeKwh), num(t.peakPvW), num(t.selfConsumption), num(t.autarky), num(t.coverage)).joinToString(","))
        }
    }

    fun json(samples: List<HistorySample>, totals: List<PeriodTotals>, generatedAt: Instant, appVersion: String): String {
        val obj = JsonObject(mapOf(
            "format" to JsonPrimitive("solar-tracker-pro-history/1"),
            "generatedAt" to JsonPrimitive(generatedAt.toString()),
            "appVersion" to JsonPrimitive(appVersion),
            "note" to JsonPrimitive("Measured inverter telemetry; energy integrated from power (CALCULATED). Empty = not reported."),
            "totals" to JsonArray(totals.map { t ->
                JsonObject(mapOf(
                    "period" to JsonPrimitive(t.period.name), "start" to JsonPrimitive(t.periodStart.toString()),
                    "pvKwh" to JsonPrimitive(t.pvKwh), "loadKwh" to JsonPrimitive(t.loadKwh),
                    "gridImportKwh" to JsonPrimitive(t.gridImportKwh), "gridExportKwh" to JsonPrimitive(t.gridExportKwh),
                    "batteryChargeKwh" to JsonPrimitive(t.batteryChargeKwh), "batteryDischargeKwh" to JsonPrimitive(t.batteryDischargeKwh),
                    "peakPvW" to prim(t.peakPvW), "selfConsumption" to prim(t.selfConsumption), "autarky" to prim(t.autarky), "coverage" to JsonPrimitive(t.coverage),
                ))
            }),
            "samples" to JsonArray(samples.map { s ->
                JsonObject(sampleColumns.zip(sampleRow(s)).associate { (k, v) ->
                    k to when (v) { null -> JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
                })
            }),
        ))
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    private fun sampleRow(s: HistorySample): List<Any?> = listOf(
        s.start.toString(), s.end.toString(), s.samples, s.pvW, s.pvMaxW, s.loadW, s.batteryW, s.gridW, s.batteryVoltageV, s.batteryCurrentA,
        s.socPercent, s.inverterTemperatureC, s.batteryTemperatureC, s.pvEnergyKwh, s.loadEnergyKwh, s.gridImportKwh, s.gridExportKwh,
        s.batteryChargeKwh, s.batteryDischargeKwh, s.mode?.name, s.faultCodes.sorted().joinToString(" "), s.warningCodes.sorted().joinToString(" "),
    )

    private fun prim(v: Double?) = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: ""

    /** One CSV cell (shared with other exports): formula-injection safe, quoted when needed. */
    internal fun csv(v: Any?): String = when (v) {
        null -> ""
        is Double -> String.format(Locale.ROOT, "%.4f", v)
        is Number -> v.toString()
        else -> v.toString().let { s ->
            // Neutralise spreadsheet formula injection and quote separators.
            val safe = if (s.isNotEmpty() && s[0] in "=+-@" && s.toDoubleOrNull() == null) "'$s" else s
            if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}

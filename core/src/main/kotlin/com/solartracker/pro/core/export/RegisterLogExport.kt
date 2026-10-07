package com.solartracker.pro.core.export

import com.solartracker.pro.core.inverter.RegisterLogRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Export of the raw register log, to verify the register map against a real inverter. Raw words are written
 * exactly as read; nothing is filled in. A simulator log is labelled and must not be used for verification.
 */
object RegisterLogExport {
    val COLUMNS = listOf(
        "timestamp", "communication_ok", "communication", "simulated", "address", "name", "raw_dec", "raw_hex",
        "decoded", "unit", "expected_min", "expected_max", "quality", "note",
    )

    fun csv(records: List<RegisterLogRecord>): String = buildString {
        appendLine(COLUMNS.joinToString(","))
        records.forEach { r ->
            val head = listOf(r.timestamp.toString(), r.communicationOk, r.communication, r.simulated)
            if (r.samples.isEmpty()) {
                appendLine((head + List(COLUMNS.size - head.size) { null }).joinToString(",") { HistoryExport.csv(it) })
            } else r.samples.forEach { s ->
                appendLine((head + listOf(s.address, s.name, s.raw, hex(s.raw), s.decoded, s.unit, s.expectedMin, s.expectedMax, s.quality.name, s.note))
                    .joinToString(",") { HistoryExport.csv(it) })
            }
        }
    }

    fun json(records: List<RegisterLogRecord>, generatedAt: Instant, appVersion: String, device: String): String {
        fun prim(v: Double?) = v?.let { JsonPrimitive(it) } ?: JsonNull
        val obj = JsonObject(mapOf(
            "format" to JsonPrimitive("solar-tracker-pro-register-log/1"),
            "generatedAt" to JsonPrimitive(generatedAt.toString()),
            "appVersion" to JsonPrimitive(appVersion),
            "device" to JsonPrimitive(device),
            "simulated" to JsonPrimitive(records.any { it.simulated }),
            "note" to JsonPrimitive("Read-only Modbus log (function 03). Register map from community documentation – REAL DEVICE VALIDATION REQUIRED. " +
                "Compare decoded values with the inverter display."),
            "records" to JsonArray(records.map { r ->
                JsonObject(mapOf(
                    "timestamp" to JsonPrimitive(r.timestamp.toString()),
                    "communicationOk" to JsonPrimitive(r.communicationOk),
                    "communication" to JsonPrimitive(r.communication),
                    "registers" to JsonArray(r.samples.map { s ->
                        JsonObject(mapOf(
                            "address" to JsonPrimitive(s.address), "name" to JsonPrimitive(s.name),
                            "raw" to JsonPrimitive(s.raw), "rawHex" to JsonPrimitive(hex(s.raw)),
                            "decoded" to prim(s.decoded), "unit" to JsonPrimitive(s.unit),
                            "expectedMin" to prim(s.expectedMin), "expectedMax" to prim(s.expectedMax),
                            "quality" to JsonPrimitive(s.quality.name), "note" to (s.note?.let { JsonPrimitive(it) } ?: JsonNull),
                        ))
                    }),
                ))
            }),
        ))
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    private fun hex(raw: Int) = "0x" + raw.toString(16).uppercase().padStart(4, '0')
}

package com.solartracker.pro.core.ems

import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.energy.CoolingMode
import com.solartracker.pro.core.energy.CoolingScheduleEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalTime

/** Validation of user-entered EMS configuration. */
fun FlexibleLoad.validate(): List<String> = buildList {
    if (name.isBlank() || name.length > 40) add("Nazwa 1–40 znaków")
    if (powerKw !in 0.01..50.0) add("Moc 0,01–50 kW")
    if (hours !in 0.25..24.0) add("Czas pracy 0,25–24 h")
    if (earliest != null && latest != null && !latest.isAfter(earliest)) add("Koniec okna musi być po początku")
    if (earliest != null && latest != null && java.time.Duration.between(earliest, latest).toMinutes() < hours * 60) add("Okno krótsze niż czas pracy")
    if (priority !in -10..10) add("Priorytet −10…10")
}

fun GeneratorConfig.validate(): List<String> = buildList {
    if (ratedKw !in 0.1..100.0) add("Moc agregatu 0,1–100 kW")
    if (startSocPercent !in 5.0..90.0) add("Start przy SOC 5–90%")
    if (stopSocPercent !in 10.0..100.0 || stopSocPercent <= startSocPercent) add("Stop przy SOC wyższym niż start (do 100%)")
    if (minRunHours !in 0.0..12.0) add("Minimalny czas pracy 0–12 h")
    if (litersPerKwh != null && litersPerKwh !in 0.05..2.0) add("Zużycie paliwa 0,05–2 l/kWh")
}

/** JSON storage of EMS settings. Invalid entries are dropped on read rather than guessed. */
object EmsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeLoads(loads: List<FlexibleLoad>): String = JsonArray(loads.map { l ->
        JsonObject(mapOf(
            "id" to JsonPrimitive(l.id), "name" to JsonPrimitive(l.name), "kw" to JsonPrimitive(l.powerKw), "h" to JsonPrimitive(l.hours),
            "from" to (l.earliest?.let { JsonPrimitive(it.toString()) } ?: JsonNull), "to" to (l.latest?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "prio" to JsonPrimitive(l.priority), "surplus" to JsonPrimitive(l.surplusOnly),
        ))
    }).toString()

    fun decodeLoads(text: String?): List<FlexibleLoad> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    fun s(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    FlexibleLoad(
                        id = s("id")!!, name = s("name")!!,
                        powerKw = o["kw"]!!.jsonPrimitive.doubleOrNull!!, hours = o["h"]!!.jsonPrimitive.doubleOrNull!!,
                        earliest = s("from")?.let(LocalTime::parse), latest = s("to")?.let(LocalTime::parse),
                        priority = o["prio"]?.jsonPrimitive?.intOrNull ?: 0, surplusOnly = o["surplus"]?.jsonPrimitive?.booleanOrNull ?: true,
                    ).takeIf { it.validate().isEmpty() }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun encodeGenerator(g: GeneratorConfig?): String = g?.let {
        JsonObject(mapOf(
            "kw" to JsonPrimitive(it.ratedKw), "start" to JsonPrimitive(it.startSocPercent), "stop" to JsonPrimitive(it.stopSocPercent),
            "minH" to JsonPrimitive(it.minRunHours), "lpk" to (it.litersPerKwh?.let { v -> JsonPrimitive(v) } ?: JsonNull),
        )).toString()
    } ?: ""

    fun decodeGenerator(text: String?): GeneratorConfig? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            GeneratorConfig(
                o["kw"]!!.jsonPrimitive.doubleOrNull!!, o["start"]!!.jsonPrimitive.doubleOrNull!!, o["stop"]!!.jsonPrimitive.doubleOrNull!!,
                o["minH"]?.jsonPrimitive?.doubleOrNull ?: 1.0, o["lpk"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull,
            ).takeIf { it.validate().isEmpty() }
        }.getOrNull()
    }

    fun encodeCooling(c: CoolingLoadProfile?): String = c?.let {
        JsonObject(mapOf(
            "name" to JsonPrimitive(it.name), "nominal" to JsonPrimitive(it.nominalPowerW), "min" to JsonPrimitive(it.minimumPowerW),
            "mode" to JsonPrimitive(it.mode.name), "avg" to (it.averagePowerW?.let { v -> JsonPrimitive(v) } ?: JsonNull),
            "duty" to (it.dutyAtReference?.let { v -> JsonPrimitive(v) } ?: JsonNull),
            "schedule" to JsonArray(it.schedule.map { e -> JsonObject(mapOf("from" to JsonPrimitive(e.start.toString()), "to" to JsonPrimitive(e.end.toString()), "w" to JsonPrimitive(e.powerW))) }),
            "target" to JsonPrimitive(it.targetTemperatureC), "ref" to JsonPrimitive(it.referenceAmbientC),
            "open" to JsonPrimitive(it.operatingStart.toString()), "close" to JsonPrimitive(it.operatingEnd.toString()), "idle" to JsonPrimitive(it.idleDutyFactor),
            "pre" to JsonPrimitive(it.preCoolingEnabled), "preTarget" to (it.preCoolingTargetC?.let { v -> JsonPrimitive(v) } ?: JsonNull), "preH" to JsonPrimitive(it.preCoolingHours),
        )).toString()
    } ?: ""

    fun decodeCooling(text: String?): CoolingLoadProfile? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            fun d(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
            fun s(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            CoolingLoadProfile(
                name = s("name") ?: "Chłodnia", nominalPowerW = d("nominal")!!, minimumPowerW = d("min")!!,
                mode = CoolingMode.valueOf(s("mode")!!), averagePowerW = d("avg"), dutyAtReference = d("duty"),
                schedule = o["schedule"]?.jsonArray?.map { e ->
                    val x = e.jsonObject
                    CoolingScheduleEntry(LocalTime.parse(x["from"]!!.jsonPrimitive.content), LocalTime.parse(x["to"]!!.jsonPrimitive.content), x["w"]!!.jsonPrimitive.doubleOrNull!!)
                }.orEmpty(),
                targetTemperatureC = d("target")!!, referenceAmbientC = d("ref") ?: 25.0,
                operatingStart = s("open")?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT, operatingEnd = s("close")?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT,
                idleDutyFactor = d("idle") ?: 1.0, preCoolingEnabled = o["pre"]?.jsonPrimitive?.booleanOrNull ?: false,
                preCoolingTargetC = d("preTarget"), preCoolingHours = d("preH") ?: 2.0,
            ).takeIf { it.validate().isEmpty() }
        }.getOrNull()
    }
}

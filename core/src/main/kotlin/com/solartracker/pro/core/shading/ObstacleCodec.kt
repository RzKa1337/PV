package com.solartracker.pro.core.shading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.Month

/**
 * JSON (de)serialization of obstacles and terrain: offline cache of downloaded map data and storage
 * of the user's own obstacles (with history). Versioned; unknown/invalid entries are skipped.
 */
object ObstacleCodec {
    const val VERSION = 1

    fun encode(obstacles: List<Obstacle>): String = buildJsonObject {
        put("version", VERSION)
        put("obstacles", buildJsonArray { obstacles.forEach { add(obstacle(it)) } })
    }.toString()

    fun decode(text: String): List<Obstacle> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val arr = root["obstacles"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { obstacle(it as JsonObject) }.getOrNull() }
    }

    fun encodeTerrain(t: TerrainSamples): String = buildJsonObject {
        put("version", VERSION)
        put("origin", t.originElevationM)
        put("source", t.source)
        t.resolutionM?.let { put("resolution", it) }
        put("azimuths", JsonArray(t.azimuthsDeg.map(::JsonPrimitive)))
        put("distances", JsonArray(t.distancesM.map(::JsonPrimitive)))
        put("elevations", JsonArray(t.elevations.map { row -> JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }))
    }.toString()

    fun decodeTerrain(text: String): TerrainSamples? = runCatching {
        val o = Json.parseToJsonElement(text) as JsonObject
        TerrainSamples(
            originElevationM = o.d("origin")!!,
            azimuthsDeg = (o["azimuths"] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! },
            distancesM = (o["distances"] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! },
            elevations = (o["elevations"] as JsonArray).map { row -> (row as JsonArray).map { (it as? JsonPrimitive)?.doubleOrNull } },
            source = o.s("source") ?: "?",
            resolutionM = o.d("resolution"),
        )
    }.getOrNull()

    private fun obstacle(o: Obstacle): JsonObject = buildJsonObject {
        put("id", o.id)
        put("type", o.type.name)
        put("shape", shape(o.shape))
        put("height", buildJsonObject {
            o.height.meters?.let { put("m", it) }
            put("source", o.height.source.name)
            o.height.uncertaintyM?.let { put("pm", it) }
            o.height.note?.let { put("note", it) }
        })
        o.baseElevationM?.let { put("base", it) }
        o.name?.let { put("name", it) }
        o.foliage?.let { f ->
            put("foliage", buildJsonObject {
                put("months", JsonArray(f.leafMonths.map { JsonPrimitive(it.value) }))
                put("on", f.transmittanceLeafOn)
                put("off", f.transmittanceLeafOff)
                f.crownBaseM?.let { put("crownBase", it) }
            })
        }
        put("enabled", o.enabled)
        put("source", o.source)
        o.sourceUpdated?.let { put("updated", it.toString()) }
        put("user", o.userDefined)
        o.overridesId?.let { put("overrides", it) }
        put("history", JsonArray(o.history.map { h -> buildJsonObject { put("at", h.at.toString()); put("d", h.description) } }))
    }

    private fun obstacle(j: JsonObject): Obstacle {
        val h = j["height"] as JsonObject
        val source = HeightSource.valueOf(h.s("source")!!)
        val f = j["foliage"] as? JsonObject
        return Obstacle(
            id = j.s("id")!!,
            type = ObstacleType.valueOf(j.s("type")!!),
            shape = shape(j["shape"] as JsonObject),
            height = HeightValue(h.d("m"), source, h.d("pm"), h.s("note")),
            baseElevationM = j.d("base"),
            name = j.s("name"),
            foliage = f?.let {
                Foliage(
                    leafMonths = (it["months"] as JsonArray).map { m -> Month.of((m as JsonPrimitive).content.toInt()) }.toSet(),
                    transmittanceLeafOn = it.d("on")!!, transmittanceLeafOff = it.d("off")!!, crownBaseM = it.d("crownBase"),
                )
            },
            enabled = (j["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            source = j.s("source") ?: "?",
            sourceUpdated = j.s("updated")?.let(Instant::parse),
            userDefined = (j["user"] as? JsonPrimitive)?.booleanOrNull ?: false,
            overridesId = j.s("overrides"),
            history = (j["history"] as? JsonArray)?.map { e -> (e as JsonObject).let { ObstacleChange(Instant.parse(it.s("at")!!), it.s("d")!!) } }.orEmpty(),
        )
    }

    private fun shape(s: ObstacleShape): JsonObject = buildJsonObject {
        when (s) {
            is ObstacleShape.Point -> { put("k", "point"); put("pts", points(listOf(s.at))); put("r", s.radiusM) }
            is ObstacleShape.Line -> { put("k", "line"); put("pts", points(s.points)); put("w", s.widthM) }
            is ObstacleShape.Polygon -> { put("k", "polygon"); put("pts", points(s.outline)) }
            is ObstacleShape.Bearing -> { put("k", "bearing"); put("d", s.distanceM); put("from", s.azimuthFromDeg); put("to", s.azimuthToDeg) }
        }
    }

    private fun shape(j: JsonObject): ObstacleShape {
        fun pts() = (j["pts"] as JsonArray).map { p -> (p as JsonArray).let { LatLon((it[0] as JsonPrimitive).doubleOrNull!!, (it[1] as JsonPrimitive).doubleOrNull!!) } }
        return when (j.s("k")) {
            "point" -> ObstacleShape.Point(pts().single(), j.d("r")!!)
            "line" -> ObstacleShape.Line(pts(), j.d("w")!!)
            "polygon" -> ObstacleShape.Polygon(pts())
            "bearing" -> ObstacleShape.Bearing(j.d("d")!!, j.d("from")!!, j.d("to")!!)
            else -> error("unknown shape")
        }
    }

    private fun points(p: List<LatLon>) = JsonArray(p.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

}

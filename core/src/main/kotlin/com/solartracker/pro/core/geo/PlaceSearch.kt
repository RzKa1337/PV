package com.solartracker.pro.core.geo

import com.solartracker.pro.core.shading.GeocodeResult
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.update.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a place suggestion came from (shown to the user as attribution). */
enum class PlaceSource(val label: String) {
    GOOGLE("Google Maps"),
    OPEN_METEO("Open-Meteo / GeoNames"),
    OPENSTREETMAP("OpenStreetMap Nominatim"),
}

/**
 * One autocomplete suggestion exactly as returned by the provider – never generated locally.
 * [point] is known for providers that return coordinates with the suggestion (Open-Meteo, Nominatim);
 * Google suggestions are resolved with Place Details when picked.
 */
data class PlaceSuggestion(
    val id: String,
    val primary: String,
    /** Region and country (or the rest of the address) – tells apart places with the same name. */
    val secondary: String,
    /** Characters of [primary] that match the typed text (for highlighting). */
    val primaryMatches: List<IntRange>,
    val source: PlaceSource,
    val point: LatLon? = null,
    val elevationM: Double? = null,
    val timezone: String? = null,
)

/** A picked place with provider coordinates. */
data class ResolvedPlace(
    val name: String,
    val detail: String,
    val point: LatLon,
    val elevationM: Double?,
    val timezone: String?,
    val source: PlaceSource,
)

enum class PlaceErrorKind { NETWORK, KEY_MISSING, KEY_REJECTED, QUOTA, BAD_RESPONSE }

class PlaceSearchException(val kind: PlaceErrorKind, message: String, cause: Throwable? = null) : IOException(message, cause)

interface PlaceAutocompleteProvider {
    val source: PlaceSource

    /** Suggestions for [query]; [session] groups the keystrokes of one search (billing session for Google). */
    fun suggest(query: String, session: String, limit: Int): List<PlaceSuggestion>

    /** Coordinates of a picked suggestion. */
    fun resolve(suggestion: PlaceSuggestion, session: String): ResolvedPlace
}

/** Query normalisation and highlighting – locale aware, diacritics-insensitive (ł = l, ą = a, ü = u). */
object PlaceText {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 100

    /** Trimmed query with single spaces, or null when too short to search. */
    fun normalize(query: String): String? {
        val q = query.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        return q.takeIf { it.codePointCount(0, it.length) >= MIN_LENGTH }
    }

    /** Lower-case, diacritics removed, one output char per input char (so indices map back). */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val base = when (c) {
                'ł' -> 'l'; 'Ł' -> 'l'; 'ø' -> 'o'; 'Ø' -> 'o'; 'đ' -> 'd'; 'Đ' -> 'd'; 'ß' -> 's'
                else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
            }
            append(base.lowercaseChar())
        }
    }

    /** Range of [text] matching [query]: a word start is preferred over a match inside a word. */
    fun matches(text: String, query: String): List<IntRange> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val t = fold(text)
        var best = -1
        var from = 0
        while (true) {
            val i = t.indexOf(q, from)
            if (i < 0) break
            if (i == 0 || !t[i - 1].isLetterOrDigit()) { best = i; break }
            if (best < 0) best = i
            from = i + 1
        }
        return if (best < 0) emptyList() else listOf(best until best + q.length)
    }
}

private val json = Json { ignoreUnknownKeys = true }
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun parseObject(body: String): JsonObject =
    runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: throw PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Nieprawidłowa odpowiedź wyszukiwarki")

private fun validPoint(lat: Double?, lon: Double?): LatLon? =
    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null

/**
 * Open-Meteo geocoding (GeoNames data, no key, CC BY 4.0). 2 characters match exactly, 3+ fuzzily – suitable for
 * search-as-you-type. Returns coordinates, elevation and time zone with every suggestion.
 */
class OpenMeteoPlaceProvider(
    private val http: HttpClient,
    private val userAgent: String,
    private val language: String = "pl",
    private val base: String = "https://geocoding-api.open-meteo.com",
) : PlaceAutocompleteProvider {
    override val source = PlaceSource.OPEN_METEO

    override fun suggest(query: String, session: String, limit: Int): List<PlaceSuggestion> {
        val url = "$base/v1/search?count=$limit&language=${enc(language)}&format=json&name=${enc(query)}"
        val response = try {
            http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        } catch (e: IOException) {
            throw PlaceSearchException(PlaceErrorKind.NETWORK, e.message ?: "network", e)
        }
        response.use {
            val body = it.body.bufferedReader().readText()
            if (it.code == 429) throw PlaceSearchException(PlaceErrorKind.QUOTA, "Open-Meteo: limit zapytań (429)")
            if (it.code != 200) throw PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Open-Meteo: HTTP ${it.code}")
            return parse(body, query)
        }
    }

    override fun resolve(suggestion: PlaceSuggestion, session: String): ResolvedPlace {
        val p = suggestion.point ?: throw PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Brak współrzędnych")
        return ResolvedPlace(suggestion.primary, suggestion.secondary, p, suggestion.elevationM, suggestion.timezone, source)
    }

    companion object {
        fun parse(body: String, query: String): List<PlaceSuggestion> {
            val root = parseObject(body)
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                val point = validPoint(o.num("latitude"), o.num("longitude")) ?: return@mapNotNull null
                val secondary = listOfNotNull(o.str("admin1"), o.str("country")).filter { it != name }.distinct().joinToString(", ")
                PlaceSuggestion(
                    id = (o["id"] as? JsonPrimitive)?.content ?: "${point.lat},${point.lon}",
                    primary = name,
                    secondary = secondary,
                    primaryMatches = PlaceText.matches(name, query),
                    source = PlaceSource.OPEN_METEO,
                    point = point,
                    elevationM = o.num("elevation"),
                    timezone = o.str("timezone"),
                )
            }
        }

        /** The existing geocoding result type, for the shading / map screens. */
        fun toGeocodeResults(suggestions: List<PlaceSuggestion>): List<GeocodeResult> = suggestions.mapNotNull { s ->
            val p = s.point ?: return@mapNotNull null
            GeocodeResult(listOf(s.primary, s.secondary).filter { it.isNotBlank() }.joinToString(", "), p,
                com.solartracker.pro.core.shading.LocationAccuracy.CITY, "Open-Meteo Geocoding", s.elevationM, s.timezone)
        }
    }
}

/** Address search results (Nominatim, explicit search only – its policy forbids autocomplete) as suggestions. */
fun GeocodeResult.toSuggestion(query: String): PlaceSuggestion {
    val primary = name.substringBefore(',').trim().ifEmpty { name }
    val secondary = name.substringAfter(',', "").trim()
    return PlaceSuggestion("${point.lat},${point.lon}", primary, secondary, PlaceText.matches(primary, query),
        PlaceSource.OPENSTREETMAP, point, elevationM, timezone)
}

/**
 * Google Places API (New): Autocomplete + Place Details (fields `location,formattedAddress` only – Essentials).
 * The key is restricted to this Android app: requests carry the package name and the signing-certificate SHA-1,
 * which Google checks against the key restriction. Session tokens group the keystrokes and the final details call.
 */
class GooglePlacesProvider(
    private val http: HttpClient,
    private val apiKey: String,
    private val androidPackage: String?,
    private val androidCertSha1: String?,
    private val language: String = "pl",
    private val base: String = "https://places.googleapis.com",
) : PlaceAutocompleteProvider {
    override val source = PlaceSource.GOOGLE

    init {
        if (apiKey.isBlank()) throw PlaceSearchException(PlaceErrorKind.KEY_MISSING, "Brak klucza Google Places")
    }

    private fun headers(extra: Map<String, String>): Map<String, String> = buildMap {
        put("X-Goog-Api-Key", apiKey)
        put("Accept", "application/json")
        androidPackage?.let { put("X-Android-Package", it) }
        androidCertSha1?.let { put("X-Android-Cert", it) }
        putAll(extra)
    }

    override fun suggest(query: String, session: String, limit: Int): List<PlaceSuggestion> {
        val body = buildJsonObject {
            put("input", query)
            put("languageCode", language)
            put("sessionToken", session)
        }.toString()
        val text = call { http.post("$base/v1/places:autocomplete", headers(mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Goog-FieldMask" to "suggestions.placePrediction.placeId,suggestions.placePrediction.structuredFormat,suggestions.placePrediction.text",
        )), body) }
        return parseAutocomplete(text).take(limit)
    }

    override fun resolve(suggestion: PlaceSuggestion, session: String): ResolvedPlace {
        val url = "$base/v1/places/${enc(suggestion.id)}?languageCode=${enc(language)}&sessionToken=${enc(session)}"
        val text = call { http.get(url, headers(mapOf("X-Goog-FieldMask" to "location,formattedAddress"))) }
        val o = parseObject(text)
        val loc = o.obj("location")
        val point = validPoint(loc?.num("latitude"), loc?.num("longitude"))
            ?: throw PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Google: brak współrzędnych miejsca")
        val detail = suggestion.secondary.ifBlank { o.str("formattedAddress").orEmpty() }
        return ResolvedPlace(suggestion.primary, detail, point, null, null, source)
    }

    private inline fun call(request: () -> com.solartracker.pro.core.update.HttpResponse): String {
        val response = try { request() } catch (e: IOException) {
            throw PlaceSearchException(PlaceErrorKind.NETWORK, e.message ?: "network", e)
        }
        response.use {
            val text = it.body.bufferedReader().readText()
            if (it.code == 200) return text
            throw errorFor(it.code, text)
        }
    }

    companion object {
        /** Maps Google error responses; the message never contains the key. */
        fun errorFor(code: Int, body: String): PlaceSearchException {
            val status = runCatching { (json.parseToJsonElement(body) as JsonObject).obj("error")?.str("status") }.getOrNull()
            return when {
                code == 429 || status == "RESOURCE_EXHAUSTED" -> PlaceSearchException(PlaceErrorKind.QUOTA, "Google: przekroczony limit zapytań")
                code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ->
                    PlaceSearchException(PlaceErrorKind.KEY_REJECTED, "Google odrzucił klucz (ograniczenia klucza, włączone API lub rozliczenia)")
                code == 400 && body.contains("API key", ignoreCase = true) ->
                    PlaceSearchException(PlaceErrorKind.KEY_REJECTED, "Google: nieprawidłowy klucz API")
                else -> PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Google: HTTP $code" + (status?.let { " ($it)" } ?: ""))
            }
        }

        fun parseAutocomplete(body: String): List<PlaceSuggestion> {
            val root = parseObject(body)
            val arr = root["suggestions"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val p = (e as? JsonObject)?.obj("placePrediction") ?: return@mapNotNull null
                val id = p.str("placeId") ?: return@mapNotNull null
                val fmt = p.obj("structuredFormat")
                val main = fmt?.obj("mainText") ?: p.obj("text") ?: return@mapNotNull null
                val primary = main.str("text") ?: return@mapNotNull null
                val secondary = fmt?.obj("secondaryText")?.str("text").orEmpty()
                PlaceSuggestion(id, primary, secondary, matchRanges(main, primary.length), PlaceSource.GOOGLE)
            }
        }

        private fun matchRanges(text: JsonObject, length: Int): List<IntRange> {
            val arr = text["matches"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val start = (o["startOffset"] as? JsonPrimitive)?.intOrNull ?: 0
                val end = (o["endOffset"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (start in 0 until end && end <= length) start until end else null
            }
        }
    }
}

/** Suggestions plus the provider that produced them; [notice] says why a fallback provider was used. */
data class SuggestResult(val query: String, val suggestions: List<PlaceSuggestion>, val source: PlaceSource?, val notice: PlaceSearchException? = null)

/**
 * Tries the providers in order (Google first when configured, then the keyless one) and caches answers for
 * [cacheTtl] so retyping or deleting characters does not call the API again. Cancelling stale requests and the
 * debounce are the caller's job (the UI).
 */
class PlaceSearcher(
    private val providers: List<PlaceAutocompleteProvider>,
    private val limit: Int = DEFAULT_LIMIT,
    private val cacheTtl: Duration = Duration.ofMinutes(10),
    private val clock: () -> Instant = { Instant.now() },
) {
    private val cache = object : LinkedHashMap<String, Pair<Instant, SuggestResult>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Instant, SuggestResult>>?) = size > CACHE_SIZE
    }

    val primarySource: PlaceSource? get() = providers.firstOrNull()?.source

    fun suggest(rawQuery: String, session: String): SuggestResult {
        val q = PlaceText.normalize(rawQuery) ?: return SuggestResult(rawQuery.trim(), emptyList(), null)
        val key = PlaceText.fold(q)
        synchronized(cache) {
            cache[key]?.let { (at, r) -> if (Duration.between(at, clock()) < cacheTtl) return r.copy(query = q) }
        }
        var firstError: PlaceSearchException? = null
        for (p in providers) {
            try {
                val result = SuggestResult(q, p.suggest(q, session, limit), p.source, firstError)
                synchronized(cache) { cache[key] = clock() to result }
                return result
            } catch (e: PlaceSearchException) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: PlaceSearchException(PlaceErrorKind.KEY_MISSING, "Brak dostawcy wyszukiwania")
    }

    fun resolve(suggestion: PlaceSuggestion, session: String): ResolvedPlace {
        val provider = providers.firstOrNull { it.source == suggestion.source }
        return when {
            provider != null -> provider.resolve(suggestion, session)
            suggestion.point != null -> ResolvedPlace(suggestion.primary, suggestion.secondary, suggestion.point, suggestion.elevationM, suggestion.timezone, suggestion.source)
            else -> throw PlaceSearchException(PlaceErrorKind.BAD_RESPONSE, "Nieznany dostawca")
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 6
        const val CACHE_SIZE = 50
        /** Typing pause before a request (ms). */
        const val DEBOUNCE_MS = 300L
    }
}

/** A location the user picked before (recent list). */
data class SavedPlace(val name: String, val detail: String, val lat: Double, val lon: Double, val elevationM: Double?, val source: String) {
    fun distanceKm(other: SavedPlace): Double {
        val dLat = (lat - other.lat) * 111.32
        val dLon = (lon - other.lon) * 111.32 * cos(Math.toRadians((lat + other.lat) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}

object RecentPlaces {
    const val MAX = 6

    /** [place] first; the same place (≤ 1 km or same name and detail) is not repeated. */
    fun push(list: List<SavedPlace>, place: SavedPlace, max: Int = MAX): List<SavedPlace> =
        (listOf(place) + list.filterNot { it.distanceKm(place) <= 1.0 || (it.name == place.name && it.detail == place.detail) }).take(max)

    fun encode(list: List<SavedPlace>): String = JsonArray(list.map { p ->
        buildJsonObject {
            put("name", p.name); put("detail", p.detail); put("lat", p.lat); put("lon", p.lon)
            p.elevationM?.let { put("elev", it) }; put("source", p.source)
        }
    }).toString()

    /** Damaged or foreign data gives an empty list (never a crash). */
    fun decode(text: String?): List<SavedPlace> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val p = validPoint(o.num("lat"), o.num("lon")) ?: return@mapNotNull null
            SavedPlace(o.str("name") ?: return@mapNotNull null, o.str("detail").orEmpty(), p.lat, p.lon, o.num("elev"), o.str("source").orEmpty())
        }.take(MAX)
    }
}

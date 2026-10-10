package com.solartracker.pro.core.scenery

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.update.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/**
 * Decorative background photos of sunny places. Purely decorative: the picture is chosen from the local time of day
 * (sun elevation), never from weather or production, so it never suggests that sunshine was detected.
 */
enum class DayPhase(val searchWord: String?) {
    /** Sun from 6° below to 10° above the horizon, before noon. */
    DAWN("sunrise"),
    DAY(null),
    /** Afternoon with the sun below 25°: warm light, long shadows. */
    AFTERNOON(null),
    /** Sun from 6° below to 10° above the horizon, after noon. */
    SUNSET("sunset"),
    /** Sun more than 6° below the horizon (after civil twilight). */
    NIGHT("night");

    companion object {
        fun at(location: GeoLocation, instant: Instant): DayPhase {
            val sun = SolarCalculator.details(location, instant)
            val morning = sun.hourAngleDeg < 0.0
            return when {
                sun.elevationDeg < -6.0 -> NIGHT
                sun.elevationDeg < 10.0 -> if (morning) DAWN else SUNSET
                !morning && sun.elevationDeg < 25.0 -> AFTERNOON
                else -> DAY
            }
        }
    }
}

/** A family of places for one group of screens; each query is a place or motif searched on Wikimedia Commons. */
enum class SceneTheme(val queries: List<String>) {
    COAST(listOf("Santorini Oia", "Amalfi coast", "Didim Altınkum beach", "Aegean coast Turkey", "Greek island village sea", "Algarve coast")),
    VILLAGES(listOf("Andalusia white village", "Palermo", "Sicily coast town", "Provence village", "Tuscany landscape", "Cinque Terre")),
    ISLANDS(listOf("Canary Islands beach", "Lanzarote", "Tenerife coast", "Crete beach", "Mallorca coast", "Madeira coast")),
    DESERT(listOf("Atacama desert", "Sahara dunes", "Wadi Rum", "Fuerteventura dunes", "Death Valley dunes", "Namib desert")),
    SOLAR(listOf("solar park", "photovoltaic power station", "solar panels roof", "solar farm sunset", "photovoltaic system house", "concentrated solar power"));

    /** Search text for Commons (quality-reviewed bitmap photos only). */
    fun searchText(query: String, phase: DayPhase): String {
        // Solar-plant photos at night are rare and dull; keep them by day.
        val word = phase.searchWord?.takeUnless { this == SOLAR && phase == DayPhase.NIGHT }
        return listOfNotNull(query, word).joinToString(" ") + " incategory:Quality_images filetype:bitmap"
    }
}

/** One background photo with everything needed to credit it. */
data class ScenicPhoto(
    /** Commons file title ("File:….jpg"); stable identifier. */
    val title: String,
    /** Scaled JPEG served by Wikimedia (thumbnail of the requested width). */
    val imageUrl: String,
    val width: Int,
    val height: Int,
    /** File description page (author, license, original). */
    val pageUrl: String,
    val author: String,
    val license: String,
    val licenseUrl: String?,
) {
    /** "Author · CC BY-SA 4.0 · Wikimedia Commons" */
    val credit: String get() = "$author · $license · Wikimedia Commons"
    val isPortrait: Boolean get() = height > width
}

class ScenerySearchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Keyless photo search on Wikimedia Commons (official MediaWiki API). Only freely licensed files are kept
 * (CC0, public domain, CC BY, CC BY-SA – no NC/ND, no GFDL-only) and only when an author can be credited.
 */
class WikimediaPhotoSource(
    private val http: HttpClient,
    private val userAgent: String,
    private val endpoint: String = "https://commons.wikimedia.org/w/api.php",
) {
    fun searchUrl(searchText: String, thumbWidth: Int, limit: Int = 20): String =
        "$endpoint?action=query&format=json&formatversion=2&generator=search&gsrnamespace=6" +
            "&gsrlimit=$limit&gsrsearch=${enc(searchText)}&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata" +
            "&iiextmetadatafilter=LicenseShortName%7CLicenseUrl%7CArtist%7CCredit&iiurlwidth=$thumbWidth"

    /** @throws ScenerySearchException on network or HTTP errors */
    fun search(searchText: String, thumbWidth: Int, limit: Int = 20): List<ScenicPhoto> {
        val body = try {
            http.get(searchUrl(searchText, thumbWidth, limit), mapOf("User-Agent" to userAgent, "Accept" to "application/json")).use { r ->
                if (r.code !in 200..299) throw ScenerySearchException("HTTP ${r.code}")
                r.body.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: ScenerySearchException) {
            throw e
        } catch (e: IOException) {
            throw ScenerySearchException(e.message ?: "network", e)
        }
        return parse(body, thumbWidth)
    }

    companion object {
        /** Smallest original width accepted – smaller files look soft as a full-screen background. */
        const val MIN_ORIGINAL_WIDTH = 1600

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun parse(json: String, thumbWidth: Int = 1920): List<ScenicPhoto> {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
            val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonArray ?: return emptyList()
            return pages.mapNotNull { it as? JsonObject }
                .sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
                .mapNotNull { page -> photo(page, thumbWidth) }
        }

        private fun photo(page: JsonObject, thumbWidth: Int): ScenicPhoto? {
            val title = page.str("title") ?: return null
            val info = (page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            if (info.str("mime") != "image/jpeg") return null
            val width = info.int("width") ?: return null
            val height = info.int("height") ?: return null
            if (maxOf(width, height) < MIN_ORIGINAL_WIDTH) return null
            val meta = info["extmetadata"] as? JsonObject ?: return null
            val license = meta.meta("LicenseShortName")?.let(::plainText)?.takeIf { isFreeLicense(it) } ?: return null
            val author = (meta.meta("Artist") ?: meta.meta("Credit"))?.let(::plainText)?.takeIf { it.isNotBlank() }
                ?: if (isPublicDomain(license)) "—" else return null
            val image = info.str("thumburl")?.takeIf { it.startsWith("https://") }
                ?: info.str("url")?.takeIf { it.startsWith("https://") && width <= thumbWidth } ?: return null
            val thumbW = info.int("thumbwidth") ?: width
            val thumbH = info.int("thumbheight") ?: height
            return ScenicPhoto(
                title = title,
                imageUrl = image,
                width = thumbW,
                height = thumbH,
                pageUrl = info.str("descriptionurl") ?: "https://commons.wikimedia.org/wiki/${title.replace(' ', '_')}",
                author = author.take(80),
                license = license,
                licenseUrl = meta.meta("LicenseUrl")?.takeIf { it.startsWith("http") },
            )
        }

        /** CC0, public domain, CC BY and CC BY-SA (any version); NC/ND and GFDL-only are refused. */
        fun isFreeLicense(name: String): Boolean {
            val n = name.lowercase().replace('-', ' ').replace(Regex("\\s+"), " ").trim()
            if ("nc" in n.split(' ') || "nd" in n.split(' ')) return false
            return isPublicDomain(name) || n.startsWith("cc by")
        }

        fun isPublicDomain(name: String): Boolean {
            val n = name.lowercase().trim()
            return n.startsWith("cc0") || n.startsWith("public domain") || n == "pd" || n.startsWith("pd ") || n.startsWith("pd-")
        }

        /** Commons metadata values are HTML ("<a href=…>Name</a>"); keep the text only. */
        fun plainText(html: String): String = html
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.meta(key: String): String? = ((this[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
    }
}

/** Serialization of a photo list for the on-device cache. */
object ScenicPhotoCodec {
    fun encode(list: List<ScenicPhoto>): String = JsonArray(list.map { p ->
        buildJsonObject {
            put("title", p.title); put("image", p.imageUrl); put("w", p.width); put("h", p.height)
            put("page", p.pageUrl); put("author", p.author); put("license", p.license)
            p.licenseUrl?.let { put("licenseUrl", it) }
        }
    }).toString()

    fun decode(text: String?): List<ScenicPhoto> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            runCatching {
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                ScenicPhoto(
                    title = s("title")!!, imageUrl = s("image")!!, width = o["w"]!!.jsonPrimitive.intOrNull!!,
                    height = o["h"]!!.jsonPrimitive.intOrNull!!, pageUrl = s("page")!!, author = s("author")!!,
                    license = s("license")!!, licenseUrl = s("licenseUrl"),
                )
            }.getOrNull()
        }
    }
}

/**
 * Deterministic choice: the same seed always gives the same picture for a screen, so switching tabs never re-draws;
 * a new seed ("change scenery") gives a new set. Screens sharing a theme get different photos when possible.
 */
object SceneryPicker {
    fun queryFor(seed: Long, theme: SceneTheme, slot: Int): String {
        val q = theme.queries
        return q[Math.floorMod(mix(seed, theme.ordinal.toLong() * 31 + slot), q.size.toLong()).toInt()]
    }

    fun pick(seed: Long, key: String, candidates: List<ScenicPhoto>, avoid: Set<String> = emptySet()): ScenicPhoto? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filterNot { it.title in avoid }.ifEmpty { candidates }
        return pool[Math.floorMod(mix(seed, key.hashCode().toLong()), pool.size.toLong()).toInt()]
    }

    /** SplitMix64 finaliser: small seed changes give unrelated choices. */
    fun mix(seed: Long, salt: Long): Long {
        var z = seed + salt * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}

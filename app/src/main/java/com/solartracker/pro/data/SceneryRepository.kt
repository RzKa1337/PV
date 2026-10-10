package com.solartracker.pro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.util.LruCache
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.scenery.DayPhase
import com.solartracker.pro.core.scenery.SceneTheme
import com.solartracker.pro.core.scenery.SceneryPicker
import com.solartracker.pro.core.scenery.ScenicPhoto
import com.solartracker.pro.core.scenery.ScenicPhotoCodec
import com.solartracker.pro.core.scenery.WikimediaPhotoSource
import com.solartracker.pro.core.update.UrlConnectionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.random.Random

/** A background picture ready to draw, with the photo it came from (for the credit line). */
class LoadedScenery(val photo: ScenicPhoto, val bitmap: Bitmap)

/**
 * Background photos of sunny places from Wikimedia Commons (keyless, freely licensed, credited on screen).
 *
 * - One random **seed** defines the whole set: every screen gets its photo from it, so switching tabs never re-draws;
 *   "change scenery" draws a new seed.
 * - Search results are kept per theme and time of day for [LIST_MAX_AGE_MS]; photos are stored in the app's files
 *   (up to [DISK_BUDGET_BYTES], least recently used removed first) and reused offline.
 * - Every failure returns a cached photo or null (the caller then shows the built-in illustration) – it never
 *   throws into the UI and never touches PV data.
 */
class SceneryRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("scenery", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "scenery")
    private val photosDir = File(root, "photos")
    private val listsDir = File(root, "lists")
    private val http = UrlConnectionHttpClient(connectTimeoutMs = 8_000, readTimeoutMs = 15_000)
    private val userAgent = "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android; https://github.com/RzKa1337/PV)"
    private val source = WikimediaPhotoSource(http, userAgent)
    private val mutex = Mutex()
    private val decoded = object : LruCache<String, Bitmap>(3) {}

    /** Photo shown per screen key and time of day for the current seed (screens sharing a theme avoid repeats). */
    private val inUse = HashMap<String, ScenicPhoto>()

    private val _seed = MutableStateFlow(prefs.getLong(KEY_SEED, 0L).takeIf { it != 0L } ?: newSeed())
    val seed: StateFlow<Long> = _seed.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** Photos on (default) or only the built-in illustrations (no network use). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private fun newSeed(): Long = Random.nextLong().let { if (it == 0L) 1L else it }.also { prefs.edit().putLong(KEY_SEED, it).apply() }

    /** "Change scenery": a new set for every screen. */
    fun reshuffle() {
        synchronized(inUse) { inUse.clear() }
        _seed.value = newSeed()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /** Last picture shown for [key], if still decoded in memory (lets a tab switch show it in the first frame). */
    fun cached(key: String, phase: DayPhase): LoadedScenery? {
        val photo = synchronized(inUse) { inUse[memoryKey(key, phase)] } ?: return null
        val bitmap = decoded.get(photo.imageUrl) ?: return null
        return LoadedScenery(photo, bitmap)
    }

    /**
     * Picture for screen [key] (theme [theme], [slot] = its position among screens of that theme) at [phase],
     * decoded for a [targetWidth]×[targetHeight] screen. Null when nothing is available (offline, first start).
     */
    suspend fun load(key: String, theme: SceneTheme, slot: Int, phase: DayPhase, targetWidth: Int, targetHeight: Int): LoadedScenery? =
        withContext(Dispatchers.IO) {
            if (!enabled.value) return@withContext null
            runCatching {
                mutex.withLock {
                    val seedNow = seed.value
                    val candidates = candidates(theme, phase, seedNow, slot)
                    val avoid = synchronized(inUse) { inUse.filterKeys { !it.startsWith("$key|") }.values.map { it.title }.toSet() }
                    // Prefer photos already on the device, so offline or on a slow network the set still varies.
                    val chosen = SceneryPicker.pick(seedNow, "$key|${phase.name}", candidates, avoid)
                    val file = chosen?.let { fileFor(it) }
                    val ready = when {
                        chosen == null -> null
                        file!!.exists() -> chosen
                        download(chosen, file) -> chosen
                        else -> null
                    } ?: offlineFallback(theme, avoid) ?: return@withLock null
                    val bitmap = decode(ready, targetWidth, targetHeight) ?: return@withLock null
                    synchronized(inUse) { inUse[memoryKey(key, phase)] = ready }
                    LoadedScenery(ready, bitmap)
                }
            }.getOrNull()
        }

    private fun memoryKey(key: String, phase: DayPhase) = "$key|${phase.name}"

    /** Search results for the seed's query; tries the theme's other places when one finds nothing usable. */
    private fun candidates(theme: SceneTheme, phase: DayPhase, seed: Long, slot: Int): List<ScenicPhoto> {
        val first = SceneryPicker.queryFor(seed, theme, slot)
        val queries = listOf(first) + theme.queries.filter { it != first }.shuffled(Random(seed xor slot.toLong()))
        var fetches = 0
        for (q in queries) {
            val cached = readList(theme, phase, q)
            val fresh = cached != null && System.currentTimeMillis() - cached.first < (if (cached.second.isEmpty()) EMPTY_RETRY_MS else LIST_MAX_AGE_MS)
            if (fresh) {
                if (cached!!.second.isNotEmpty()) return cached.second
                continue
            }
            if (fetches >= MAX_SEARCHES_PER_LOAD) return cached?.second.orEmpty()
            fetches++
            val found = runCatching { source.search(theme.searchText(q, phase), THUMB_WIDTH) }.getOrNull()
            if (found == null) {
                // Offline or refused: keep using an older list if there is one.
                if (!cached?.second.isNullOrEmpty()) return cached!!.second
                return emptyList()
            }
            writeList(theme, phase, q, found)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Any photo of the theme already on the device (offline), else any stored photo at all. */
    private fun offlineFallback(theme: SceneTheme, avoid: Set<String>): ScenicPhoto? {
        val stored = listsDir.listFiles().orEmpty()
            .sortedByDescending { it.name.startsWith("${theme.name}_") }
            .flatMap { ScenicPhotoCodec.decode(runCatching { it.readText() }.getOrNull()?.substringAfter('\n')) }
            .filter { fileFor(it).exists() }
            .distinctBy { it.title }
        return stored.firstOrNull { it.title !in avoid } ?: stored.firstOrNull()
    }

    private fun listFile(theme: SceneTheme, phase: DayPhase, query: String) =
        File(listsDir, "${theme.name}_${phase.name}_${sha1(query).take(12)}.json")

    /** (saved at, photos) or null. File format: first line = epoch millis, then the JSON list. */
    private fun readList(theme: SceneTheme, phase: DayPhase, query: String): Pair<Long, List<ScenicPhoto>>? {
        val f = listFile(theme, phase, query)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val at = text.substringBefore('\n').toLongOrNull() ?: return null
        return at to ScenicPhotoCodec.decode(text.substringAfter('\n'))
    }

    private fun writeList(theme: SceneTheme, phase: DayPhase, query: String, photos: List<ScenicPhoto>) {
        listsDir.mkdirs()
        runCatching { listFile(theme, phase, query).writeText("${System.currentTimeMillis()}\n${ScenicPhotoCodec.encode(photos)}") }
    }

    private fun fileFor(photo: ScenicPhoto) = File(photosDir, sha1(photo.imageUrl) + ".jpg")

    /** Downloads one photo (Wikimedia hosts only, size-capped), then trims the store to the disk budget. */
    private fun download(photo: ScenicPhoto, target: File): Boolean {
        val url = imageUrlForNetwork(photo.imageUrl)
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (!host.endsWith(".wikimedia.org") || !url.startsWith("https://")) return false
        photosDir.mkdirs()
        val tmp = File(photosDir, target.name + ".part")
        val ok = runCatching { fetchTo(url, tmp) && isImage(tmp) && tmp.renameTo(target) }.getOrDefault(false)
        if (!ok) tmp.delete()
        if (ok) trimDisk()
        return ok
    }

    private fun fetchTo(url: String, out: File): Boolean = http.get(url, mapOf("User-Agent" to userAgent)).use { r ->
        if (r.code != 200) return false
        out.outputStream().use { sink ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = r.body.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_PHOTO_BYTES) return false
                sink.write(buffer, 0, n)
            }
        }
        true
    }

    private fun isImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0
    }

    /** Smaller standard thumbnail on metered (mobile) networks. */
    private fun imageUrlForNetwork(url: String): String {
        val metered = runCatching { (app.getSystemService(ConnectivityManager::class.java)).isActiveNetworkMetered }.getOrDefault(true)
        return if (metered) url.replace("/${THUMB_WIDTH}px-", "/${METERED_THUMB_WIDTH}px-") else url
    }

    private fun trimDisk() {
        val files = photosDir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEach { f ->
            total += f.length()
            if (total > DISK_BUDGET_BYTES) f.delete()
        }
    }

    /** Decodes for the screen size (power-of-two subsampling keeps memory low) and marks the file as recently used. */
    private fun decode(photo: ScenicPhoto, w: Int, h: Int): Bitmap? {
        decoded.get(photo.imageUrl)?.let { return it }
        val file = fileFor(photo)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return null
        }
        // Cover-crop shows the image scaled by `scale`; decoding at 1/sample is lossless while sample ≤ 1/scale.
        val scale = maxOf(w.toFloat() / bounds.outWidth, h.toFloat() / bounds.outHeight).coerceAtLeast(1e-3f)
        var sample = 1
        while (sample * 2 <= 1f / scale && sample < 8) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        file.setLastModified(System.currentTimeMillis())
        decoded.put(photo.imageUrl, bitmap)
        return bitmap
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_SEED = "seed"
        private const val KEY_ENABLED = "enabled"
        /** Standard Wikimedia thumbnail widths (other widths are rendered on demand and may be refused). */
        const val THUMB_WIDTH = 1920
        const val METERED_THUMB_WIDTH = 1280
        private const val MAX_PHOTO_BYTES = 6L * 1024 * 1024
        private const val DISK_BUDGET_BYTES = 40L * 1024 * 1024
        private const val LIST_MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val EMPTY_RETRY_MS = 24L * 3600 * 1000
        private const val MAX_SEARCHES_PER_LOAD = 3

        @Volatile private var instance: SceneryRepository? = null
        fun get(context: Context): SceneryRepository =
            instance ?: synchronized(this) { instance ?: SceneryRepository(context).also { instance = it } }
    }
}

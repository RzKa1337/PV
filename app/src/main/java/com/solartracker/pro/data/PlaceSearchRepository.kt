package com.solartracker.pro.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.geo.GooglePlacesProvider
import com.solartracker.pro.core.geo.OpenMeteoPlaceProvider
import com.solartracker.pro.core.geo.PlaceAutocompleteProvider
import com.solartracker.pro.core.geo.PlaceErrorKind
import com.solartracker.pro.core.geo.PlaceSearchException
import com.solartracker.pro.core.geo.PlaceSearcher
import com.solartracker.pro.core.geo.PlaceSource
import com.solartracker.pro.core.geo.PlaceSuggestion
import com.solartracker.pro.core.geo.ResolvedPlace
import com.solartracker.pro.core.geo.SavedPlace
import com.solartracker.pro.core.geo.SuggestResult
import com.solartracker.pro.core.geo.toSuggestion
import com.solartracker.pro.core.shading.NominatimLocationProvider
import com.solartracker.pro.core.shading.OpenMeteoTerrainProvider
import com.solartracker.pro.core.update.UrlConnectionHttpClient
import com.solartracker.pro.energy.SecretStore
import com.solartracker.pro.i18n.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/**
 * City / address search for the location setting. Google Places (New) is used when a key is configured (in the
 * app or at build time); otherwise – and as a fallback when Google refuses the key or the quota is used up – the
 * keyless Open-Meteo geocoder. Nominatim is called only for an explicit search (its policy forbids autocomplete).
 * Coordinates always come from the provider, never from parsing the typed text.
 */
class PlaceSearchRepository(context: Context) {
    private val app = context.applicationContext
    private val http = UrlConnectionHttpClient(connectTimeoutMs = 8_000, readTimeoutMs = 10_000)
    private val userAgent = "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android; github.com/RzKa1337/PV)"
    private val prefs = app.getSharedPreferences("place_search", Context.MODE_PRIVATE)
    private val certSha1: String? by lazy { signingCertSha1(app) }

    @Volatile private var cached: Pair<String, PlaceSearcher>? = null

    /** Key typed in the app (encrypted with the Android Keystore), else the build-time key, else none. */
    private fun apiKey(): String =
        prefs.getString(KEY_GOOGLE, null)?.let { SecretStore.decrypt(it) }?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MAPS_API_KEY

    val hasUserKey: Boolean get() = !prefs.getString(KEY_GOOGLE, null).isNullOrEmpty()
    val googleConfigured: Boolean get() = apiKey().isNotBlank()

    fun setUserKey(key: String) {
        val clean = key.trim()
        val editor = prefs.edit()
        if (clean.isEmpty()) editor.remove(KEY_GOOGLE) else editor.putString(KEY_GOOGLE, SecretStore.encrypt(clean))
        editor.apply()
        cached = null
    }

    private fun language(): String = if (AppLocale.isEnglish) "en" else "pl"

    private fun searcher(): PlaceSearcher {
        val key = apiKey()
        val signature = "${key.hashCode()}|${language()}"
        cached?.let { (sig, s) -> if (sig == signature) return s }
        val providers = buildList<PlaceAutocompleteProvider> {
            if (key.isNotBlank()) add(GooglePlacesProvider(http, key, app.packageName, certSha1, language()))
            add(OpenMeteoPlaceProvider(http, userAgent, language()))
        }
        return PlaceSearcher(providers).also { cached = signature to it }
    }

    /** New billing/session token for one search (Google groups keystrokes + the details call). */
    fun newSession(): String = UUID.randomUUID().toString()

    suspend fun suggest(query: String, session: String): SuggestResult = withContext(Dispatchers.IO) {
        searcher().suggest(query, session)
    }

    /** Explicit search (keyboard "search"): autocomplete first, then addresses / postcodes from OpenStreetMap. */
    suspend fun searchNow(query: String, session: String): SuggestResult = withContext(Dispatchers.IO) {
        val first = runCatching { searcher().suggest(query, session) }
        first.getOrNull()?.takeIf { it.suggestions.isNotEmpty() }?.let { return@withContext it }
        val osm = runCatching { NominatimLocationProvider(http, userAgent).search(query.trim(), 6) }
        osm.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
            return@withContext SuggestResult(query.trim(), list.map { it.toSuggestion(query) }, PlaceSource.OPENSTREETMAP, first.getOrNull()?.notice)
        }
        first.getOrNull() ?: throw (first.exceptionOrNull() ?: PlaceSearchException(PlaceErrorKind.NETWORK, "network"))
    }

    /** Coordinates of the picked suggestion; the elevation comes from Open-Meteo (Copernicus DEM) when missing. */
    suspend fun resolve(suggestion: PlaceSuggestion, session: String): SavedPlace = withContext(Dispatchers.IO) {
        val r: ResolvedPlace = searcher().resolve(suggestion, session)
        val elevation = r.elevationM ?: runCatching {
            OpenMeteoTerrainProvider(http, userAgent).elevations(listOf(r.point)).firstOrNull()
        }.getOrNull()
        SavedPlace(r.name, r.detail, r.point.lat, r.point.lon, elevation, r.source.name)
    }

    companion object {
        private const val KEY_GOOGLE = "google_places_key"

        /** SHA-1 of the signing certificate as Google expects it in X-Android-Cert (hex, upper case, no colons). */
        @Suppress("DEPRECATION")
        fun signingCertSha1(context: Context): String? = runCatching {
            val pm = context.packageManager
            val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            } ?: return null
            MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }
}

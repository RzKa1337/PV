package com.solartracker.pro.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.geo.GooglePlacesProvider
import com.solartracker.pro.core.geo.OpenMeteoPlaceProvider
import com.solartracker.pro.core.geo.PhotonPlaceProvider
import com.solartracker.pro.core.shading.LatLon
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
 * Coordinates come from the provider – or are the user's own typed coordinates / pasted map link, never parsed
 * out of a place name.
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

    /**
     * Google (with a key) → Photon / OpenStreetMap (towns, regions, deserts, mountains, streets, addresses, POIs) →
     * Open-Meteo (GeoNames towns). A provider that finds nothing hands over to the next one. [near] (the current
     * location) only ranks nearby results higher.
     */
    private fun searcher(near: LatLon? = null): PlaceSearcher {
        val key = apiKey()
        val bias = near?.let { LatLon(Math.round(it.lat * 10) / 10.0, Math.round(it.lon * 10) / 10.0) }
        val signature = "${key.hashCode()}|${language()}|$bias"
        cached?.let { (sig, s) -> if (sig == signature) return s }
        val providers = buildList<PlaceAutocompleteProvider> {
            if (key.isNotBlank()) add(GooglePlacesProvider(http, key, app.packageName, certSha1, language()))
            // Photon knows en/de/fr; "default" gives local names (Desierto de Atacama, Warszawa).
            add(PhotonPlaceProvider(http, userAgent, if (AppLocale.isEnglish) "en" else "default", bias))
            add(OpenMeteoPlaceProvider(http, userAgent, language()))
        }
        return PlaceSearcher(providers).also { cached = signature to it }
    }

    /** New billing/session token for one search (Google groups keystrokes + the details call). */
    fun newSession(): String = UUID.randomUUID().toString()

    suspend fun suggest(query: String, session: String, near: LatLon? = null): SuggestResult = withContext(Dispatchers.IO) {
        searcher(near).suggest(query, session)
    }

    /**
     * Explicit, thorough search (keyboard "search" / "search more"): OpenStreetMap Nominatim with the full query in the
     * app language (understands "Atakama, Chile", "Pustynia Atakama", addresses, postcodes) first, then the
     * autocomplete results that are not already there. Nominatim is only called on this explicit action.
     */
    suspend fun searchNow(query: String, session: String, near: LatLon? = null): SuggestResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        val osm = runCatching { NominatimLocationProvider(http, userAgent, language = language()).search(q, 6).map { it.toSuggestion(q) } }
        val auto = runCatching { searcher(near).suggest(q, session) }
        val osmList = osm.getOrNull().orEmpty()
        val autoList = auto.getOrNull()?.suggestions.orEmpty().filterNot { a ->
            val p = a.point
            p != null && osmList.any { o -> o.point!!.let { kotlin.math.abs(it.lat - p.lat) < 0.01 && kotlin.math.abs(it.lon - p.lon) < 0.01 } }
        }
        val merged = (osmList + autoList).take(PlaceSearcher.DEFAULT_LIMIT + 2)
        when {
            merged.isNotEmpty() -> SuggestResult(q, merged, if (osmList.isNotEmpty()) PlaceSource.OPENSTREETMAP else auto.getOrNull()?.source, auto.getOrNull()?.notice)
            auto.isSuccess -> auto.getOrThrow()
            else -> throw (auto.exceptionOrNull() as? PlaceSearchException ?: osm.exceptionOrNull()?.let { PlaceSearchException(PlaceErrorKind.NETWORK, it.message ?: "network", it) }
                ?: PlaceSearchException(PlaceErrorKind.NETWORK, "network"))
        }
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

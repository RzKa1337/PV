package com.solartracker.pro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Local persistence of [AppSettings]. Values are validated on both read and write. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs.toSettings() }

    suspend fun updateSystem(transform: (PvSystem) -> PvSystem) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings().system).sanitized()
            prefs[Keys.PEAK_POWER] = updated.peakPowerKw
            prefs[Keys.TILT] = updated.tiltDeg
            prefs[Keys.AZIMUTH] = updated.azimuthDeg
        }
    }

    suspend fun setLocation(location: GeoLocation, name: String, source: LocationSource) {
        dataStore.edit { prefs ->
            prefs[Keys.LATITUDE] = location.latitude
            prefs[Keys.LONGITUDE] = location.longitude
            prefs[Keys.LOCATION_NAME] = name.trim().take(MAX_NAME_LENGTH)
            prefs[Keys.LOCATION_SOURCE] = source.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val system = PvSystem(
            peakPowerKw = this[Keys.PEAK_POWER] ?: defaults.system.peakPowerKw,
            tiltDeg = this[Keys.TILT] ?: defaults.system.tiltDeg,
            azimuthDeg = this[Keys.AZIMUTH] ?: defaults.system.azimuthDeg,
        ).sanitized()
        val lat = this[Keys.LATITUDE]?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = this[Keys.LONGITUDE]?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val hasLocation = lat != null && lon != null
        return AppSettings(
            system = system,
            location = if (hasLocation) GeoLocation(lat!!, lon!!) else defaults.location,
            locationName = if (hasLocation) this[Keys.LOCATION_NAME].orEmpty() else defaults.locationName,
            locationSource = enumValueOrDefault(this[Keys.LOCATION_SOURCE], defaults.locationSource),
            themeMode = enumValueOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val PEAK_POWER = doublePreferencesKey("peak_power_kw")
        val TILT = doublePreferencesKey("tilt_deg")
        val AZIMUTH = doublePreferencesKey("azimuth_deg")
        val LATITUDE = doublePreferencesKey("latitude")
        val LONGITUDE = doublePreferencesKey("longitude")
        val LOCATION_NAME = stringPreferencesKey("location_name")
        val LOCATION_SOURCE = stringPreferencesKey("location_source")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

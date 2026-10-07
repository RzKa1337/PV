package com.solartracker.pro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionPeriod
import com.solartracker.pro.core.energy.EnergyPrices
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
            prefs[Keys.ELEVATION] = location.elevationM
            prefs[Keys.LOCATION_NAME] = name.trim().take(MAX_NAME_LENGTH)
            prefs[Keys.LOCATION_SOURCE] = source.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { prefs -> prefs[Keys.LANGUAGE] = language.name }
    }

    /**
     * Saves the battery configuration. An invalid configuration is rejected (nothing is saved).
     * @return true when saved
     */
    suspend fun setBattery(enabled: Boolean, battery: BatteryStorage): Boolean {
        if (!battery.isValid) return false
        dataStore.edit { prefs ->
            prefs[Keys.BATTERY_ENABLED] = enabled
            prefs[Keys.BAT_CAPACITY] = battery.nominalCapacityKwh
            prefs[Keys.BAT_USABLE] = battery.usableCapacityPercent
            prefs[Keys.BAT_INITIAL_SOC] = battery.initialSocPercent
            prefs[Keys.BAT_MIN_SOC] = battery.minSocPercent
            prefs[Keys.BAT_MAX_SOC] = battery.maxSocPercent
            prefs[Keys.BAT_CHARGE_KW] = battery.maxChargePowerKw
            prefs[Keys.BAT_DISCHARGE_KW] = battery.maxDischargePowerKw
            prefs[Keys.BAT_CHARGE_EFF] = battery.chargeEfficiencyPercent
            prefs[Keys.BAT_DISCHARGE_EFF] = battery.dischargeEfficiencyPercent
            prefs[Keys.BAT_TYPE] = battery.type.name
        }
        return true
    }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.WEATHER_ENABLED] = enabled }
    }

    suspend fun setBatteryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BATTERY_ENABLED] = enabled }
    }

    /** Saves the consumption profile; invalid settings are rejected. @return true when saved */
    suspend fun setConsumption(consumption: ConsumptionSettings): Boolean {
        if (consumption.validate().isNotEmpty()) return false
        dataStore.edit { prefs ->
            prefs[Keys.CONSUMPTION_MODE] = consumption.mode.name
            prefs[Keys.CONSUMPTION_CONSTANT_KW] = consumption.constantKw
            prefs[Keys.CONSUMPTION_PERIODS] = encodePeriods(consumption.periods)
        }
        return true
    }

    /** Saves optional prices; negative or non-finite values are rejected. @return true when saved */
    suspend fun setPrices(prices: EnergyPrices): Boolean {
        val values = listOf(prices.gridPricePerKwh, prices.generatorPricePerKwh, prices.feedInPricePerKwh, prices.batteryCost)
        if (values.any { it != null && (!it.isFinite() || it < 0.0) }) return false
        dataStore.edit { prefs ->
            prefs.setOrRemove(Keys.PRICE_GRID, prices.gridPricePerKwh)
            prefs.setOrRemove(Keys.PRICE_GENERATOR, prices.generatorPricePerKwh)
            prefs.setOrRemove(Keys.PRICE_FEED_IN, prices.feedInPricePerKwh)
            prefs.setOrRemove(Keys.PRICE_BATTERY, prices.batteryCost)
            prefs[Keys.BACKUP_SOURCE] = prices.backupSource.name
        }
        return true
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Double>, value: Double?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun Preferences.toBattery(): BatteryStorage {
        val d = BatteryStorage()
        val stored = BatteryStorage(
            nominalCapacityKwh = this[Keys.BAT_CAPACITY] ?: d.nominalCapacityKwh,
            usableCapacityPercent = this[Keys.BAT_USABLE] ?: d.usableCapacityPercent,
            initialSocPercent = this[Keys.BAT_INITIAL_SOC] ?: d.initialSocPercent,
            minSocPercent = this[Keys.BAT_MIN_SOC] ?: d.minSocPercent,
            maxSocPercent = this[Keys.BAT_MAX_SOC] ?: d.maxSocPercent,
            maxChargePowerKw = this[Keys.BAT_CHARGE_KW] ?: d.maxChargePowerKw,
            maxDischargePowerKw = this[Keys.BAT_DISCHARGE_KW] ?: d.maxDischargePowerKw,
            chargeEfficiencyPercent = this[Keys.BAT_CHARGE_EFF] ?: d.chargeEfficiencyPercent,
            dischargeEfficiencyPercent = this[Keys.BAT_DISCHARGE_EFF] ?: d.dischargeEfficiencyPercent,
            type = enumValueOrDefault(this[Keys.BAT_TYPE], d.type),
        )
        return if (stored.isValid) stored else d
    }

    private fun Preferences.toConsumption(): ConsumptionSettings {
        val d = ConsumptionSettings()
        val stored = ConsumptionSettings(
            mode = enumValueOrDefault(this[Keys.CONSUMPTION_MODE], d.mode),
            constantKw = this[Keys.CONSUMPTION_CONSTANT_KW] ?: d.constantKw,
            periods = this[Keys.CONSUMPTION_PERIODS]?.let(::decodePeriods) ?: d.periods,
        )
        return if (stored.validate().isEmpty()) stored else d
    }

    private fun Preferences.toPrices(): EnergyPrices {
        fun price(key: Preferences.Key<Double>) = this[key]?.takeIf { it.isFinite() && it >= 0.0 }
        return EnergyPrices(
            gridPricePerKwh = price(Keys.PRICE_GRID),
            generatorPricePerKwh = price(Keys.PRICE_GENERATOR),
            feedInPricePerKwh = price(Keys.PRICE_FEED_IN),
            batteryCost = price(Keys.PRICE_BATTERY),
            backupSource = enumValueOrDefault(this[Keys.BACKUP_SOURCE], BackupSource.GRID),
        )
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
            location = if (hasLocation) {
                val elevation = this[Keys.ELEVATION]?.takeIf { it.isFinite() && it in -500.0..9000.0 } ?: 0.0
                GeoLocation(lat!!, lon!!, elevation)
            } else {
                defaults.location
            },
            locationName = if (hasLocation) this[Keys.LOCATION_NAME].orEmpty() else defaults.locationName,
            locationSource = enumValueOrDefault(this[Keys.LOCATION_SOURCE], defaults.locationSource),
            themeMode = enumValueOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
            language = enumValueOrDefault(this[Keys.LANGUAGE], defaults.language),
            batteryEnabled = this[Keys.BATTERY_ENABLED] ?: defaults.batteryEnabled,
            battery = toBattery(),
            consumption = toConsumption(),
            prices = toPrices(),
            weatherEnabled = this[Keys.WEATHER_ENABLED] ?: defaults.weatherEnabled,
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
        val ELEVATION = doublePreferencesKey("elevation_m")
        val LOCATION_NAME = stringPreferencesKey("location_name")
        val LOCATION_SOURCE = stringPreferencesKey("location_source")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("app_language")
        val BATTERY_ENABLED = booleanPreferencesKey("battery_enabled")
        val BAT_CAPACITY = doublePreferencesKey("battery_capacity_kwh")
        val BAT_USABLE = doublePreferencesKey("battery_usable_percent")
        val BAT_INITIAL_SOC = doublePreferencesKey("battery_initial_soc")
        val BAT_MIN_SOC = doublePreferencesKey("battery_min_soc")
        val BAT_MAX_SOC = doublePreferencesKey("battery_max_soc")
        val BAT_CHARGE_KW = doublePreferencesKey("battery_charge_kw")
        val BAT_DISCHARGE_KW = doublePreferencesKey("battery_discharge_kw")
        val BAT_CHARGE_EFF = doublePreferencesKey("battery_charge_eff")
        val BAT_DISCHARGE_EFF = doublePreferencesKey("battery_discharge_eff")
        val BAT_TYPE = stringPreferencesKey("battery_type")
        val CONSUMPTION_MODE = stringPreferencesKey("consumption_mode")
        val CONSUMPTION_CONSTANT_KW = doublePreferencesKey("consumption_constant_kw")
        val CONSUMPTION_PERIODS = stringPreferencesKey("consumption_periods")
        val PRICE_GRID = doublePreferencesKey("price_grid")
        val PRICE_GENERATOR = doublePreferencesKey("price_generator")
        val PRICE_FEED_IN = doublePreferencesKey("price_feed_in")
        val PRICE_BATTERY = doublePreferencesKey("price_battery")
        val BACKUP_SOURCE = stringPreferencesKey("backup_source")
        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
    }

    companion object {
        const val MAX_NAME_LENGTH = 60

        /** Encodes periods as "0-6:0.5;6-10:1.0" (dot decimal, locale independent). */
        internal fun encodePeriods(periods: List<ConsumptionPeriod>): String =
            periods.joinToString(";") { "${it.startHour}-${it.endHour}:${it.powerKw}" }

        internal fun decodePeriods(text: String): List<ConsumptionPeriod>? = runCatching {
            text.split(';').filter { it.isNotBlank() }.map { part ->
                val (range, power) = part.split(':')
                val (start, end) = range.split('-')
                ConsumptionPeriod(start.trim().toInt(), end.trim().toInt(), power.trim().toDouble())
            }
        }.getOrNull()
    }
}

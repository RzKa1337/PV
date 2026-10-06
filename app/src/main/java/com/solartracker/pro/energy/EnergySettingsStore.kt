package com.solartracker.pro.energy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.solartracker.pro.core.ems.EmsCodec
import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.ems.FlexibleLoad
import com.solartracker.pro.core.ems.GeneratorConfig
import com.solartracker.pro.core.ems.validate
import com.solartracker.pro.core.inverter.InverterBrand
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterLink
import com.solartracker.pro.core.inverter.InverterProtocol
import com.solartracker.pro.core.shading.LocationAccuracy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.energyDataStore: DataStore<Preferences> by preferencesDataStore(name = "energy")

/** PV field layout and location metadata needed by the shading model. */
data class SiteConfig(
    val locationAccuracy: LocationAccuracy = LocationAccuracy.CITY,
    val locationConfirmed: Boolean = false,
    val panelCount: Int = 4,
    val rows: Int = 1,
    val panelModel: String = "",
    val panelWidthM: Double = 1.13,
    val panelLengthM: Double = 1.72,
    val panelBaseHeightM: Double = 0.5,
    val strings: Int = 1,
    val mpptCount: Int = 1,
    val bypassDiodes: Int = 3,
    val obstacleRadiusM: Int = 150,
    /** Battery nominal voltage (48 V for the Anenji 6.2 kW). */
    val batteryVoltage: Double = 48.0,
    /** Off-grid installations cannot export (needed to explain curtailment). */
    val gridExportAllowed: Boolean = false,
) {
    val columns: Int get() = (panelCount + rows - 1) / rows

    fun validate(): List<String> = buildList {
        if (panelCount !in 1..500) add("Liczba paneli 1–500")
        if (rows !in 1..50 || rows > panelCount) add("Liczba rzędów 1–liczba paneli")
        if (panelWidthM !in 0.3..3.0 || panelLengthM !in 0.3..3.0) add("Wymiary panelu 0,3–3 m")
        if (panelBaseHeightM !in 0.0..200.0) add("Wysokość paneli 0–200 m")
        if (strings !in 1..20 || strings > panelCount) add("Liczba stringów 1–liczba paneli")
        if (mpptCount !in 1..8) add("MPPT 1–8")
        if (obstacleRadiusM !in 30..1000) add("Promień przeszkód 30–1000 m")
    }
}

class EnergySettingsStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.energyDataStore)

    private val prefs = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val inverter: Flow<InverterConfig> = prefs.map { p ->
        val d = InverterConfig()
        InverterConfig(
            enabled = p[K.ENABLED] ?: d.enabled,
            brand = InverterBrand.entries.firstOrNull { it.name == p[K.BRAND] } ?: d.brand,
            model = p[K.MODEL] ?: d.model,
            ratedPowerW = p[K.RATED] ?: d.ratedPowerW,
            mpptCount = p[K.MPPT] ?: d.mpptCount,
            protocol = InverterProtocol.entries.firstOrNull { it.name == p[K.PROTOCOL] } ?: d.protocol,
            link = InverterLink.entries.firstOrNull { it.name == p[K.LINK] } ?: d.link,
            host = p[K.HOST] ?: d.host,
            port = p[K.PORT] ?: d.port,
            slaveId = p[K.SLAVE] ?: d.slaveId,
            pollIntervalSeconds = p[K.POLL] ?: d.pollIntervalSeconds,
        )
    }

    val site: Flow<SiteConfig> = prefs.map { p ->
        val d = SiteConfig()
        val s = SiteConfig(
            locationAccuracy = LocationAccuracy.entries.firstOrNull { it.name == p[K.ACCURACY] } ?: d.locationAccuracy,
            locationConfirmed = p[K.CONFIRMED] ?: d.locationConfirmed,
            panelCount = p[K.PANELS] ?: d.panelCount,
            rows = p[K.ROWS] ?: d.rows,
            panelModel = p[K.PANEL_MODEL] ?: d.panelModel,
            panelWidthM = p[K.PANEL_W] ?: d.panelWidthM,
            panelLengthM = p[K.PANEL_L] ?: d.panelLengthM,
            panelBaseHeightM = p[K.PANEL_H] ?: d.panelBaseHeightM,
            strings = p[K.STRINGS] ?: d.strings,
            mpptCount = p[K.SITE_MPPT] ?: d.mpptCount,
            bypassDiodes = p[K.BYPASS] ?: d.bypassDiodes,
            obstacleRadiusM = p[K.RADIUS] ?: d.obstacleRadiusM,
            batteryVoltage = p[K.BAT_V] ?: d.batteryVoltage,
            gridExportAllowed = p[K.EXPORT] ?: d.gridExportAllowed,
        )
        if (s.validate().isEmpty()) s else d.copy(locationAccuracy = s.locationAccuracy, locationConfirmed = s.locationConfirmed)
    }

    /** User-defined flexible loads and the generator for EMS recommendations. */
    val flexibleLoads: Flow<List<FlexibleLoad>> = prefs.map { EmsCodec.decodeLoads(it[K.EMS_LOADS]) }
    val generator: Flow<GeneratorConfig?> = prefs.map { EmsCodec.decodeGenerator(it[K.EMS_GENERATOR]) }

    /** Optional large cooling load (cold room); added to the load forecast while there is no measured history. */
    val cooling: Flow<CoolingLoadProfile?> = prefs.map { EmsCodec.decodeCooling(it[K.COOLING]) }

    suspend fun setCooling(c: CoolingLoadProfile?) {
        dataStore.edit { it[K.COOLING] = EmsCodec.encodeCooling(c?.takeIf { p -> p.validate().isEmpty() }) }
    }

    suspend fun setFlexibleLoads(loads: List<FlexibleLoad>) {
        dataStore.edit { it[K.EMS_LOADS] = EmsCodec.encodeLoads(loads.filter { l -> l.validate().isEmpty() }.take(20)) }
    }

    suspend fun setGenerator(g: GeneratorConfig?) {
        dataStore.edit { it[K.EMS_GENERATOR] = EmsCodec.encodeGenerator(g?.takeIf { c -> c.validate().isEmpty() }) }
    }

    suspend fun inverterNow() = inverter.first()
    suspend fun siteNow() = site.first()

    suspend fun setInverter(c: InverterConfig) {
        dataStore.edit { p ->
            p[K.ENABLED] = c.enabled; p[K.BRAND] = c.brand.name; p[K.MODEL] = c.model.take(60); p[K.RATED] = c.ratedPowerW
            p[K.MPPT] = c.mpptCount; p[K.PROTOCOL] = c.protocol.name; p[K.LINK] = c.link.name; p[K.HOST] = c.host.trim()
            p[K.PORT] = c.port; p[K.SLAVE] = c.slaveId; p[K.POLL] = c.pollIntervalSeconds
        }
    }

    suspend fun setSite(s: SiteConfig) {
        dataStore.edit { p ->
            p[K.ACCURACY] = s.locationAccuracy.name; p[K.CONFIRMED] = s.locationConfirmed; p[K.PANELS] = s.panelCount; p[K.ROWS] = s.rows
            p[K.PANEL_MODEL] = s.panelModel.take(60); p[K.PANEL_W] = s.panelWidthM; p[K.PANEL_L] = s.panelLengthM; p[K.PANEL_H] = s.panelBaseHeightM
            p[K.STRINGS] = s.strings; p[K.SITE_MPPT] = s.mpptCount; p[K.BYPASS] = s.bypassDiodes; p[K.RADIUS] = s.obstacleRadiusM
            p[K.BAT_V] = s.batteryVoltage; p[K.EXPORT] = s.gridExportAllowed
        }
    }

    private object K {
        val ENABLED = booleanPreferencesKey("inv_enabled")
        val BRAND = stringPreferencesKey("inv_brand")
        val MODEL = stringPreferencesKey("inv_model")
        val RATED = doublePreferencesKey("inv_rated_w")
        val MPPT = intPreferencesKey("inv_mppt")
        val PROTOCOL = stringPreferencesKey("inv_protocol")
        val LINK = stringPreferencesKey("inv_link")
        val HOST = stringPreferencesKey("inv_host")
        val PORT = intPreferencesKey("inv_port")
        val SLAVE = intPreferencesKey("inv_slave")
        val POLL = intPreferencesKey("inv_poll_s")
        val ACCURACY = stringPreferencesKey("site_accuracy")
        val CONFIRMED = booleanPreferencesKey("site_confirmed")
        val PANELS = intPreferencesKey("site_panels")
        val ROWS = intPreferencesKey("site_rows")
        val PANEL_MODEL = stringPreferencesKey("site_panel_model")
        val PANEL_W = doublePreferencesKey("site_panel_w")
        val PANEL_L = doublePreferencesKey("site_panel_l")
        val PANEL_H = doublePreferencesKey("site_panel_h")
        val STRINGS = intPreferencesKey("site_strings")
        val SITE_MPPT = intPreferencesKey("site_mppt")
        val BYPASS = intPreferencesKey("site_bypass")
        val RADIUS = intPreferencesKey("site_radius")
        val BAT_V = doublePreferencesKey("site_battery_v")
        val EXPORT = booleanPreferencesKey("site_export")
        val EMS_LOADS = stringPreferencesKey("ems_loads")
        val EMS_GENERATOR = stringPreferencesKey("ems_generator")
        val COOLING = stringPreferencesKey("cooling_profile")
    }
}

package com.solartracker.pro.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.solartracker.pro.core.update.CheckInterval
import com.solartracker.pro.core.update.UpdateChannel
import com.solartracker.pro.core.update.UpdateConfig
import com.solartracker.pro.core.update.UpdateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/** Separate from the user settings; excluded from cloud backup because it may hold the user's token. */
private val Context.updaterDataStore: DataStore<Preferences> by preferencesDataStore(name = UpdateStore.NAME)

data class UpdateSnapshot(val config: UpdateConfig, val state: UpdateState)

class UpdateStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.updaterDataStore)

    val data: Flow<UpdateSnapshot> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { UpdateSnapshot(it.toConfig(), it.toState()) }

    suspend fun snapshot(): UpdateSnapshot = data.first()

    suspend fun setConfig(config: UpdateConfig) {
        dataStore.edit { p ->
            p[Keys.ENABLED] = config.enabled
            p[Keys.OWNER] = config.owner
            p[Keys.REPO] = config.repo
            p[Keys.CHANNEL] = config.channel.name
            p[Keys.INTERVAL] = config.interval.name
            p[Keys.AUTO_DOWNLOAD] = config.autoDownload
            p[Keys.AUTO_INSTALL] = config.autoInstall
            p[Keys.WIFI_ONLY] = config.wifiOnly
            if (config.token.isBlank()) p.remove(Keys.TOKEN) else p[Keys.TOKEN] = config.token.trim()
        }
    }

    suspend fun updateState(transform: (UpdateState) -> UpdateState) {
        dataStore.edit { p ->
            val s = transform(p.toState())
            if (s.lastCheck == null) p.remove(Keys.LAST_CHECK) else p[Keys.LAST_CHECK] = s.lastCheck!!.toEpochMilli()
            if (s.snoozedUntil == null) p.remove(Keys.SNOOZED_UNTIL) else p[Keys.SNOOZED_UNTIL] = s.snoozedUntil!!.toEpochMilli()
            if (s.snoozedVersion == null) p.remove(Keys.SNOOZED_VERSION) else p[Keys.SNOOZED_VERSION] = s.snoozedVersion!!
            p[Keys.SKIPPED] = s.skippedVersions.joinToString(",")
            p[Keys.BAD] = s.badVersions.joinToString(",")
        }
    }

    private fun Preferences.toConfig(): UpdateConfig {
        val d = UpdateConfig()
        val stored = UpdateConfig(
            enabled = this[Keys.ENABLED] ?: d.enabled,
            owner = this[Keys.OWNER] ?: d.owner,
            repo = this[Keys.REPO] ?: d.repo,
            channel = UpdateChannel.entries.firstOrNull { it.name == this[Keys.CHANNEL] } ?: d.channel,
            interval = CheckInterval.entries.firstOrNull { it.name == this[Keys.INTERVAL] } ?: d.interval,
            autoDownload = this[Keys.AUTO_DOWNLOAD] ?: d.autoDownload,
            autoInstall = this[Keys.AUTO_INSTALL] ?: d.autoInstall,
            wifiOnly = this[Keys.WIFI_ONLY] ?: d.wifiOnly,
            token = this[Keys.TOKEN].orEmpty(),
        )
        return if (stored.validate().isEmpty()) stored else d.copy(token = stored.token.filterNot { it.isWhitespace() })
    }

    private fun Preferences.toState() = UpdateState(
        lastCheck = this[Keys.LAST_CHECK]?.let(Instant::ofEpochMilli),
        snoozedUntil = this[Keys.SNOOZED_UNTIL]?.let(Instant::ofEpochMilli),
        snoozedVersion = this[Keys.SNOOZED_VERSION],
        skippedVersions = this[Keys.SKIPPED].toSet(),
        badVersions = this[Keys.BAD].toSet(),
    )

    private fun String?.toSet(): Set<String> = this?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val CHANNEL = stringPreferencesKey("channel")
        val INTERVAL = stringPreferencesKey("interval")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        val AUTO_INSTALL = booleanPreferencesKey("auto_install")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val TOKEN = stringPreferencesKey("token")
        val LAST_CHECK = longPreferencesKey("last_check")
        val SNOOZED_UNTIL = longPreferencesKey("snoozed_until")
        val SNOOZED_VERSION = stringPreferencesKey("snoozed_version")
        val SKIPPED = stringPreferencesKey("skipped_versions")
        val BAD = stringPreferencesKey("bad_versions")
    }

    companion object {
        const val NAME = "updater"
    }
}

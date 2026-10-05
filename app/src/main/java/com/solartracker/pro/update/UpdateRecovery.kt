package com.solartracker.pro.update

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Protects user data across an update and detects a broken new version.
 *
 * Before installing, the settings file is copied to no-backup storage and a marker with the target
 * version is written. The new version is "on probation" until it has run in the foreground for a
 * while ([markHealthy]). If it crashes [MAX_CRASHES] times before that, the settings are restored from
 * the copy, the version is reported as bad and the user is told how to go back (Android never lets
 * an app downgrade itself). A missing or empty settings file after the update is also restored.
 */
class UpdateRecovery(private val filesDir: File, noBackupDir: File) {

    constructor(context: Context) : this(context.filesDir, context.noBackupFilesDir)

    data class Probation(val versionCode: Long, val version: String, val fromVersion: String, val crashes: Int)

    sealed interface StartResult {
        data object Normal : StartResult
        data class OnProbation(val probation: Probation) : StartResult
        data class SettingsRestored(val probation: Probation) : StartResult
        data class RolledBack(val probation: Probation) : StartResult
    }

    private val dir = File(noBackupDir, "update-backup")
    private val marker = File(dir, "probation.properties")
    private val settingsFile get() = File(filesDir, "datastore/$SETTINGS_FILE")
    private val settingsBackup = File(dir, SETTINGS_FILE)

    /** Called right before handing the APK to the installer. */
    @Synchronized
    fun prepare(targetVersionCode: Long, targetVersion: String, fromVersion: String) {
        dir.mkdirs()
        if (settingsFile.isFile) settingsFile.copyTo(settingsBackup, overwrite = true) else settingsBackup.delete()
        write(Probation(targetVersionCode, targetVersion, fromVersion, 0))
    }

    /** Called first thing in Application.onCreate, before anything opens the settings DataStore. */
    @Synchronized
    fun onAppStart(currentVersionCode: Long): StartResult {
        val p = read() ?: return StartResult.Normal
        if (p.versionCode != currentVersionCode) {
            // The install did not happen (cancelled/failed) or is older: nothing to watch.
            if (p.versionCode < currentVersionCode) clear()
            return StartResult.Normal
        }
        if (p.crashes >= MAX_CRASHES) {
            restoreSettings()
            clear()
            return StartResult.RolledBack(p)
        }
        if (settingsBackup.isFile && (!settingsFile.isFile || settingsFile.length() == 0L)) {
            restoreSettings()
            return StartResult.SettingsRestored(p)
        }
        return StartResult.OnProbation(p)
    }

    /** Uncaught exception while on probation. Must be fast and synchronous. */
    @Synchronized
    fun recordCrash(currentVersionCode: Long) {
        val p = read() ?: return
        if (p.versionCode == currentVersionCode) write(p.copy(crashes = p.crashes + 1))
    }

    /** The new version runs fine: drop the marker and the settings copy. @return the probation that ended */
    @Synchronized
    fun markHealthy(currentVersionCode: Long): Probation? {
        val p = read()?.takeIf { it.versionCode == currentVersionCode } ?: return null
        clear()
        return p
    }

    /** The installation did not happen: drop the marker and the settings copy. */
    @Synchronized
    fun abandon() = clear()

    private fun restoreSettings() {
        if (!settingsBackup.isFile) return
        settingsFile.parentFile?.mkdirs()
        settingsBackup.copyTo(settingsFile, overwrite = true)
    }

    private fun clear() {
        marker.delete()
        settingsBackup.delete()
    }

    private fun read(): Probation? = runCatching {
        if (!marker.isFile) return null
        val props = Properties().apply { marker.inputStream().use(::load) }
        Probation(
            versionCode = props.getProperty("versionCode").toLong(),
            version = props.getProperty("version"),
            fromVersion = props.getProperty("fromVersion", ""),
            crashes = props.getProperty("crashes", "0").toInt(),
        )
    }.getOrNull()

    private fun write(p: Probation) {
        dir.mkdirs()
        val props = Properties().apply {
            setProperty("versionCode", p.versionCode.toString())
            setProperty("version", p.version)
            setProperty("fromVersion", p.fromVersion)
            setProperty("crashes", p.crashes.toString())
        }
        val tmp = File(dir, "probation.tmp")
        tmp.outputStream().use { props.store(it, null) }
        tmp.renameTo(marker)
    }

    companion object {
        const val MAX_CRASHES = 2
        const val SETTINGS_FILE = "settings.preferences_pb"
    }
}

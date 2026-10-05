package com.solartracker.pro.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.MainActivity
import com.solartracker.pro.core.update.ApkIdentityCheck
import com.solartracker.pro.core.update.CancelledException
import com.solartracker.pro.core.update.Checksums
import com.solartracker.pro.core.update.DownloadProgress
import com.solartracker.pro.core.update.Downloader
import com.solartracker.pro.core.update.GitHubClient
import com.solartracker.pro.core.update.SemanticVersion
import com.solartracker.pro.core.update.UpdateCandidate
import com.solartracker.pro.core.update.UpdateCheckResult
import com.solartracker.pro.core.update.UpdateConfig
import com.solartracker.pro.core.update.UpdateEngine
import com.solartracker.pro.core.update.UpdateLog
import com.solartracker.pro.core.update.UpdateLogEntry
import com.solartracker.pro.core.update.UpdatePolicy
import com.solartracker.pro.core.update.UrlConnectionHttpClient
import com.solartracker.pro.core.update.VerifiedDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/** What the update screen shows. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val at: Instant) : UpdateStatus
    data class Available(val candidate: UpdateCandidate) : UpdateStatus
    data class Downloading(val candidate: UpdateCandidate, val progress: DownloadProgress?) : UpdateStatus
    data class ReadyToInstall(val download: VerifiedDownload) : UpdateStatus
    data class NeedsInstallPermission(val download: VerifiedDownload) : UpdateStatus
    data class Installing(val version: String) : UpdateStatus
    data class NotInstallable(val version: String, val reason: String) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

/**
 * Orchestrates the update flow on Android: check → download → verify (SHA-256 + APK identity) →
 * backup → install → confirm health. Every step is written to the update log.
 */
class UpdateManager(
    private val context: Context,
    val store: UpdateStore,
    private val recovery: UpdateRecovery,
    private val scope: CoroutineScope,
    private val isAppInForeground: () -> Boolean,
) {
    val currentVersion: SemanticVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME) ?: SemanticVersion(0, 0, 0)
    private val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

    private val logFile = File(context.noBackupFilesDir, "update-log.txt")
    private val log = UpdateLog(capacity = 300).apply { runCatching { if (logFile.isFile) restore(logFile.readText()) } }
    private val downloadDir = File(context.noBackupFilesDir, "updates")
    private val http = UrlConnectionHttpClient()
    private val engine = UpdateEngine(GitHubClient(http), Downloader(http, log = log), log, downloadDir)
    private val installer = ApkInstaller(context)
    private val mutex = Mutex()
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow(log.entries())
    val logEntries: StateFlow<List<UpdateLogEntry>> = _log.asStateFlow()

    private fun publishLog() {
        _log.value = log.entries()
        runCatching { logFile.writeText(log.serialize()) }
    }

    private fun logInfo(message: String) = log.info(message).also { publishLog() }
    private fun logWarn(message: String) = log.warn(message).also { publishLog() }
    private fun logError(message: String) = log.error(message).also { publishLog() }

    // ---- start-up / recovery ----

    fun onAppStart(result: UpdateRecovery.StartResult) {
        when (result) {
            UpdateRecovery.StartResult.Normal -> Unit
            is UpdateRecovery.StartResult.OnProbation ->
                logInfo("Uruchomiono wersję ${result.probation.version} po aktualizacji z ${result.probation.fromVersion} – sprawdzanie stabilności")
            is UpdateRecovery.StartResult.SettingsRestored ->
                logWarn("Po aktualizacji do ${result.probation.version} brakowało ustawień – przywrócono kopię sprzed aktualizacji")
            is UpdateRecovery.StartResult.RolledBack -> {
                val p = result.probation
                logError(
                    "Wersja ${p.version} uległa awarii ${p.crashes}× po aktualizacji – przywrócono ustawienia sprzed " +
                        "aktualizacji i oznaczono wersję jako wadliwą. Android nie pozwala na samodzielny powrót do " +
                        "starszej wersji: zainstaluj ${p.fromVersion} ręcznie z GitHuba.",
                )
                scope.launch {
                    store.updateState { UpdatePolicy.markBad(it, p.version) }
                    val config = store.snapshot().config
                    UpdateNotifications.show(
                        context,
                        "Wersja ${p.version} nie działa poprawnie",
                        "Przywrócono Twoje ustawienia. Dotknij, aby pobrać poprzednią wersję ${p.fromVersion}.",
                        UpdateNotifications.browserIntent(context, "https://github.com/${config.owner}/${config.repo}/releases"),
                    )
                }
            }
        }
        cleanStaleDownloads()
    }

    fun recordCrash() = recovery.recordCrash(currentVersionCode)

    /** The app has been in the foreground long enough after an update. */
    fun markHealthy() {
        recovery.markHealthy(currentVersionCode)?.let { p ->
            logInfo("Aktualizacja ${p.fromVersion} → ${p.version} zakończona powodzeniem, aplikacja działa stabilnie")
            engine.cleanDownloads()
        }
    }

    private fun cleanStaleDownloads() {
        // Downloads of versions that are not newer than the installed one are useless.
        downloadDir.listFiles()?.filter { f ->
            val v = Regex("""v?(\d+\.\d+\.\d+[^-]*)""").find(f.name)?.groupValues?.get(1)?.let(SemanticVersion::parse)
            v != null && v <= currentVersion
        }?.forEach { it.delete() }
    }

    // ---- configuration ----

    suspend fun saveConfig(config: UpdateConfig): List<String> {
        val errors = config.validate()
        if (errors.isNotEmpty()) return errors
        store.setConfig(config)
        UpdateWorker.schedule(context, config)
        logInfo(
            "Zapisano ustawienia: ${config.owner}/${config.repo}, kanał ${config.channel}, co ${config.interval}, " +
                "auto-pobieranie ${config.autoDownload}, auto-instalacja ${config.autoInstall}, tylko Wi-Fi ${config.wifiOnly}, " +
                "token ${if (config.token.isBlank()) "brak" else "ustawiony"}",
        )
        return emptyList()
    }

    // ---- user actions ----

    fun checkNow() {
        scope.launch { runCheck(manual = true) }
    }

    fun download() {
        val candidate = when (val s = _status.value) {
            is UpdateStatus.Available -> s.candidate
            is UpdateStatus.Error, is UpdateStatus.Idle -> return checkNow()
            else -> return
        }
        downloadJob = scope.launch { downloadAndVerify(candidate, notify = false) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun install() {
        val download = when (val s = _status.value) {
            is UpdateStatus.ReadyToInstall -> s.download
            is UpdateStatus.NeedsInstallPermission -> s.download
            else -> return
        }
        scope.launch { install(download) }
    }

    fun permissionSettingsIntent(): Intent = installer.permissionSettingsIntent()

    fun snooze(version: String) {
        scope.launch {
            store.updateState { UpdatePolicy.snooze(it, version, Instant.now(), Duration.ofDays(1)) }
            logInfo("Odłożono wersję $version na 24 h")
            _status.value = UpdateStatus.Idle
        }
    }

    fun skip(version: String) {
        scope.launch {
            store.updateState { UpdatePolicy.skip(it, version) }
            logInfo("Pominięto wersję $version (nie będzie proponowana automatycznie)")
            engine.cleanDownloads()
            _status.value = UpdateStatus.Idle
        }
    }

    fun clearIgnored() {
        scope.launch {
            store.updateState { it.copy(skippedVersions = emptySet(), badVersions = emptySet(), snoozedVersion = null, snoozedUntil = null) }
            logInfo("Wyczyszczono listę pominiętych i wadliwych wersji")
        }
    }

    // ---- background ----

    /** Periodic work: check when due, then download/install according to the configuration. */
    suspend fun backgroundRun() {
        val (config, state) = store.snapshot()
        if (!UpdatePolicy.isCheckDue(config, state, Instant.now())) return
        val candidate = (runCheck(manual = false) as? UpdateCheckResult.Available)?.candidate ?: return
        if (!config.autoDownload) {
            UpdateNotifications.show(context, "Dostępna aktualizacja ${candidate.version}", "Dotknij, aby pobrać i zainstalować.")
            return
        }
        val verified = downloadAndVerify(candidate, notify = true) ?: return
        if (config.autoInstall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && installer.canInstall()) {
            install(verified)
        } else {
            UpdateNotifications.show(
                context,
                "Aktualizacja ${candidate.version} gotowa",
                "Pobrano i zweryfikowano. Dotknij, aby zainstalować.",
            )
        }
    }

    // ---- steps ----

    private suspend fun runCheck(manual: Boolean): UpdateCheckResult? = mutex.withLock {
        val (config, state) = store.snapshot()
        if (!manual && !config.enabled) return null
        _status.value = UpdateStatus.Checking
        val now = Instant.now()
        val result = try {
            withContext(Dispatchers.IO) {
                engine.check(config, state, currentVersion, Build.SUPPORTED_ABIS.toList(), now)
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            logError("Sprawdzanie nie powiodło się: $message")
            _status.value = UpdateStatus.Error(message)
            return null
        } finally {
            publishLog()
        }
        store.updateState { it.copy(lastCheck = now) }
        _status.value = when (result) {
            UpdateCheckResult.UpToDate -> UpdateStatus.UpToDate(now)
            is UpdateCheckResult.Available -> UpdateStatus.Available(result.candidate)
            is UpdateCheckResult.NotInstallable -> UpdateStatus.NotInstallable(result.release.tagName, result.reason)
        }
        result
    }

    private suspend fun downloadAndVerify(candidate: UpdateCandidate, notify: Boolean): VerifiedDownload? = mutex.withLock {
        val config = store.snapshot().config
        _status.value = UpdateStatus.Downloading(candidate, null)
        var lastPercent = -1
        try {
            val verified = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                engine.downloadAndVerify(candidate, config, isCancelled = { !job.isActive }) { p ->
                    _status.value = UpdateStatus.Downloading(candidate, p)
                    val percent = p.fraction?.let { (it * 100).toInt() }
                    if (notify && percent != null && percent != lastPercent && percent % 5 == 0) {
                        lastPercent = percent
                        UpdateNotifications.progress(context, "Pobieranie aktualizacji ${candidate.version}", percent)
                    }
                }
            }
            val problem = ApkIdentityCheck.problem(
                ApkInspector.installed(context),
                ApkInspector.archive(context, verified.file),
                candidate.version,
            )
            if (problem != null) {
                verified.file.delete()
                logError("Weryfikacja APK nie powiodła się: $problem – plik usunięty, wersja ${candidate.version} oznaczona jako niebezpieczna")
                store.updateState { UpdatePolicy.markBad(it, candidate.version.toString()) }
                _status.value = UpdateStatus.NotInstallable(candidate.version.toString(), problem)
                return null
            }
            logInfo("APK zweryfikowane: pakiet, podpis i wersja ${candidate.version} zgodne")
            _status.value = UpdateStatus.ReadyToInstall(verified)
            verified
        } catch (e: CancelledException) {
            logWarn("Pobieranie anulowane – zostanie wznowione przy następnej próbie")
            _status.value = UpdateStatus.Available(candidate)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            logWarn("Pobieranie anulowane – zostanie wznowione przy następnej próbie")
            _status.value = UpdateStatus.Available(candidate)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            logError("Pobieranie/weryfikacja nie powiodły się: $message")
            _status.value = UpdateStatus.Error(message)
            null
        } finally {
            if (notify) UpdateNotifications.cancel(context, UpdateNotifications.ID_PROGRESS)
            publishLog()
        }
    }

    private suspend fun install(download: VerifiedDownload) = mutex.withLock {
        val version = download.candidate.version.toString()
        if (!installer.canInstall()) {
            logWarn("Brak zgody na instalowanie aplikacji – otwórz ustawienia i zezwól")
            _status.value = UpdateStatus.NeedsInstallPermission(download)
            return@withLock
        }
        try {
            withContext(Dispatchers.IO) {
                // Re-check right before installing: the file must still be exactly what was verified.
                val sha = Checksums.sha256(download.file)
                check(Checksums.matches(download.sha256, sha)) { "Plik zmienił się po weryfikacji" }
                val identity = ApkInspector.archive(context, download.file)
                    ?: throw IllegalStateException("Nie można odczytać APK")
                recovery.prepare(identity.versionCode, version, currentVersion.toString())
                logInfo("Kopia ustawień zapisana; instalacja $version (${download.file.length()} B)")
                installer.install(download.file)
            }
            _status.value = UpdateStatus.Installing(version)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            logError("Nie udało się rozpocząć instalacji: $message")
            _status.value = UpdateStatus.Error(message)
        }
    }

    // ---- callbacks from receivers ----

    fun onUserActionRequired(confirm: Intent?) {
        logInfo("Instalator systemu prosi o potwierdzenie")
        if (confirm == null) {
            logError("Brak ekranu potwierdzenia instalacji")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isAppInForeground()) {
            runCatching { context.startActivity(confirm) }.onFailure { logError("Nie można otworzyć instalatora: ${it.message}") }
        } else {
            UpdateNotifications.show(
                context,
                "Aktualizacja gotowa do instalacji",
                "Dotknij, aby potwierdzić instalację.",
                UpdateNotifications.activityIntent(context, confirm),
            )
        }
    }

    suspend fun onInstallResult(status: Int, message: String?) {
        val current = _status.value
        val version = (current as? UpdateStatus.Installing)?.version ?: "?"
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> logInfo("Instalacja zakończona powodzeniem")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                logWarn("Instalacja anulowana przez użytkownika – obecna wersja pozostaje bez zmian")
                recovery.abandon()
                _status.value = UpdateStatus.Idle
            }
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "zablokowana przez system"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "konflikt z zainstalowaną aplikacją (np. inny podpis)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "niezgodna z urządzeniem"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "nieprawidłowy plik APK"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "brak miejsca"
                    else -> "błąd $status"
                }
                logError("Instalacja nie powiodła się: $reason${message?.let { " ($it)" } ?: ""}. Poprzednia wersja działa dalej bez zmian")
                recovery.abandon() // no new version was installed – nothing to watch
                if (status in setOf(
                        PackageInstaller.STATUS_FAILURE_CONFLICT,
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                        PackageInstaller.STATUS_FAILURE_INVALID,
                    ) && version != "?"
                ) {
                    store.updateState { UpdatePolicy.markBad(it, version) }
                    logWarn("Wersja $version oznaczona jako wadliwa")
                }
                _status.value = UpdateStatus.Error("Instalacja nie powiodła się: $reason")
            }
        }
    }

    fun onPackageReplaced() {
        logInfo("Aplikacja zaktualizowana do ${currentVersion} (versionCode $currentVersionCode)")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Older Android allows restarting directly.
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            UpdateNotifications.show(context, "Zaktualizowano do $currentVersion", "Dotknij, aby uruchomić nową wersję.")
        }
    }
}

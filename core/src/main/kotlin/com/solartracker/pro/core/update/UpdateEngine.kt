package com.solartracker.pro.core.update

import java.io.File
import java.time.Instant

/** A downloaded APK whose SHA-256 matched the checksum published with the release. */
data class VerifiedDownload(val candidate: UpdateCandidate, val file: File, val sha256: String)

/**
 * Platform-independent update flow: check GitHub releases, download the chosen artifact and the
 * checksum file, verify the SHA-256. Unverified files are deleted and never returned.
 * Platform checks (APK signature, package name, version code) and installation are done by the app.
 */
class UpdateEngine(
    private val github: GitHubClient,
    private val downloader: Downloader,
    private val log: UpdateLog,
    private val downloadDir: File,
) {
    fun check(
        config: UpdateConfig,
        state: UpdateState,
        currentVersion: SemanticVersion,
        supportedAbis: List<String>,
        now: Instant,
    ): UpdateCheckResult {
        val errors = config.validate()
        if (errors.isNotEmpty()) throw UpdateException(errors.joinToString())
        log.info("Sprawdzanie aktualizacji: ${config.owner}/${config.repo}, kanał ${config.channel}, wersja $currentVersion")
        val releases = github.listReleases(config)
        val result = UpdateSelector.select(
            releases, currentVersion, config.channel, supportedAbis, UpdatePolicy.ignoredVersions(state, now),
        )
        when (result) {
            UpdateCheckResult.UpToDate -> log.info("Brak nowszej wersji (${releases.size} wydań)")
            is UpdateCheckResult.Available -> log.info("Dostępna wersja ${result.candidate.version}: ${result.candidate.apk.name}")
            is UpdateCheckResult.NotInstallable -> log.warn("Wersja ${result.release.tagName} pominięta: ${result.reason}")
        }
        return result
    }

    fun downloadAndVerify(
        candidate: UpdateCandidate,
        config: UpdateConfig,
        isCancelled: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
    ): VerifiedDownload {
        downloadDir.mkdirs()
        val headers = github.assetHeaders(config)
        log.info("Pobieranie sum kontrolnych ${candidate.checksums.name}")
        val sumsFile = File(downloadDir, "${candidate.version}-${Checksums.FILE_NAME}")
        downloader.download(candidate.checksums.apiUrl, headers, sumsFile, candidate.checksums.size, isCancelled)
        val expected = Checksums.parse(sumsFile.readText())[candidate.apk.name]
        sumsFile.delete()
        if (expected == null) {
            log.error("Brak sumy kontrolnej dla ${candidate.apk.name}")
            throw UpdateException("Plik ${Checksums.FILE_NAME} nie zawiera sumy dla ${candidate.apk.name}")
        }

        val apk = File(downloadDir, candidate.apk.name)
        repeat(2) { round ->
            if (apk.exists() && round == 0) {
                // A previous run may already have downloaded it.
                val existing = Checksums.sha256(apk)
                if (Checksums.matches(expected, existing)) {
                    log.info("Plik ${apk.name} już pobrany i zweryfikowany")
                    return VerifiedDownload(candidate, apk, existing)
                }
                apk.delete()
            }
            log.info("Pobieranie ${candidate.apk.name} (${candidate.apk.size} B)")
            downloader.download(candidate.apk.apiUrl, headers, apk, candidate.apk.size, isCancelled, onProgress)
            val actual = Checksums.sha256(apk)
            if (Checksums.matches(expected, actual)) {
                log.info("SHA-256 zgodna: $actual")
                return VerifiedDownload(candidate, apk, actual)
            }
            log.error("SHA-256 niezgodna (oczekiwano $expected, jest $actual) – plik usunięty")
            apk.delete()
        }
        throw UpdateException("Pobrany plik ma niezgodną sumę kontrolną – aktualizacja przerwana")
    }

    /** Removes downloaded files other than [keep] (e.g. after a successful install). */
    fun cleanDownloads(keep: File? = null) {
        downloadDir.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }
}

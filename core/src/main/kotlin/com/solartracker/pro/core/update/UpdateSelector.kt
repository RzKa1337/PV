package com.solartracker.pro.core.update

enum class UpdateChannel { STABLE, BETA }

/** A newer release with the artifact chosen for this device and its checksum file. */
data class UpdateCandidate(
    val release: GitHubRelease,
    val version: SemanticVersion,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult

    /** A newer version exists but cannot be installed safely (e.g. no checksum file). */
    data class NotInstallable(val release: GitHubRelease, val reason: String) : UpdateCheckResult
}

/**
 * Chooses the update to offer: the newest non-draft release of the [UpdateChannel] that is newer
 * than the installed version and not skipped/blocked by the user, with an APK for this device and a
 * [Checksums.FILE_NAME] file. Unverifiable releases are never offered for installation.
 */
object UpdateSelector {

    fun select(
        releases: List<GitHubRelease>,
        currentVersion: SemanticVersion,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        ignoredVersions: Set<String> = emptySet(),
    ): UpdateCheckResult {
        val newest = releases
            .asSequence()
            .filter { !it.draft }
            .mapNotNull { r -> r.version?.let { v -> r to v } }
            // The stable channel ignores releases marked as pre-release and pre-release versions.
            .filter { (r, v) -> channel == UpdateChannel.BETA || (!r.preRelease && !v.isPreRelease) }
            .filter { (_, v) -> v > currentVersion }
            .filter { (_, v) -> v.toString() !in ignoredVersions }
            .maxByOrNull { (_, v) -> v }
            ?: return UpdateCheckResult.UpToDate

        val (release, version) = newest
        val apk = selectApk(release.assets, supportedAbis)
            ?: return UpdateCheckResult.NotInstallable(release, "Brak pliku APK dla tego urządzenia")
        val sums = release.assets.firstOrNull { it.name == Checksums.FILE_NAME }
            ?: return UpdateCheckResult.NotInstallable(release, "Wydanie nie zawiera sum kontrolnych (${Checksums.FILE_NAME})")
        return UpdateCheckResult.Available(UpdateCandidate(release, version, apk, sums))
    }

    /**
     * APK selection by platform: an ABI-specific APK (`…-arm64-v8a.apk`) for the first matching
     * supported ABI in the device's preference order, otherwise `…-universal.apk`, otherwise the
     * only APK of the release.
     */
    fun selectApk(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }?.let { return it }
        return apks.singleOrNull()
    }
}

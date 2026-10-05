package com.solartracker.pro.core.update

/** What the app reads from an APK (or the installed app): package, version and signing certificates. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    /** SHA-256 (lowercase hex) of each signing certificate. */
    val signerSha256: Set<String>,
)

/**
 * Platform check done after the SHA-256 checksum matched and before installation: the APK must be the
 * same app, signed with exactly the same certificate(s) as the installed one, newer, and its version
 * name must match the release it was downloaded from.
 */
object ApkIdentityCheck {

    /** @return null when the APK may be installed, otherwise the reason (Polish, for the user/log) */
    fun problem(installed: ApkIdentity, candidate: ApkIdentity?, expectedVersion: SemanticVersion): String? {
        if (candidate == null) return "Plik nie jest poprawnym APK"
        if (candidate.packageName != installed.packageName) {
            return "Inna aplikacja (${candidate.packageName})"
        }
        if (candidate.signerSha256.isEmpty()) return "APK nie jest podpisane"
        if (installed.signerSha256.isEmpty()) return "Nie można odczytać podpisu zainstalowanej aplikacji"
        if (candidate.signerSha256 != installed.signerSha256) {
            return "APK podpisane innym kluczem niż zainstalowana aplikacja – instalacja odrzucona"
        }
        if (candidate.versionCode <= installed.versionCode) {
            return "APK nie jest nowsze (versionCode ${candidate.versionCode} ≤ ${installed.versionCode})"
        }
        val apkVersion = candidate.versionName?.let(SemanticVersion::parse)
        if (apkVersion != expectedVersion) {
            return "Wersja w APK (${candidate.versionName}) nie zgadza się z wydaniem $expectedVersion"
        }
        return null
    }
}

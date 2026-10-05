package com.solartracker.pro.core.update

import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

enum class CheckInterval(val duration: Duration?) {
    HOURS_6(Duration.ofHours(6)),
    HOURS_12(Duration.ofHours(12)),
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7)),

    /** Only when the user taps "Sprawdź teraz". */
    MANUAL(null),
}

/** User configuration of the updater. No secrets are stored here except the optional user token. */
data class UpdateConfig(
    val enabled: Boolean = true,
    val owner: String = DEFAULT_OWNER,
    val repo: String = DEFAULT_REPO,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val interval: CheckInterval = CheckInterval.DAILY,
    /** Download a found update in the background (installation always needs a user tap). */
    val autoDownload: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Optional read-only GitHub token, needed for private repositories. */
    val token: String = "",
) {
    fun validate(): List<String> = buildList {
        if (!NAME.matches(owner)) add("Nieprawidłowy właściciel repozytorium")
        if (!NAME.matches(repo)) add("Nieprawidłowa nazwa repozytorium")
        if (token.any { it.isWhitespace() }) add("Token nie może zawierać spacji")
    }

    companion object {
        const val DEFAULT_OWNER = "RzKa1337"
        const val DEFAULT_REPO = "PV"
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")
    }
}

/** Persistent updater state (deferrals, skipped and bad versions, last check). */
data class UpdateState(
    val lastCheck: Instant? = null,
    val snoozedUntil: Instant? = null,
    val snoozedVersion: String? = null,
    val skippedVersions: Set<String> = emptySet(),
    /** Versions that failed to install or crashed after updating; never offered automatically. */
    val badVersions: Set<String> = emptySet(),
)

object UpdatePolicy {

    fun isCheckDue(config: UpdateConfig, state: UpdateState, now: Instant): Boolean {
        if (!config.enabled) return false
        val interval = config.interval.duration ?: return false
        val last = state.lastCheck ?: return true
        return !now.isBefore(last.plus(interval))
    }

    /** Versions not to offer automatically right now. */
    fun ignoredVersions(state: UpdateState, now: Instant): Set<String> = buildSet {
        addAll(state.skippedVersions)
        addAll(state.badVersions)
        val snoozed = state.snoozedVersion
        if (snoozed != null && state.snoozedUntil != null && now.isBefore(state.snoozedUntil)) add(snoozed)
    }

    fun snooze(state: UpdateState, version: String, now: Instant, duration: Duration): UpdateState =
        state.copy(snoozedVersion = version, snoozedUntil = now.plus(duration))

    fun skip(state: UpdateState, version: String): UpdateState =
        state.copy(skippedVersions = state.skippedVersions + version)

    fun markBad(state: UpdateState, version: String): UpdateState =
        state.copy(badVersions = state.badVersions + version)
}

/** Exponential back-off for failed downloads/requests. */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelay: Duration = Duration.ofSeconds(2),
    val factor: Double = 2.0,
    val maxDelay: Duration = Duration.ofMinutes(1),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** Delay before retry number [attempt] (1 = first retry). */
    fun delayBefore(attempt: Int): Duration {
        val millis = initialDelay.toMillis() * factor.pow((attempt - 1).coerceAtLeast(0))
        return Duration.ofMillis(min(millis, maxDelay.toMillis().toDouble()).toLong())
    }
}

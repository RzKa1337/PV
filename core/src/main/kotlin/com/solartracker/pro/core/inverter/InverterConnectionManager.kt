package com.solartracker.pro.core.inverter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** DEGRADED = the last read failed but the inverter is not yet considered offline. */
enum class LinkStatus { DISCONNECTED, CONNECTING, ONLINE, DEGRADED, OFFLINE }

/** Live connection state shown to the user. */
data class ConnectionState(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val lastSuccess: Instant? = null,
    val lastAttempt: Instant? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    /** Successful reads among the last [ConnectionStats.WINDOW] attempts, 0..1. */
    val quality: Double? = null,
    val lastLatencyMs: Long? = null,
    /** Failures by kind since monitoring started. */
    val errorCounts: Map<LinkErrorKind, Int> = emptyMap(),
    val lastErrorKind: LinkErrorKind? = null,
    /** Successful reconnects after the link was lost. */
    val reconnects: Int = 0,
    val lastReconnect: Instant? = null,
    val readsOk: Long = 0,
    val readsFailed: Long = 0,
)

/** How fresh the newest telemetry is right now. */
enum class Freshness { LIVE, STALE, LAST_KNOWN, NONE }

data class PollSettings(
    val interval: Duration = Duration.ofSeconds(5),
    /** Data older than this is STALE even if the link looks up. */
    val staleAfter: Duration = Duration.ofSeconds(15),
    /** After this many failed reads in a row the inverter is OFFLINE. */
    val offlineAfterFailures: Int = 3,
    val maxBackoff: Duration = Duration.ofSeconds(60),
    /** Identical consecutive readings after which the data is reported as frozen. */
    val frozenAfterReads: Int = 60,
) {
    init {
        require(!interval.isNegative && interval >= MIN_INTERVAL) { "interval too short" }
        require(staleAfter >= interval) { "staleAfter must be >= interval" }
        require(offlineAfterFailures >= 1)
    }

    companion object {
        val MIN_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}

internal class ConnectionStats {
    private val results = ArrayDeque<Boolean>()
    fun add(ok: Boolean) {
        results.addLast(ok)
        while (results.size > WINDOW) results.removeFirst()
    }
    val quality: Double? get() = if (results.isEmpty()) null else results.count { it }.toDouble() / results.size

    companion object {
        const val WINDOW = 20
    }
}

/**
 * Polls an [InverterProvider] while collected/running: reconnects with exponential back-off,
 * marks the inverter OFFLINE after repeated failures, drops duplicate or out-of-order readings and
 * never presents old data as live (see [freshness]).
 */
class InverterConnectionManager(
    private val provider: InverterProvider,
    private val settings: PollSettings = PollSettings(),
    private val clock: () -> Instant = { Instant.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onTelemetry: (InverterTelemetry) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
    /** Validation of every reading; rejected values never reach [telemetry]. */
    private val validator: TelemetryValidator? = TelemetryValidator(),
    /** Model PV expectation now [W] for the zero-PV check (null = unknown). */
    private val expectedPvW: (Instant) -> Double? = { null },
) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow<InverterTelemetry?>(null)
    val telemetry: StateFlow<InverterTelemetry?> = _telemetry.asStateFlow()

    private val _validation = MutableStateFlow<TelemetryValidation?>(null)
    /** Validation of the newest reading (values with provenance and quality, issues). */
    val validation: StateFlow<TelemetryValidation?> = _validation.asStateFlow()

    private val stats = ConnectionStats()
    private var connected = false
    private var lastRaw: InverterTelemetry? = null
    private var identicalStreak = 0

    val info: InverterInfo get() = provider.info
    val capabilities: Set<TelemetryField> get() = provider.capabilities

    fun freshness(now: Instant = clock()): Freshness {
        val t = _telemetry.value ?: return Freshness.NONE
        val s = _state.value.status
        if (s == LinkStatus.OFFLINE || s == LinkStatus.DISCONNECTED) return Freshness.LAST_KNOWN
        return if (Duration.between(t.timestamp, now) > settings.staleAfter) Freshness.STALE else Freshness.LIVE
    }

    /** Runs until the calling coroutine is cancelled (e.g. the screen stops collecting). */
    suspend fun run() {
        var backoff = settings.interval
        try {
            while (coroutineContext.isActive) {
                val ok = pollOnce()
                val wait = if (ok) {
                    backoff = settings.interval
                    settings.interval
                } else {
                    backoff = Duration.ofMillis(min(backoff.toMillis() * 2, settings.maxBackoff.toMillis()))
                    backoff
                }
                delay(wait.toMillis())
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + io) { disconnect() }
        }
    }

    /** One read attempt. @return true on success */
    suspend fun pollOnce(): Boolean {
        val attemptAt = clock()
        val previous = _state.value
        if (!connected) _state.value = previous.copy(status = LinkStatus.CONNECTING, lastAttempt = attemptAt)
        val started = System.nanoTime()
        return try {
            val reading = withContext(io) {
                if (!connected) {
                    provider.connect()
                    connected = true
                    onEvent("Połączono: ${provider.info.interfaceDescription}")
                }
                provider.read(clock())
            }
            val latency = (System.nanoTime() - started) / 1_000_000
            stats.add(true)
            accept(reading)
            val recovered = previous.status == LinkStatus.OFFLINE || previous.status == LinkStatus.DEGRADED
            _state.value = previous.copy(
                status = LinkStatus.ONLINE,
                lastSuccess = reading.timestamp,
                lastAttempt = attemptAt,
                consecutiveFailures = 0,
                quality = stats.quality,
                lastLatencyMs = latency,
                reconnects = previous.reconnects + if (recovered) 1 else 0,
                lastReconnect = if (recovered) attemptAt else previous.lastReconnect,
                readsOk = previous.readsOk + 1,
            )
            if (previous.status == LinkStatus.OFFLINE) onEvent("Połączenie z falownikiem przywrócone")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stats.add(false)
            val failures = previous.consecutiveFailures + 1
            val message = e.message ?: e.javaClass.simpleName
            val kind = LinkErrorKind.classify(e)
            // Drop the link so the next attempt reconnects from scratch.
            withContext(io) { disconnect() }
            val offline = failures >= settings.offlineAfterFailures
            _state.value = previous.copy(
                status = if (offline) LinkStatus.OFFLINE else LinkStatus.DEGRADED,
                lastAttempt = attemptAt,
                consecutiveFailures = failures,
                lastError = "${kind.label}: $message",
                quality = stats.quality,
                errorCounts = previous.errorCounts + (kind to (previous.errorCounts[kind] ?: 0) + 1),
                lastErrorKind = kind,
                readsFailed = previous.readsFailed + 1,
            )
            if (offline && previous.status != LinkStatus.OFFLINE) onEvent("Falownik nie odpowiada (OFFLINE): $message")
            false
        }
    }

    private fun accept(reading: InverterTelemetry) {
        val last = _telemetry.value
        // Out-of-order or duplicate timestamps (clock jumps) are ignored.
        if (last != null && !reading.timestamp.isAfter(last.timestamp)) return
        // Frozen data: the device keeps returning exactly the same values.
        val raw = lastRaw
        identicalStreak = if (raw != null && raw.copy(timestamp = reading.timestamp) == reading) identicalStreak + 1 else 0
        lastRaw = reading
        val v = validator?.validate(reading, last, expectedPvW(reading.timestamp))
        // The simulator is deterministic (constant at night), so "frozen" is only checked on real devices.
        val frozen = identicalStreak >= settings.frozenAfterReads && !provider.info.simulated
        val validation = v?.let {
            if (frozen) it.copy(issues = it.issues + TelemetryIssue(IssueType.FROZEN, null, "$identicalStreak identycznych odczytów z rzędu")) else it
        }
        _validation.value = validation
        val accepted = validation?.sanitized ?: reading
        _telemetry.value = accepted
        onTelemetry(accepted)
    }

    private fun disconnect() {
        if (connected) runCatching { provider.disconnect() }
        connected = false
    }

}

/**
 * Commands to the inverter. Deliberately read-only: the app never writes settings to the inverter
 * and never executes commands received from the network.
 */
class InverterCommandService(private val manager: InverterConnectionManager) {
    sealed interface Command {
        data object RefreshNow : Command
    }

    suspend fun execute(command: Command): Boolean = when (command) {
        Command.RefreshNow -> manager.pollOnce()
    }
}

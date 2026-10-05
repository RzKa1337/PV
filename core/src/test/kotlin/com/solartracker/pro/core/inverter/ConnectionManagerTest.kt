package com.solartracker.pro.core.inverter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private var now = Instant.parse("2026-10-05T10:00:00Z")

    @Test
    fun onlineThenOfflineThenReconnects() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var failing = false
        val fake = FakeAnenjiProvider(failNext = { failing })
        val events = mutableListOf<String>()
        val manager = InverterConnectionManager(fake, PollSettings(Duration.ofSeconds(5), Duration.ofSeconds(15), 3), { now }, dispatcher, onEvent = { events += it })

        assertTrue(manager.pollOnce())
        assertEquals(LinkStatus.ONLINE, manager.state.value.status)
        assertEquals(Freshness.LIVE, manager.freshness(now))
        now = now.plusSeconds(20)
        assertEquals("old data is not live", Freshness.STALE, manager.freshness(now))

        failing = true
        repeat(2) { manager.pollOnce() }
        assertEquals(LinkStatus.DEGRADED, manager.state.value.status)
        manager.pollOnce()
        assertEquals(LinkStatus.OFFLINE, manager.state.value.status)
        assertEquals(3, manager.state.value.consecutiveFailures)
        assertTrue(manager.state.value.lastError!!.contains("Symulowany"))
        assertEquals(Freshness.LAST_KNOWN, manager.freshness(now))
        assertTrue(events.any { it.contains("OFFLINE") })

        failing = false
        now = now.plusSeconds(5)
        assertTrue(manager.pollOnce())
        assertEquals(LinkStatus.ONLINE, manager.state.value.status)
        assertTrue("reconnected", fake.connectCount >= 2)
        assertTrue(events.last().contains("przywrócone"))
    }

    @Test
    fun ignoresDuplicateAndOutOfOrderTimestamps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accepted = mutableListOf<Instant>()
        val manager = InverterConnectionManager(FakeAnenjiProvider(), PollSettings(), { now }, dispatcher, onTelemetry = { accepted += it.timestamp })
        manager.pollOnce()
        manager.pollOnce() // same timestamp → duplicate
        now = now.minusSeconds(30) // clock jumped back
        manager.pollOnce()
        assertEquals(1, accepted.size)
        now = now.plusSeconds(60)
        manager.pollOnce()
        assertEquals(2, accepted.size)
    }

    @Test
    fun runLoopPollsAtIntervalBacksOffAndDisconnectsOnCancel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var reads = 0
        var failing = false
        val fake = FakeAnenjiProvider(script = { reads++; FakeAnenjiProvider.defaultReading(it) }, failNext = { failing })
        val manager = InverterConnectionManager(fake, PollSettings(Duration.ofSeconds(5), Duration.ofSeconds(15), 3), {
            now = now.plusMillis(1); now
        }, dispatcher)
        val job = launch(dispatcher) { manager.run() }
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(5_001)
        assertEquals(2, reads)
        failing = true
        advanceTimeBy(5_001) // fails, back-off 10 s
        val attempts = manager.state.value.consecutiveFailures
        advanceTimeBy(9_000)
        assertEquals("waits longer after failure", attempts, manager.state.value.consecutiveFailures)
        job.cancel()
        runCurrent()
        assertEquals(false, fake.connected)
    }

    @Test
    fun pollSettingsValidation() {
        val e = runCatching { PollSettings(interval = Duration.ofMillis(200)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertNull(InverterConfig(host = "x").validate().firstOrNull())
        assertEquals(Duration.ofSeconds(15), InverterConfig(pollIntervalSeconds = 5).pollSettings.staleAfter)
    }
}

package com.solartracker.pro.core.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * Emits the current wall-clock time once per second, right after each second boundary.
 *
 * Timing design:
 * - The astronomical time is always read fresh from [wallClock] (e.g. `Instant.now()`); nothing
 *   is cached between ticks.
 * - Waiting is done with `delay`, which is scheduled on a monotonic clock, so wall-clock
 *   adjustments cannot make the coroutine sleep for a wrong amount of time.
 * - The wait is recomputed every tick as "time left until the next wall-clock second boundary
 *   + [guardMillis]", so errors never accumulate (no drift as with a fixed `delay(1000)`).
 * - A tick is emitted only when the wall-clock second changes, so an early wake-up never emits
 *   the same second twice, and a late one still emits the current second.
 *
 * The flow is cold: it runs only while collected and stops as soon as the collector is cancelled.
 */
class SecondTicker(
    private val wallClock: () -> Instant = { Instant.now() },
    private val guardMillis: Long = DEFAULT_GUARD_MILLIS,
) {
    init {
        require(guardMillis in 0..200) { "guardMillis must be 0..200" }
    }

    fun ticks(): Flow<Instant> = flow {
        var lastSecond = Long.MIN_VALUE
        while (true) {
            val now = wallClock()
            if (now.epochSecond != lastSecond) {
                lastSecond = now.epochSecond
                emit(now)
            }
            delay(millisToNextSecond(wallClock()) + guardMillis)
        }
    }

    companion object {
        /** Small margin after the boundary, so the wake-up is safely inside the new second. */
        const val DEFAULT_GUARD_MILLIS = 5L

        /** Milliseconds from [instant] to the next full wall-clock second (1..1000). */
        fun millisToNextSecond(instant: Instant): Long = 1000L - instant.nano / 1_000_000
    }
}

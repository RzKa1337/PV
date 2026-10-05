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
 *   the same second twice.
 * - If a wake-up is late by a few seconds (e.g. the CPU was busy), the missed seconds are emitted
 *   immediately, in order, each with the exact instant of its second boundary, so no second is
 *   lost. Bigger jumps (device sleep, the user changing the clock, going backwards) are not
 *   back-filled: the ticker continues from the current time.
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
            val second = now.epochSecond
            if (second != lastSecond) {
                val missed = second - lastSecond - 1
                if (lastSecond != Long.MIN_VALUE && missed in 1..MAX_BACKFILL_SECONDS) {
                    for (s in lastSecond + 1 until second) emit(Instant.ofEpochSecond(s))
                }
                lastSecond = second
                emit(now)
            }
            delay(millisToNextSecond(wallClock()) + guardMillis)
        }
    }

    companion object {
        /** Small margin after the boundary, so the wake-up is safely inside the new second. */
        const val DEFAULT_GUARD_MILLIS = 5L

        /** Longest delay whose missed seconds are back-filled. */
        const val MAX_BACKFILL_SECONDS = 5L

        /** Milliseconds from [instant] to the next full wall-clock second (1..1000). */
        fun millisToNextSecond(instant: Instant): Long = 1000L - instant.nano / 1_000_000
    }
}

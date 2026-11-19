package com.doro.organizer.model

/**
 * The delay after consecutive wrong startup passwords (DEC-62, security.md § 5.3), on the `AppTime.elapsedMillis()`
 * base, so moving the wall clock changes nothing.
 */
object UnlockBackoff {
    /** The first delay, after the fifth failure. */
    const val FIRST_DELAY_MS = 30_000L

    /** Failures without any delay. */
    private const val FREE_FAILURES = 4
    private const val MAX_DELAY_MS = 300_000L

    /** The wait after the [failures]-th consecutive failure: none for 1–4, then 30 s doubling up to 300 s. */
    fun delayMillis(failures: Int): Long {
        if (failures <= FREE_FAILURES) return 0
        val doublings = failures - FREE_FAILURES - 1
        // 30 s × 2^4 already exceeds the cap; the guard also keeps the shift far from overflowing.
        if (doublings >= 4) return MAX_DELAY_MS
        return minOf(FIRST_DELAY_MS shl doublings, MAX_DELAY_MS)
    }

    /**
     * The retry time to honor for a stored pair. Elapsed realtime restarts at boot, so a stored value further away
     * than the current delay proves a reboot and is replaced by `now + delayMillis(failures)`: a reboot re-applies
     * the full delay.
     */
    fun effectiveRetryAt(failures: Int, storedRetryAt: Long, now: Long): Long {
        val delay = delayMillis(failures)
        return if (storedRetryAt - now > delay) now + delay else storedRetryAt
    }
}

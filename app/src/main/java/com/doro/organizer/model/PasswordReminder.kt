package com.doro.organizer.model

/**
 * The 14-day startup-password reminder (DEC-74, security.md § 5.6), on the wall clock (`AppTime.nowMillis()`),
 * because the interval must survive reboots.
 */
object PasswordReminder {
    /** 14 days: 1,209,600,000 ms. */
    const val INTERVAL_MS: Long = 14L * 24 * 60 * 60 * 1000

    fun isDue(verifiedAt: Long, now: Long): Boolean =
        verifiedAt <= 0L || // lost or never written
            now < verifiedAt || // the clock was set backwards
            now - verifiedAt >= INTERVAL_MS
}

package com.doro.organizer.core.time

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/**
 * The only clock of the app (DEC-20, DEC-26). Wall-clock values are epoch milliseconds (UTC); the zone is read on
 * every call, never cached.
 */
interface AppTime {
    /** Wall clock: every `*_at` value, the share sweep, the backup file date, the password reminder. */
    fun nowMillis(): Long

    /** The local date in the current zone. */
    fun today(): LocalDate

    /** The local date of [epochMillis] in the current zone. */
    fun dateOf(epochMillis: Long): LocalDate

    /** Monotonic clock that restarts at boot: the unlock backoff, the lock exemption, KDF timing. */
    fun elapsedMillis(): Long

    /** [today], re-evaluated on lifecycle, midnight and time-change triggers; emits only when the date differs. */
    val todayFlow: StateFlow<LocalDate>
}

package com.doro.organizer.model

import com.doro.organizer.testing.FakeAppTime
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** testing.md L-37, the pure due rule (DEC-74, security.md § 5.6), on the wall clock `AppTime.nowMillis()`. */
class PasswordReminderTest {
    private val time = FakeAppTime()
    private val day = 86_400_000L

    @Test
    fun interval_isFourteenDays() {
        assertEquals(14 * day, PasswordReminder.INTERVAL_MS)
        assertEquals(1_209_600_000L, PasswordReminder.INTERVAL_MS)
    }

    @Test
    fun dueExactlyAfterFourteenDays() {
        val verifiedAt = time.nowMillis()

        time.advanceBy(Duration.ofMillis(14 * day - 1))
        assertFalse(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
        time.advanceBy(Duration.ofMillis(1))
        assertTrue(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
        time.advanceBy(Duration.ofDays(3))
        assertTrue(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
    }

    @Test
    fun dueWhenTheClockWasSetBackwards_evenByOneMillisecond() {
        val verifiedAt = time.nowMillis()

        time.setWallClock(time.instant.minusMillis(1))

        assertTrue(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
    }

    @Test
    fun dueWithoutAStoredTimestamp() {
        assertTrue(PasswordReminder.isDue(verifiedAt = 0, now = time.nowMillis()))
    }

    @Test
    fun theMonotonicClockChangesNothing() {
        val verifiedAt = time.nowMillis()

        time.setElapsed(time.elapsedMillis() + 30 * day)
        assertFalse(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
        time.setElapsed(1)
        assertFalse(PasswordReminder.isDue(verifiedAt, time.nowMillis()))
    }
}

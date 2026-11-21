package com.doro.organizer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** The backoff countdown `m:ss` and its tick (ui.md § 10.2, § 11.14, testing.md L-02). */
class CountdownTest {
    @Test
    fun minutesUnpadded_secondsTwoDigits() {
        assertEquals("4:05", countdownText(245_000))
        assertEquals("5:00", countdownText(300_000))
        assertEquals("0:30", countdownText(30_000))
    }

    @Test
    fun aStartedSecond_countsAsAWholeOne() {
        assertEquals("0:30", countdownText(29_001))
        assertEquals("0:01", countdownText(1))
    }

    @Test
    fun nothingLeft_isZero_neverNegative() {
        assertEquals("0:00", countdownText(0))
        assertEquals("0:00", countdownText(-5_000))
    }

    @Test
    fun theNextTick_fallsOnTheNextSecondBoundaryOfTheRemainingTime() {
        assertEquals(1_000, nextCountdownTickMs(30_000))
        assertEquals(1_000, nextCountdownTickMs(1_000))
        assertEquals(1, nextCountdownTickMs(29_001))
        assertEquals(999, nextCountdownTickMs(999))
    }

    @Test
    fun theLastSecond_endsExactlyWhenTheWaitEnds() {
        // 400 ms left shows 0:01; the field enables after those 400 ms, not a whole second later (ui.md § 10.2).
        assertEquals(400, nextCountdownTickMs(400))
        assertEquals("0:00", countdownText(400 - nextCountdownTickMs(400)))
    }
}

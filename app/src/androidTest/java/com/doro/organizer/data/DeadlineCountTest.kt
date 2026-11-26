package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DeadlineCount.runUntil], the long statement of `DatabaseHolderTest`'s running-statement scene (DEC-114, BL-10), on
 * a fake clock: no SQL, no timing. Each count advances the clock by the time the case gives it, so the run's end is
 * exact. Whatever the speed, the run ends at the first count that reaches the deadline, so the statement's end lands
 * within one count of 800 ms; on round 1's calibration (the fastest of five samples) G3's slow speed ran to +2,229 ms.
 */
@RunWith(AndroidJUnit4::class)
class DeadlineCountTest {
    @Test
    fun aFastTierRun_endsAtTheFirstCountPastTheDeadline() {
        val clock = FakeClock { FAST_NS }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(48, run.counts)
        assertEquals(816_000_000L, clock.nowNs)
        assertEquals(DeadlineCount.FULL_COUNTS, run.outcome)
    }

    /** G3 r13's statement: 0.474 µs per row, 47.4 ms per count. */
    @Test
    fun theG3SlowTier_endsWithinOneCountOfTheDeadline() {
        val clock = FakeClock { G3_SLOW_NS }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(17, run.counts)
        assertEquals(805_800_000L, clock.nowNs)
        assertTrue("ended after the deadline plus one count", clock.nowNs <= DEADLINE_NS + G3_SLOW_NS)
        assertEquals(DeadlineCount.FULL_COUNTS, run.outcome)
    }

    @Test
    fun aTierChangeMidRun_stillEndsWithinOneCountOfTheDeadline() {
        val clock = FakeClock { n -> if (n <= 10) FAST_NS else 55_000_000L }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(22, run.counts)
        assertEquals(830_000_000L, clock.nowNs)
        assertEquals(DeadlineCount.FULL_COUNTS, run.outcome)
    }

    @Test
    fun aStalledCountAcrossTheDeadline_endsTheRunAfterIt() {
        val clock = FakeClock { n -> if (n == 40) 600_000_000L else FAST_NS }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(40, run.counts)
        assertEquals(39 * FAST_NS + 600_000_000L, clock.nowNs)
        assertEquals("40 counts of 100000 rows, each 17.0 to 600.0 ms (full counts)", "$run")
    }

    /** The statement is always stepped at least once, so the scene always has a running statement to stamp. */
    @Test
    fun aDeadlineAlreadyPassed_runsExactlyOneCount() {
        val clock = FakeClock(startNs = 2 * DEADLINE_NS) { FAST_NS }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(1, run.counts)
        assertEquals(1, clock.calls)
        assertEquals(DeadlineCount.FULL_COUNTS, run.outcome)
    }

    @Test
    fun aShortCount_stopsTheRun_andNamesIt() {
        val clock = FakeClock(result = { n -> if (n == 3) DeadlineCount.CHUNK - 1 else DeadlineCount.CHUNK }) { FAST_NS }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(3, run.counts)
        assertEquals(3, clock.calls)
        assertEquals("count 3 returned 99999", run.outcome)
    }

    /** A clock that never advances stops at [DeadlineCount.MAX_COUNTS] counts instead of hanging. */
    @Test
    fun aClockThatDoesNotAdvance_stopsAtMaxCounts() {
        val clock = FakeClock { 0L }

        val run = clock.runUntil(DEADLINE_NS)

        assertEquals(DeadlineCount.MAX_COUNTS, run.counts)
        assertEquals(DeadlineCount.MAX_COUNTS, clock.calls)
        assertEquals("stopped after ${DeadlineCount.MAX_COUNTS} counts before the deadline", run.outcome)
    }

    @Test
    fun aThrowingCount_propagatesItsException() {
        val thrown = IllegalStateException("no row")
        val clock = FakeClock(result = { n -> if (n == 2) throw thrown else DeadlineCount.CHUNK }) { FAST_NS }

        try {
            clock.runUntil(DEADLINE_NS)
        } catch (caught: IllegalStateException) {
            assertSame(thrown, caught)
            assertEquals(2, clock.calls)
            return
        }
        fail("runUntil swallowed the count's exception")
    }

    @Test
    fun toString_printsCountsRangeAndOutcome() {
        val run = DeadlineCount.Run(41, 16_500_000L, 55_000_000L, DeadlineCount.FULL_COUNTS)

        assertEquals("41 counts of 100000 rows, each 16.5 to 55.0 ms (full counts)", "$run")
    }

    /**
     * A clock that only the counts move: count n (from 1) advances it by [durationNs] of n and returns [result] of n.
     * [calls] is the number of counts run.
     */
    private class FakeClock(
        startNs: Long = 0L,
        private val result: (Int) -> Long = { DeadlineCount.CHUNK },
        private val durationNs: (Int) -> Long,
    ) {
        var nowNs = startNs
            private set
        var calls = 0
            private set

        fun runUntil(deadlineNs: Long): DeadlineCount.Run = DeadlineCount.runUntil(deadlineNs, { nowNs }) {
            calls++
            nowNs += durationNs(calls)
            result(calls)
        }
    }

    private companion object {
        /** `DatabaseHolderTest`'s STATEMENT_MS in ns. */
        const val DEADLINE_NS = 800_000_000L

        /** A count at the fast speed (about 0.17 µs per row). */
        const val FAST_NS = 17_000_000L

        /** A count at G3 r13's speed (0.474 µs per row). */
        const val G3_SLOW_NS = 47_400_000L
    }
}

package com.doro.organizer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LongQuery.boundFrom], the estimate behind every calibrated long statement (BL-10, DEC-114), on fixed samples: no
 * SQL, no timing. The bound comes from the fastest sample, wherever it falls, which bounds the statement's speed from
 * below: a stalled sample is ignored, and an unusually fast one sets the bound, so the statement can run up to about
 * 3× its target but rarely shorter (a sample can be off in either direction on the phone). The first case is the
 * calibration of BL-10's first failures: a clean first sample and a stalled last one, which the old rule (the last
 * sample) turned into less than half the bound and a statement about half as long as planned.
 * [aFastLastSample_setsTheBound_theFastestRule] pins the other side, which keeps this rule out of narrow windows.
 */
@RunWith(AndroidJUnit4::class)
class LongQueryCalibrationTest {
    @Test
    fun aStalledLastSample_isIgnored_theBoundComesFromTheFastest() {
        assertNearClean(LongQuery.boundFrom(longArrayOf(CLEAN_NS, STALLED_NS), TARGET_MS))
    }

    @Test
    fun aStalledFirstSample_isIgnored_theBoundComesFromTheFastest() {
        assertNearClean(LongQuery.boundFrom(longArrayOf(STALLED_NS, CLEAN_NS), TARGET_MS))
    }

    /**
     * G3 r13 of BL-10 round 1 (build/bl10/g3): a fast fifth sample sets the bound, 4,711,425 rows. The samples are as
     * printed, which cuts them to 0.1 ms, so the pinned bound is not quite that run's own 4,707,529. That run's
     * statement then cost 0.474 µs per row, about 2,233 ms for this bound, beyond the +1,703 ms that
     * `DatabaseHolderTest`'s window allows (a drain at about +203 ms plus its 1,500 ms maximum), so a window with two
     * narrow sides must not use [LongQuery] (DEC-114). Fails on a median (122.3 ms, 3,270,645 rows) or a mean; the old
     * last-sample rule gives the same bound here because the fast sample is last, and fails
     * [aStalledLastSample_isIgnored_theBoundComesFromTheFastest] instead.
     */
    @Test
    fun aFastLastSample_setsTheBound_theFastestRule() {
        val samples = longArrayOf(122_300_000L, 114_800_000L, 253_700_000L, 126_700_000L, 84_900_000L)

        assertEquals(4_711_425L, LongQuery.boundFrom(samples, TARGET_MS))
    }

    @Test
    fun fiveCleanSamples_giveTheCleanBound() {
        val samples = longArrayOf(CLEAN_NS, CLEAN_NS + 2_000_000L, CLEAN_NS + 1_000_000L, CLEAN_NS + 3_000_000L, CLEAN_NS)
        assertNearClean(LongQuery.boundFrom(samples, TARGET_MS))
    }

    /**
     * The 0 ns sample is last on purpose: on the old last-sample rule this case also gives MAX_BOUND, so BL-10's RED-1
     * fails only [aStalledLastSample_isIgnored_theBoundComesFromTheFastest].
     */
    @Test
    fun aSampleOf0Ns_isClampedToMaxBound_withoutDividingByZero() {
        assertEquals(LongQuery.MAX_BOUND, LongQuery.boundFrom(longArrayOf(CLEAN_NS, STALLED_NS, 0L), TARGET_MS))
    }

    /** Samples far over the target (a raw bound of 40,000) are clamped up to one [LongQuery.SAMPLE]. */
    @Test
    fun samplesFarOverTheTarget_areClampedToSample() {
        assertEquals(LongQuery.SAMPLE, LongQuery.boundFrom(longArrayOf(10_000_000_000L, 20_000_000_000L), TARGET_MS))
    }

    @Test
    fun noSample_isRejected() {
        assertRejected { LongQuery.boundFrom(LongArray(0), TARGET_MS) }
    }

    @Test
    fun aTargetOf0Ms_isRejected() {
        assertRejected { LongQuery.boundFrom(longArrayOf(CLEAN_NS), 0L) }
    }

    /** A target whose product with [LongQuery.SAMPLE] in ns would overflow a Long. */
    @Test
    fun aTargetOverMaxTargetMs_isRejected() {
        assertRejected { LongQuery.boundFrom(longArrayOf(CLEAN_NS), LongQuery.MAX_TARGET_MS + 1) }
    }

    /** [block] throws IllegalArgumentException (a `require` of [LongQuery.boundFrom]). */
    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("boundFrom accepted its input")
    }

    /** The bound is within [TOLERANCE_PERCENT] of [LongQuery.SAMPLE] * [TARGET_MS] / 111 ms, about 3.60 million. */
    private fun assertNearClean(bound: Long) {
        val expected = LongQuery.SAMPLE * TARGET_MS / CLEAN_MS
        val off = if (bound > expected) bound - expected else expected - bound
        assertTrue(
            "the bound $bound is more than $TOLERANCE_PERCENT% away from $expected (the fastest sample's bound)",
            off * 100 <= expected * TOLERANCE_PERCENT,
        )
    }

    private companion object {
        /** BL-10's 800 ms target: DatabaseHolderTest's STATEMENT_MS, which sized its statement before DEC-114. */
        const val TARGET_MS = 800L

        /** A clean sample of BL-10's failures (green-data close: 111 ms per 500,000 rows). */
        const val CLEAN_MS = 111L
        const val CLEAN_NS = CLEAN_MS * 1_000_000L

        /** A stalled sample of BL-10's failures (about 250 ms). */
        const val STALLED_NS = 250_000_000L

        const val TOLERANCE_PERCENT = 5L
    }
}

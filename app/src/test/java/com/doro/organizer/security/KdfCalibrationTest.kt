package com.doro.organizer.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** testing.md L-04, JVM part: `iterationsFor` of security.md § 3.3. */
class KdfCalibrationTest {
    @Test
    fun iterationsFor_targets750ms_roundsUpTo10k_andClamps() {
        val expected = mapOf(
            100L to 600_000, // 375,000: the floor wins
            75L to 600_000, // 500,000: the floor wins
            50L to 750_000,
            40L to 940_000, // 937,500 rounded up
            37L to 1_020_000, // 1,013,513 rounded up
            5L to 5_000_000, // 7,500,000: the ceiling wins
        )

        assertEquals(expected, expected.mapValues { (t, _) -> iterationsFor(t) })
    }

    @Test
    fun iterationsFor_zeroDuration_countsAsOneMillisecond() {
        assertEquals(5_000_000, iterationsFor(0))
        assertEquals(5_000_000, iterationsFor(-3))
    }
}

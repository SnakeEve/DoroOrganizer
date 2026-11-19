package com.doro.organizer.model

import com.doro.organizer.testing.FakeAppTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** testing.md L-02, JVM part (DEC-62, security.md § 5.3). */
class UnlockBackoffTest {
    @Test
    fun delayMillis_followsTheSchedule() {
        val expected = mapOf(
            0 to 0L, 1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L,
            5 to 30_000L, 6 to 60_000L, 7 to 120_000L, 8 to 240_000L, 9 to 300_000L,
            10 to 300_000L, 40 to 300_000L, Int.MAX_VALUE to 300_000L,
        )

        assertEquals(expected, expected.mapValues { (failures, _) -> UnlockBackoff.delayMillis(failures) })
    }

    @Test
    fun effectiveRetryAt_keepsAValueWithinTheCurrentDelay() {
        val now = 1_000_000L

        assertEquals(now + 30_000, UnlockBackoff.effectiveRetryAt(5, storedRetryAt = now + 30_000, now = now))
        assertEquals(now + 1, UnlockBackoff.effectiveRetryAt(5, storedRetryAt = now + 1, now = now))
        assertEquals("an expired wait stays expired", 5L, UnlockBackoff.effectiveRetryAt(5, storedRetryAt = 5, now = now))
        assertEquals("no wait stored", 0L, UnlockBackoff.effectiveRetryAt(7, storedRetryAt = 0, now = now))
    }

    @Test
    fun effectiveRetryAt_afterAReboot_reappliesTheFullDelay() {
        val time = FakeAppTime()
        val storedRetryAt = time.elapsedMillis() + 120_000 // the seventh failure, before the reboot
        time.setElapsed(5_000) // elapsed realtime restarted at boot

        val effective = UnlockBackoff.effectiveRetryAt(7, storedRetryAt, time.elapsedMillis())

        assertEquals(5_000L + 120_000, effective)
    }

    @Test
    fun effectiveRetryAt_afterAReboot_withoutADelay_isNow() {
        assertEquals(5_000L, UnlockBackoff.effectiveRetryAt(3, storedRetryAt = 9_000_000, now = 5_000))
    }

    @Test
    fun effectiveRetryAt_ignoresTheWallClock() {
        val time = FakeAppTime()
        val storedRetryAt = time.elapsedMillis() + 60_000
        val before = UnlockBackoff.effectiveRetryAt(6, storedRetryAt, time.elapsedMillis())

        time.setWallClock(Instant.parse("2020-01-01T00:00:00Z"))
        val afterBackwards = UnlockBackoff.effectiveRetryAt(6, storedRetryAt, time.elapsedMillis())
        time.setWallClock(Instant.parse("2035-01-01T00:00:00Z"))
        val afterForwards = UnlockBackoff.effectiveRetryAt(6, storedRetryAt, time.elapsedMillis())

        assertEquals(storedRetryAt, before)
        assertEquals(before, afterBackwards)
        assertEquals(before, afterForwards)
    }
}

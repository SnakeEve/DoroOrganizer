package com.doro.organizer.testing

/**
 * A statement that keeps SQLite busy for a chosen time without reading any table: a recursive CTE that counts from 1
 * to its bound parameter and returns the count. For the setup probes that need a statement still running inside
 * SQLite while something else happens (db-lifecycle.md § Exclusive mode step 4, § Restore § Steps step 3).
 */
object LongQuery {
    /** Counts to `?`; the CAST keeps the comparison numeric when the bound is passed as text (`rawQuery` binds strings). */
    const val SQL =
        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < CAST(? AS INTEGER)) SELECT count(*) FROM c"

    /** The bound of the calibration runs. */
    const val SAMPLE = 500_000L

    /** The largest bound [boundFor] returns. */
    const val MAX_BOUND = 2_000_000_000L

    /**
     * The bound that makes [count] (which runs [SQL] with the given bound and returns its result) take about
     * [targetMs] on this device. Measured twice, so the second sample is not slowed by a first prepare; every probe
     * still checks that its statement was running when it acted, so a poor estimate fails the probe instead of
     * passing it.
     */
    inline fun boundFor(targetMs: Long, count: (Long) -> Long): Long {
        var elapsedMs = 0L
        repeat(2) {
            val start = System.nanoTime()
            val result = count(SAMPLE)
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            check(result == SAMPLE) { "calibration counted $result instead of $SAMPLE" }
        }
        // maxOf and minOf on Long are inline-only (Math.max, Math.min). The ranges extensions coerceAtLeast and
        // coerceIn would be calls into the stdlib's RangesKt, and the benchmark test APK compiles this package, so the
        // derived keep rules (DEC-103 (5)) would make the benchmark app keep them and change its build (T22 P-1).
        val bound = SAMPLE * targetMs / maxOf(elapsedMs, 1L)
        return minOf(maxOf(bound, SAMPLE), MAX_BOUND)
    }
}

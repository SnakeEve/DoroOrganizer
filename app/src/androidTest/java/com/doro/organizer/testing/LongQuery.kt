package com.doro.organizer.testing

/**
 * A statement that keeps SQLite busy for a chosen time without reading any table: a recursive CTE that counts from 1
 * to its bound parameter and returns the count. For the setup probes that need a statement still running inside
 * SQLite while something else happens (db-lifecycle.md § Exclusive mode step 4, § Restore § Steps step 3).
 *
 * The benchmark test APK compiles this package, so the derived keep rules (DEC-103 (5)) would make the benchmark app
 * keep every library member called here and change its build (T22 P-1). Hence plain loops, and only Long's inline-only
 * minOf and maxOf (Math.min, Math.max): `LongArray.min()`, `coerceIn` and `coerceAtLeast` are calls into the stdlib's
 * ArraysKt and RangesKt.
 */
object LongQuery {
    /** Counts to `?`; the CAST keeps the comparison numeric when the bound is passed as text (`rawQuery` binds strings). */
    const val SQL =
        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < CAST(? AS INTEGER)) SELECT count(*) FROM c"

    /** The bound of the calibration runs. */
    const val SAMPLE = 500_000L

    /** How many calibration runs [boundFor] times. */
    const val SAMPLES = 5

    /** The largest bound [boundFrom] returns. */
    const val MAX_BOUND = 2_000_000_000L

    /** The largest target of [boundFrom]: `SAMPLE * targetMs * 1_000_000` stays within a Long (about 5.1 hours). */
    const val MAX_TARGET_MS = Long.MAX_VALUE / (SAMPLE * 1_000_000L)

    /**
     * The bound that makes a count take about [targetMs], from [samplesNs], the times in nanoseconds of counts to
     * [SAMPLE], sized from the fastest sample. On the Poco F7 the cost per row varied about 3× from run to run, and a
     * sample can be off in either direction (BL-10, DEC-114): a stall makes one slow, and a sample can also run much
     * faster than the statement that follows. The fastest sample bounds the statement's speed from below, so the
     * statement can run up to about 3× longer than [targetMs] but rarely shorter: use it only where the upper limit is
     * generous. A window with two narrow sides (`DatabaseHolderTest`'s running-statement scene) ends its statement at
     * a deadline instead (`DeadlineCount`). A sample of 0 ns counts as 1 ns. The result is clamped to
     * [SAMPLE, MAX_BOUND]. [targetMs] must be positive and at most [MAX_TARGET_MS], so the product cannot overflow.
     */
    fun boundFrom(samplesNs: LongArray, targetMs: Long): Long {
        require(samplesNs.size > 0) { "no calibration sample" }
        require(targetMs > 0 && targetMs <= MAX_TARGET_MS) { "calibration target out of range" }
        var fastestNs = samplesNs[0]
        for (i in 1 until samplesNs.size) fastestNs = minOf(fastestNs, samplesNs[i])
        val bound = SAMPLE * targetMs * 1_000_000L / maxOf(fastestNs, 1L)
        return minOf(maxOf(bound, SAMPLE), MAX_BOUND)
    }

    /**
     * The bound of [boundFor] and the samples it came from; [toString] prints both for a probe's failure message, so
     * a precondition that fails on the statement's length explains its calibration without logcat. The samples are
     * private, for printing only, so they always match the bound; a Calibration is not compared (no equals). Each
     * sample prints in ms with one decimal, so a sample of 0 ns (0.0) is told apart from one just under 1 ms (0.9).
     */
    class Calibration(val bound: Long, private val samplesNs: LongArray) {
        override fun toString(): String {
            val text = StringBuilder("calibration: samples of $SAMPLE rows in ms ")
            for (i in 0 until samplesNs.size) {
                if (i > 0) text.append(", ")
                text.append(samplesNs[i] / 1_000_000L).append('.').append(samplesNs[i] / 100_000L % 10L)
            }
            return text.append(", bound $bound").toString()
        }
    }

    /**
     * Calibrates [count] (which runs [SQL] with the given bound and returns its result) to take about [targetMs] on
     * this device: times [SAMPLES] counts to [SAMPLE], each checked to count [SAMPLE] rows, and sizes the bound from
     * the fastest ([boundFrom]), which errs long rather than short. Any sample can be stalled, the first by its
     * prepare and any later one by other work on the device, and any can run faster than the statement after it;
     * every probe still checks that its statement was running when it acted and ended within its limit, so a poor
     * estimate fails the probe instead of passing it.
     */
    inline fun boundFor(targetMs: Long, count: (Long) -> Long): Calibration {
        val samplesNs = LongArray(SAMPLES)
        for (i in 0 until SAMPLES) {
            val start = System.nanoTime()
            val result = count(SAMPLE)
            samplesNs[i] = System.nanoTime() - start
            check(result == SAMPLE) { "calibration counted $result instead of $SAMPLE" }
        }
        return Calibration(boundFrom(samplesNs, targetMs), samplesNs)
    }
}

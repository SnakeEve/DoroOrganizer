package com.doro.organizer.data

/**
 * A long statement that ends at a deadline instead of at a row count sized in advance (DEC-114, BL-10): the caller
 * runs [com.doro.organizer.testing.LongQuery] in counts of [CHUNK] rows until a count ends at or after the deadline
 * (with Room in open-helper mode each count is its own query, see `DatabaseHolderTest.LongStatement`). No speed is
 * estimated, so the run ends within one count of the deadline at any speed of the device;
 * on the Poco F7 the cost per row varied about 3× from run to run, which no calibration before the statement could
 * predict. For `DatabaseHolderTest`'s running-statement scene, whose window has two narrow sides. In package `data`
 * of `src/androidTest`, which only the debug test APK compiles (DEC-103 (5)).
 */
internal object DeadlineCount {
    /** The rows of one count: about 17 ms at the fast speed, 47–55 ms at the slowest seen. */
    const val CHUNK = 100_000L

    /** Stops a run whose clock never reaches the deadline; far beyond any test's limit. */
    const val MAX_COUNTS = 10_000

    /** The outcome of a run whose every count returned [CHUNK]. */
    const val FULL_COUNTS = "full counts"

    /**
     * What a run did: [counts] counts, the shortest and longest of them, and how it ended ([outcome]). [toString]
     * prints all of it for a timeline, each time in ms with one decimal.
     */
    class Run(val counts: Int, private val minNs: Long, private val maxNs: Long, val outcome: String) {
        override fun toString(): String = "$counts counts of $CHUNK rows, each ${ms(minNs)} to ${ms(maxNs)} ms ($outcome)"

        private fun ms(ns: Long): String = "${ns / 1_000_000L}.${ns / 100_000L % 10L}"
    }

    /**
     * Runs [count] (one count to [CHUNK], returning its result) at least once, and again while the last count ended
     * before [deadlineNs] on [clockNs]. Stops early on a count that does not return [CHUNK] (outcome
     * `count n returned x`) and after [MAX_COUNTS] counts that all ended before the deadline (outcome
     * `stopped after MAX_COUNTS counts before the deadline`); otherwise the outcome is [FULL_COUNTS]. An exception
     * from [count] propagates. Times are compared by difference, as `System.nanoTime` requires.
     */
    fun runUntil(deadlineNs: Long, clockNs: () -> Long, count: () -> Long): Run {
        var counts = 0
        var minNs = Long.MAX_VALUE
        var maxNs = Long.MIN_VALUE
        var outcome = FULL_COUNTS
        while (true) {
            val startNs = clockNs()
            val result = count()
            val endNs = clockNs()
            counts++
            val elapsedNs = endNs - startNs
            minNs = minOf(minNs, elapsedNs)
            maxNs = maxOf(maxNs, elapsedNs)
            if (result != CHUNK) {
                outcome = "count $counts returned $result"
                break
            }
            if (endNs - deadlineNs >= 0) break
            if (counts >= MAX_COUNTS) {
                outcome = "stopped after $MAX_COUNTS counts before the deadline"
                break
            }
        }
        return Run(counts, minNs, maxNs, outcome)
    }
}

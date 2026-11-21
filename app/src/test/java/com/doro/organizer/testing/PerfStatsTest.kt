package com.doro.organizer.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The statistics of testing.md § 8 that every `@PerfTest` records and asserts its budget on. */
class PerfStatsTest {
    private val tenRuns = PerfStats(listOf(40.0, 10.0, 100.0, 30.0, 20.0, 90.0, 60.0, 50.0, 80.0, 70.0))

    @Test
    fun tenRuns_p90IsTheNinthSortedValue_andMedianTheMeanOfTheMiddleTwo() {
        assertEquals(90.0, tenRuns.p90, 0.0)
        assertEquals(55.0, tenRuns.median, 0.0)
        assertEquals(100.0, tenRuns.max, 0.0)
    }

    @Test
    fun oddRuns_medianIsTheMiddleValue() {
        val stats = PerfStats(listOf(3.0, 1.0, 2.0))

        assertEquals(2.0, stats.median, 0.0)
        assertEquals(3.0, stats.p90, 0.0)
    }

    @Test
    fun recordedRuns_keepTheirMeasuredOrder() {
        assertEquals(listOf(40.0, 10.0, 100.0), tenRuns.runsMs.take(3))
    }

    @Test
    fun budget_isCheckedOnItsOwnStatistic() {
        assertFalse(tenRuns.exceeds(PerfBudget(55, PerfStatistic.Median)))
        assertTrue(tenRuns.exceeds(PerfBudget(54, PerfStatistic.Median)))
        assertFalse(tenRuns.exceeds(PerfBudget(90, PerfStatistic.P90)))
        assertTrue(tenRuns.exceeds(PerfBudget(89, PerfStatistic.P90)))
    }

    @Test
    fun emulatorFactor_doublesTheLimit() {
        val budget = PerfBudget(30, PerfStatistic.Median)

        assertTrue(tenRuns.exceeds(budget))
        assertFalse(tenRuns.exceeds(budget, factor = 2))
    }

    @Test
    fun noRuns_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { PerfStats(emptyList()) }
    }
}

package com.doro.organizer.testing

/** The statistic a budget of architecture.md § Performance budgets is asserted on (testing.md § 8). */
enum class PerfStatistic { Median, P90 }

/** A budget of architecture.md § Performance budgets: [limitMs] on [statistic]. */
class PerfBudget(val limitMs: Long, val statistic: PerfStatistic) {
    override fun toString() = "$statistic ≤ $limitMs ms"
}

/**
 * The measured runs of one performance measurement in milliseconds and the statistics testing.md § 8 records: the
 * median, the p90 (nearest rank: the 9th of 10 sorted values) and the maximum. Pure, so the JVM pins the arithmetic.
 */
class PerfStats(val runsMs: List<Double>) {
    init {
        require(runsMs.isNotEmpty()) { "a measurement needs at least one run" }
    }

    private val sorted = runsMs.sorted()

    val median: Double = sorted.size.let { n ->
        if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    val p90: Double = sorted[(9 * sorted.size + 9) / 10 - 1]

    val max: Double = sorted.last()

    fun value(statistic: PerfStatistic): Double = when (statistic) {
        PerfStatistic.Median -> median
        PerfStatistic.P90 -> p90
    }

    /** True when [budget]'s statistic is above its limit times [factor] (2 on an emulator, testing.md § 8). */
    fun exceeds(budget: PerfBudget, factor: Int = 1): Boolean =
        value(budget.statistic) > budget.limitMs * factor.toDouble()
}

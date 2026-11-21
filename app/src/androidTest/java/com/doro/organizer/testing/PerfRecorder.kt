package com.doro.organizer.testing

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.OutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/**
 * The measurement mechanics of testing.md § 8, as the outermost rule of a [PerfTest] class
 * (`@get:Rule(order = -1)`). Debug builds are never measured: on a debuggable app the test is skipped before any
 * fixture runs. [measure] makes [WARM_UPS] warm-up runs and [RUNS] measured runs, timed with
 * `SystemClock.elapsedRealtimeNanos()`.
 * After a passing body every budget is asserted, all at once: the exact budget on a phone, twice the budget on an
 * emulator. Whether the test passed or not, the median, p90 and maximum of every measurement taken go as JSON
 * (`<class>-<method>.json`) into the TestStorage output of [openOutput]; a failed write is reported next to the test's
 * own result, never instead of it. Labels and trace sections are constants, never user data (DEC-70).
 */
class PerfRecorder : TestRule {
    private val measurements = mutableListOf<Measurement>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeFalse("debug builds are never measured (testing.md § 8)", targetIsDebuggable())
            val failures = mutableListOf<Throwable>()
            try {
                base.evaluate()
                assertWithinBudgets()
            } catch (failure: Throwable) {
                failures += failure
            }
            // Written after a failed body too: the runs measured before it are the record of a failing sign-off run.
            if (measurements.isNotEmpty()) {
                try {
                    write(description)
                } catch (failure: Exception) {
                    failures += failure
                }
            }
            MultipleFailureException.assertEmpty(failures)
        }
    }

    /**
     * Measures [action] under [label] against [budget] (null: recorded only). [prepare] runs before every run and is
     * not measured; with [trace], each measured run is inside that `android.os.Trace` section, which must begin and
     * end on one thread, so it is only for blocking actions.
     */
    inline fun measure(
        label: String,
        budget: PerfBudget?,
        trace: String? = null,
        prepare: () -> Unit = {},
        action: () -> Unit,
    ) {
        repeat(WARM_UPS) {
            prepare()
            action()
        }
        val runs = List(RUNS) {
            prepare()
            val start = SystemClock.elapsedRealtimeNanos()
            if (trace != null) Trace.beginSection(trace)
            try {
                action()
            } finally {
                if (trace != null) Trace.endSection()
            }
            (SystemClock.elapsedRealtimeNanos() - start) / NANOS_PER_MS
        }
        record(label, budget, PerfStats(runs))
    }

    fun record(label: String, budget: PerfBudget?, stats: PerfStats) {
        measurements += Measurement(label, budget, stats)
    }

    private fun write(description: Description) {
        val json = JSONObject()
            .put("test", "${description.className}#${description.methodName}")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
            .put("emulator", onEmulator)
            .put("measurements", JSONArray(measurements.map(Measurement::toJson)))
        val name = "${description.testClass.simpleName}-${description.methodName}.json"
        openOutput(name).bufferedWriter().use { it.write(json.toString(2)) }
    }

    private fun assertWithinBudgets() {
        val factor = if (onEmulator) EMULATOR_FACTOR else 1
        val over = measurements.filter { it.budget != null && it.stats.exceeds(it.budget, factor) }
        if (over.isNotEmpty()) {
            fail(over.joinToString("\n", "over budget (× $factor):\n") { "${it.label}: ${it.summary()}" })
        }
    }

    class Measurement(val label: String, val budget: PerfBudget?, val stats: PerfStats) {
        fun summary(): String = String.format(
            Locale.ROOT,
            "median %.1f ms, p90 %.1f ms, max %.1f ms, budget %s",
            stats.median,
            stats.p90,
            stats.max,
            budget ?: "recorded only",
        )

        fun toJson(): JSONObject = JSONObject()
            .put("label", label)
            .put("unit", "ms")
            .put("runs", JSONArray(stats.runsMs))
            .put("median", stats.median)
            .put("p90", stats.p90)
            .put("max", stats.max)
            .put("budgetMs", budget?.limitMs ?: JSONObject.NULL)
            .put("statistic", budget?.statistic?.name ?: JSONObject.NULL)
    }

    companion object {
        const val WARM_UPS = 3
        const val RUNS = 10
        const val NANOS_PER_MS = 1_000_000.0

        /** testing.md § 8: on an optional GMD emulator a budget fails above twice its value. */
        private const val EMULATOR_FACTOR = 2

        private val onEmulator: Boolean get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

        /** The instrumentation argument that makes AGP pull the TestStorage output after the run (testing.md § 8). */
        const val USE_TEST_STORAGE = "useTestStorageService"

        /**
         * Opens [name] for writing, replacing an earlier file of that name, in the androidx.test.services TestStorage
         * output (`/sdcard/googletest/test_outputfiles`, owned by the test services app), which AGP copies into the
         * connected run's additional test output when [USE_TEST_STORAGE] is `true`. Never the instrumentation's
         * `additionalTestOutputDir`: it lies in the app's own data, which the Orchestrator's `clearPackageData` deletes
         * after every test, so only the last test's files reached the host (T22).
         */
        fun openOutput(name: String): OutputStream {
            check(InstrumentationRegistry.getArguments().getString(USE_TEST_STORAGE) == "true") {
                "no $USE_TEST_STORAGE=true argument: run the perf job through Gradle on benchmark (testing.md § 9)"
            }
            return TestStorage().openOutputFile(name)
        }

        private fun targetIsDebuggable(): Boolean =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

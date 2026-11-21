package com.doro.organizer.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The perf output reaches the host (testing.md § 8): the Orchestrator's `clearPackageData` deletes the app's data
 * after every test, and with it the instrumentation's `additionalTestOutputDir`, so only the last test's files were
 * pulled (T22). The perf files go through TestStorage instead, which AGP pulls only when the `benchmark` run passes
 * `useTestStorageService=true`.
 */
class PerfOutputTest {
    @Test
    fun noInstrumentedTestWritesToTheAdditionalTestOutputDir() {
        val readers = instrumentedSources().filter { "\"additionalTestOutputDir\"" in it.readText() }

        assertEquals(emptyList<String>(), readers.map { it.relativeTo(SourceTree.srcRoot).invariantSeparatorsPath })
    }

    @Test
    fun perfRecorder_writesThroughTestStorage() {
        val recorder = instrumentedSources().single { it.name == "PerfRecorder.kt" }.readText()

        assertTrue("TestStorage().openOutputFile(" in recorder)
        assertTrue("const val USE_TEST_STORAGE = \"$ARGUMENT\"" in recorder)
    }

    @Test
    fun benchmarkRun_passesUseTestStorageService() {
        val build = SourceTree.srcRoot.resolveSibling("build.gradle.kts").readText()

        // Whitespace, line breaks and braces may vary; the source text is matched, not the evaluated build, so the
        // binding check is the perf run's output (testing.md § 9 `perf`).
        val argument = """testInstrumentationRunnerArguments\s*\[\s*"$ARGUMENT"\s*]\s*=\s*"true""""
        val condition = Regex("""(?m)^\s*if\s*\(\s*testType\s*==\s*"benchmark"\s*\)\s*\{?\s*$argument""")
        assertTrue("app/build.gradle.kts must pass $ARGUMENT=true on benchmark", condition.containsMatchIn(build))
    }

    private fun instrumentedSources(): List<File> = SourceTree.srcRoot.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("androidTest") }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .also { check(it.isNotEmpty()) { "no instrumented sources under ${SourceTree.srcRoot}" } }

    private companion object {
        const val ARGUMENT = "useTestStorageService"
    }
}

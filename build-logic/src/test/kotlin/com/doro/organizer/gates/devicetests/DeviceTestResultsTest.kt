package com.doro.organizer.gates.devicetests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The result check of the `device-tests` job (testing.md § 1, § 9) on results folders laid out as AGP writes them. */
class DeviceTestResultsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val results: File get() = File(temp.root, "connected/debug")

    /** `TEST-<device>.xml` listing [reported] (class to method) and a logcat file for each of [started]. */
    private fun run(device: String, reported: List<Pair<String, String>>, started: List<Pair<String, String>> = reported) {
        val report = buildString {
            appendLine("<?xml version='1.0' encoding='UTF-8' ?>")
            appendLine("""<testsuites tests="${reported.size}" failures="0" errors="0" skipped="0">""")
            appendLine("""  <testsuite name="suite" tests="${reported.size}">""")
            reported.forEach { (type, method) -> appendLine("""    <testcase name="$method" classname="$type" time="0.1" />""") }
            appendLine("  </testsuite>")
            appendLine("</testsuites>")
        }
        File(results, "TEST-$device.xml").apply { parentFile.mkdirs() }.writeText(report)
        val dir = File(results, device).apply { mkdirs() }
        File(dir, "device-info.pb").writeBytes(byteArrayOf(1))
        started.forEach { (type, method) -> File(dir, "logcat-$type-$method.txt").writeText("") }
    }

    @Test
    fun aCompleteRun_passes() {
        run("Phone - 16", listOf("a.NoteRowTest" to "links", "a.CrashSafetyTest" to "row[CS-29 over PasswordWithDeadSlot]"))

        assertEquals(emptyList<String>(), DeviceTestResults.violations(results))
    }

    @Test
    fun aRejectedClass_fails_byItsFile_andByTheCount() {
        val ran = listOf("a.NoteRowTest" to "links")
        run("Phone - 16", ran, ran + ("a.StartupEventTest" to "initializationError"))

        assertEquals(
            listOf(
                "JUnit rejected a test class (initializationError): logcat-a.StartupEventTest-initializationError.txt",
                "Phone - 16: 2 tests started (one logcat file each), the report lists 1",
            ),
            DeviceTestResults.violations(results),
        )
    }

    @Test
    fun moreTestsStartedThanReported_fails() {
        run("Phone - 16", listOf("a.A" to "one"), listOf("a.A" to "one", "a.B" to "two"))

        assertEquals(
            listOf("Phone - 16: 2 tests started (one logcat file each), the report lists 1"),
            DeviceTestResults.violations(results),
        )
    }

    @Test
    fun anIgnoredTest_reportedWithoutALogcatFile_passes() {
        run("Phone - 16", listOf("a.A" to "one", "a.A" to "ignored"), listOf("a.A" to "one"))

        assertEquals(emptyList<String>(), DeviceTestResults.violations(results))
    }

    @Test
    fun everyDevice_isCheckedOnItsOwn() {
        run("Phone - 16", listOf("a.A" to "one"))
        run("Tablet - 35", listOf("a.A" to "one"), listOf("a.A" to "one", "a.B" to "two"))

        assertEquals(
            listOf("Tablet - 35: 2 tests started (one logcat file each), the report lists 1"),
            DeviceTestResults.violations(results),
        )
    }

    @Test
    fun aRunWhoseFilterMatchedNoTest_fails() {
        run("Phone - 16", emptyList())

        assertEquals(listOf("Phone - 16: the run reported no tests"), DeviceTestResults.violations(results))
    }

    @Test
    fun aRunThatProducedNothing_fails() {
        val missing = DeviceTestResults.violations(results)
        results.mkdirs()
        val empty = DeviceTestResults.violations(results)

        assertTrue(missing.single(), missing.single().startsWith("no results in "))
        assertTrue(empty.single(), empty.single().startsWith("no TEST-*.xml in "))
    }
}

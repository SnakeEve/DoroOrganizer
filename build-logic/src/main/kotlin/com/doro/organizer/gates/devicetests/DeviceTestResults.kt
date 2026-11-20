package com.doro.organizer.gates.devicetests

import com.doro.organizer.gates.manifest.parseXml
import com.doro.organizer.gates.manifest.selfAndDescendants
import java.io.File

/**
 * The result check of the `device-tests` job (testing.md § 1, § 9) over AGP's results folder of a connected run
 * (`build/outputs/androidTest-results/connected/<build type>/`: one `TEST-<device>.xml` and one `<device>/` folder
 * per device). A test class that JUnit rejects never reaches the XML and the run still passes; it leaves a
 * `logcat-<class>-initializationError.txt`, and more tests started on the device (one `logcat-*.txt` each) than the
 * XML reports. An ignored test is reported without a logcat file, so fewer files than tests is no violation. A report
 * that lists no test at all fails too: a targeted run whose filter matched nothing tested nothing.
 */
object DeviceTestResults {
    private const val REJECTED = "initializationError"

    fun violations(resultsDir: File): List<String> {
        if (!resultsDir.isDirectory) return listOf("no results in $resultsDir: the connected run produced none")
        val reports = resultsDir.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.extension == "xml" }
            .orEmpty().sortedBy { it.name }
        if (reports.isEmpty()) return listOf("no TEST-*.xml in $resultsDir: the connected run reported nothing")
        val rejected = resultsDir.walkTopDown().filter { it.isFile && REJECTED in it.name }
            .map { "JUnit rejected a test class (initializationError): ${it.name}" }
            .toList()
        return rejected + reports.mapNotNull(::countMismatch)
    }

    /** A device whose report lists no test, or on which more tests started than its report lists. */
    private fun countMismatch(report: File): String? {
        val device = report.name.removePrefix("TEST-").removeSuffix(".xml")
        val reported = parseXml(report.readText()).selfAndDescendants().count { it.tagName == "testcase" }
        val started = File(report.parentFile, device)
            .listFiles { file -> file.isFile && file.name.startsWith("logcat-") && file.extension == "txt" }
            .orEmpty().size
        return when {
            reported == 0 -> "$device: the run reported no tests"
            started > reported -> "$device: $started tests started (one logcat file each), the report lists $reported"
            else -> null
        }
    }
}

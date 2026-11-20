package com.doro.organizer.gates.devicetests

import com.doro.organizer.gates.failOnViolations
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * The result check of the `device-tests` job (testing.md § 9): fails on a test class JUnit rejected and on a device
 * that started more tests than its report lists, which `connected<BuildType>AndroidTest` itself lets pass. It
 * finalizes that task, so every connected run is checked, targeted runs included.
 */
@DisableCachingByDefault(because = "It checks the results of the connected run that just ended")
abstract class CheckDeviceTestResultsTask : DefaultTask() {
    /** AGP's results folder of the connected run; internal, because a run that produced none must fail here. */
    @get:Internal
    abstract val resultsDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() = failOnViolations("device-tests", DeviceTestResults.violations(resultsDirectory.get().asFile))
}

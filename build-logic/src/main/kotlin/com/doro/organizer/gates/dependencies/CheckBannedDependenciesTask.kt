package com.doro.organizer.gates.dependencies

import com.doro.organizer.gates.failOnViolations
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** The `dependencies` gate (testing.md § 9, DEC-05 to DEC-10, DEC-12, DEC-68). */
@DisableCachingByDefault(because = "A verification gate has no outputs")
abstract class CheckBannedDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bannedDependencies: RegularFileProperty

    /** Every runtime classpath of the app's variants and their test components, by configuration name. */
    @get:Input
    abstract val moduleGraphs: MapProperty<String, ModuleGraph>

    @TaskAction
    fun check() {
        val banned = BannedDependencies.parse(bannedDependencies.get().asFile.readText())
        failOnViolations("dependencies", banned.violations(moduleGraphs.get()))
    }
}

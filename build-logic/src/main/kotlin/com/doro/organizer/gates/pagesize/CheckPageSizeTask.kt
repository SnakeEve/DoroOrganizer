package com.doro.organizer.gates.pagesize

import com.android.build.api.variant.BuiltArtifactsLoader
import com.doro.organizer.gates.configEntries
import com.doro.organizer.gates.failOnViolations
import com.doro.organizer.gates.singleApk
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/** The static `page-size` gate (testing.md § 9, DEC-14, DEC-15): zipalign plus the LOAD alignment of every `.so`. */
@DisableCachingByDefault(because = "A verification gate has no outputs")
abstract class CheckPageSizeTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseApkDirectory: DirectoryProperty

    @get:Internal
    abstract val apkLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipalign: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeLibraryAllowlist: RegularFileProperty

    @TaskAction
    fun check() {
        val apk = singleApk(apkLoader.get(), releaseApkDirectory.get())
        val allowed = configEntries(nativeLibraryAllowlist.get().asFile.readText()).toSet()
        val violations = buildList {
            zipalignFailure(apk)?.let(::add)
            addAll(PageSizeRules.violations(nativeLibraries(apk), allowed))
        }
        failOnViolations("page-size", violations)
    }

    private fun zipalignFailure(apk: File): String? {
        val output = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(zipalign.get().asFile.path, "-c", "-P", "16", "-v", "4", apk.path)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) return null
        val details = output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && !it.contains("(OK") }
        return "zipalign -c -P 16 -v 4 failed:\n" + details.joinToString("\n") { "      $it" }
    }

    private fun nativeLibraries(apk: File): Map<String, ByteArray> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }
}

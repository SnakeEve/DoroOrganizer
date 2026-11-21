package com.doro.organizer.gates.logs

import com.android.build.api.variant.BuiltArtifactsLoader
import com.doro.organizer.gates.failOnViolations
import com.doro.organizer.gates.singleApk
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * The `release-logs` gate (testing.md § 9, DEC-70, DEC-08): R8 strips every log call from the release dex. It also
 * fails a release R8 configuration that holds the generated test-APK keep rules of `qa` and `benchmark` (DEC-103 (5)).
 */
@DisableCachingByDefault(because = "A verification gate has no outputs")
abstract class CheckReleaseLogsTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseApkDirectory: DirectoryProperty

    @get:Internal
    abstract val apkLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** The full configuration R8 applied; AGP writes it next to `mapping.txt`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val r8Configuration: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dexdump: RegularFileProperty

    @TaskAction
    fun check() {
        val originalNames = mappingFile.get().asFile.useLines(block = R8Outputs::originalClassNames)
        val scanner = DexLogScanner { originalNames[it] ?: it }
        val violations = buildList {
            if (!r8Configuration.get().asFile.useLines(block = R8Outputs::hasLogStrippingRule)) {
                add("the R8 configuration lacks '${R8Outputs.LOG_STRIPPING_RULE}' (DEC-70)")
            }
            if (r8Configuration.get().asFile.useLines(block = R8Outputs::hasTestApkKeepRules)) {
                add("the R8 configuration holds the generated test-APK keep rules, which only qa and benchmark may get (DEC-103 (5))")
            }
            extractDexFiles(singleApk(apkLoader.get(), releaseApkDirectory.get())).forEach { dex ->
                addAll(disassemble(dex).useLines(block = scanner::violations))
            }
        }
        failOnViolations("release-logs", violations.distinct())
    }

    private fun extractDexFiles(apk: File): List<File> {
        val directory = temporaryDir.apply { listFiles()?.forEach(File::delete) }
        return ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }.map { entry ->
                File(directory, entry.name).also { file -> zip.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) } }
            }.toList()
        }.ifEmpty { throw GradleException("The release APK contains no classes*.dex") }
    }

    private fun disassemble(dex: File): File {
        val listing = File(dex.parentFile, "${dex.name}.txt")
        listing.outputStream().use { output ->
            exec.exec {
                commandLine(dexdump.get().asFile.path, "-d", dex.path)
                standardOutput = output
            }
        }
        return listing
    }

    private companion object {
        val DEX_ENTRY = Regex("""classes\d*\.dex""")
    }
}

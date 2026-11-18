package com.doro.organizer.gates.manifest

import com.android.build.api.variant.BuiltArtifactsLoader
import com.doro.organizer.gates.failOnViolations
import com.doro.organizer.gates.singleApk
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The `manifest` gate (testing.md § 9, security.md § 9.3, DEC-68, DEC-69). */
@DisableCachingByDefault(because = "A verification gate has no outputs")
abstract class CheckManifestTask : DefaultTask() {
    /** Build type to application id, for every variant (DEC-15). */
    @get:Input
    abstract val applicationIds: MapProperty<String, String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseResourceDirectories: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseApkDirectory: DirectoryProperty

    @get:Internal
    abstract val apkLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val permissionAllowlist: RegularFileProperty

    @TaskAction
    fun check() {
        val ids = applicationIds.get()
        val releaseId = checkNotNull(ids["release"]) { "No release variant" }
        val allowed = ManifestRules.permissionAllowlist(permissionAllowlist.get().asFile.readText(), releaseId)
        val manifest = parseXml(releaseManifest.get().asFile.readText())
        val violations = buildList {
            addAll(ManifestRules.applicationIdViolations(ids))
            addAll(ManifestRules.releaseViolations(manifest, allowed, releaseId))
            addAll(FilePaths.resourceViolations(releaseResourceDirectories.files, ManifestRules.referencesFilePaths(manifest)))
            addAll(ManifestRules.packagingViolations(libEntries(singleApk(apkLoader.get(), releaseApkDirectory.get()))))
        }
        failOnViolations("manifest", violations)
    }

    private fun libEntries(apk: File): Map<String, Boolean> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.startsWith("lib/") && !it.isDirectory }
            .associate { it.name to (it.method == ZipEntry.STORED) }
    }
}

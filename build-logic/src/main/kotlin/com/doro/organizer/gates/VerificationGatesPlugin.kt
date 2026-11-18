package com.doro.organizer.gates

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.doro.organizer.gates.dependencies.CheckBannedDependenciesTask
import com.doro.organizer.gates.dependencies.ModuleGraph
import com.doro.organizer.gates.logs.CheckReleaseLogsTask
import com.doro.organizer.gates.manifest.CheckManifestTask
import com.doro.organizer.gates.pagesize.CheckPageSizeTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers the local verification gates of testing.md § 9 (DEC-14, DEC-15) on the `:app` module:
 * `checkReleaseManifestPolicy`, `checkReleasePageSize`, `checkBannedDependencies`, `checkReleaseLogs`, and
 * `verificationGates`, which runs all four. Their lists live in the root `config/` directory.
 */
class VerificationGatesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") { register(project) }
    }

    private fun register(project: Project) {
        val android = project.extensions.getByType(ApplicationExtension::class.java)
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val config = project.isolated.rootProject.projectDirectory.dir("config")
        fun buildTool(name: String): Provider<RegularFile> = components.sdkComponents.sdkDirectory.map {
            it.file("build-tools/${android.buildToolsVersion}/$name${if (isWindows()) ".exe" else ""}")
        }

        val manifest = project.tasks.register("checkReleaseManifestPolicy", CheckManifestTask::class.java) {
            permissionAllowlist.set(config.file("permission-allowlist.txt"))
        }
        val pageSize = project.tasks.register("checkReleasePageSize", CheckPageSizeTask::class.java) {
            zipalign.set(buildTool("zipalign"))
            nativeLibraryAllowlist.set(config.file("native-library-allowlist.txt"))
        }
        val logs = project.tasks.register("checkReleaseLogs", CheckReleaseLogsTask::class.java) {
            dexdump.set(buildTool("dexdump"))
        }
        val dependencies = project.tasks.register("checkBannedDependencies", CheckBannedDependenciesTask::class.java) {
            bannedDependencies.set(config.file("banned-dependencies.txt"))
        }
        val gates = listOf(manifest, pageSize, logs, dependencies)
        gates.forEach { gate -> gate.configure { group = LifecycleBasePlugin.VERIFICATION_GROUP } }
        project.tasks.register("verificationGates") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the manifest, page-size, release-logs and dependencies gates (testing.md § 9)."
            dependsOn(gates)
        }

        components.onVariants { variant ->
            manifest.configure { applicationIds.put(checkNotNull(variant.buildType), variant.applicationId) }
            // Every build type ships to a device (DEC-12, DEC-15), so every variant and test component is checked:
            // a superset of the four configurations testing.md § 9 names, whichever build type hosts androidTest.
            dependencies.configure {
                variant.components.map { it.runtimeConfiguration }.forEach { configuration ->
                    moduleGraphs.put(configuration.name, configuration.incoming.resolutionResult.rootComponent.map { ModuleGraph.of(it) })
                }
            }
            if (variant.buildType != "release") return@onVariants
            val apk = variant.artifacts.get(SingleArtifact.APK)
            val loader = variant.artifacts.getBuiltArtifactsLoader()
            manifest.configure {
                releaseManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                variant.sources.res?.let { releaseResourceDirectories.from(it.all) }
                releaseApkDirectory.set(apk)
                apkLoader.set(loader)
            }
            pageSize.configure {
                releaseApkDirectory.set(apk)
                apkLoader.set(loader)
            }
            logs.configure {
                releaseApkDirectory.set(apk)
                apkLoader.set(loader)
                val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                mappingFile.set(mapping)
                r8Configuration.set(mapping.map { it.asFile.resolveSibling("configuration.txt") }.let(project.layout::file))
            }
        }
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
}

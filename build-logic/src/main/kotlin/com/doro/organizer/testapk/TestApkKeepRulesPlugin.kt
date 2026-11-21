package com.doro.organizer.testapk

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * DEC-103 (5): for the minified build types whose instrumented tests run in the app's process, `qa` and `benchmark`,
 * adds a generated keep-rules file to the app's R8 configuration ([GenerateTestApkKeepRulesTask],
 * `generate<Variant>TestApkKeepRules`). Only a variant that has a test APK gets one: AGP builds androidTest for the
 * `-PtestBuildType` build type alone ([TestApkKeepRules.appliesTo]). The task reads the variant's own R8 configuration
 * files to tell whether R8 obfuscates it (`qa`) or not (`benchmark`, `-dontobfuscate`). `release` never gets one; its
 * R8 configuration stays as DEC-13 sets it, and the `release-logs` gate fails a release configuration that holds
 * generated rules.
 */
class TestApkKeepRulesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") { register(project) }
    }

    private fun register(project: Project) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        // TraceReferences ships in AGP's builder artifact, with the R8 that minifies the app.
        val r8 = project.configurations.detachedConfiguration(
            project.dependencies.create("com.android.tools.build:builder:${components.pluginVersion.version}"),
        ).apply { isTransitive = false }
        // The optional android.test.* jars next to compileSdk's android.jar (platforms/android-NN/optional/).
        val optionalJars = components.sdkComponents.bootClasspath.map { jars ->
            jars.map { it.asFile }.filter { it.name == "android.jar" }.flatMap { androidJar ->
                OPTIONAL_JARS.map { androidJar.resolveSibling("optional/$it") }.filter { it.isFile }
            }
        }
        val allowlist = project.isolated.rootProject.projectDirectory.file("config/test-apk-missing-references.txt")
        val android = project.extensions.getByType(ApplicationExtension::class.java)
        components.onVariants { variant ->
            val testApk = variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]
            if (!TestApkKeepRules.appliesTo(variant.buildType, variant.isMinifyEnabled, testApk != null)) return@onVariants
            val task = project.tasks.register(
                variant.computeTaskName("generate", "TestApkKeepRules"),
                GenerateTestApkKeepRulesTask::class.java,
            ) {
                description = "Keeps in the minified ${variant.name} app what its in-process test APK uses (DEC-103 (5))."
                r8Classpath.from(r8)
                libraryJars.set(components.sdkComponents.bootClasspath)
                optionalLibraryJars.from(optionalJars)
                missingReferenceAllowlist.set(allowlist)
                // The DSL's proguardFiles of the variant (defaultConfig, build type, flavors): -dontobfuscate there
                // (proguard-benchmark.pro) means no rule set 4.
                val dslFiles = android.defaultConfig.proguardFiles +
                    android.buildTypes.getByName(checkNotNull(variant.buildType)).proguardFiles +
                    variant.productFlavors.flatMap { (_, flavor) -> android.productFlavors.getByName(flavor).proguardFiles }
                r8Configurations.from(TestApkKeepRules.ownConfigurations(dslFiles, project.layout.buildDirectory.get().asFile))
                val output = "intermediates/doro_test_apk_keep_rules/${variant.name}"
                keepRules.set(project.layout.buildDirectory.file("$output/test-apk-keep-rules.pro"))
                missingReferences.set(project.layout.buildDirectory.file("$output/missing-references.txt"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(task)
                .toGet(ScopedArtifact.CLASSES, GenerateTestApkKeepRulesTask::appJars, GenerateTestApkKeepRulesTask::appDirectories)
            checkNotNull(testApk).artifacts.forScope(ScopedArtifacts.Scope.ALL).use(task)
                .toGet(ScopedArtifact.CLASSES, GenerateTestApkKeepRulesTask::testApkJars, GenerateTestApkKeepRulesTask::testApkDirectories)
            variant.proguardFiles.add(task.flatMap { it.keepRules })
        }
    }

    private companion object {
        val OPTIONAL_JARS = listOf("android.test.base.jar", "android.test.mock.jar", "android.test.runner.jar")
    }
}

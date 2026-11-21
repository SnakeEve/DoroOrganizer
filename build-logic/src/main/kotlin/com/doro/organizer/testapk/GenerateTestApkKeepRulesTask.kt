package com.doro.organizer.testapk

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Writes the keep rules of [TestApkKeepRules] for one minified build type whose instrumented tests run in the app's
 * process (DEC-103 (5)): R8 TraceReferences over class files, with the test APK's classes as source, the app's program
 * classes and runtime libraries before R8 as target and the platform's `android.jar` with its optional `android.test.*`
 * jars as library, followed by the supertype chains the test APK's classes and lambdas extend or implement and the
 * service types the test APK provides, and, when [r8Configurations] obfuscate, the subtypes of the non-public types those
 * rules keep ([TestApkKeepRules.packageRules]). Fails on a reference that nothing defines unless
 * [missingReferenceAllowlist] covers it, and on a rule line of the first three sets that it cannot read
 * ([TestApkKeepRules.unreadableOptions]).
 */
@CacheableTask
abstract class GenerateTestApkKeepRulesTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The test APK's classes: the androidTest classes and the test-only libraries AGP packages with them. */
    @get:Classpath
    abstract val testApkJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val testApkDirectories: ListProperty<Directory>

    /** The app's classes as R8 reads them: its own classes and every runtime library. */
    @get:Classpath
    abstract val appJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val appDirectories: ListProperty<Directory>

    /** The boot classpath of compileSdk (`android.jar`): references into it need no rule. */
    @get:CompileClasspath
    abstract val libraryJars: ListProperty<RegularFile>

    /**
     * compileSdk's optional `android.test.base`, `android.test.runner` and `android.test.mock` jars: the device provides
     * them to the test APK (the runner's `uses-library`), so references into them need no rule either.
     */
    @get:CompileClasspath
    abstract val optionalLibraryJars: ConfigurableFileCollection

    /**
     * `config/test-apk-missing-references.txt`: the holder classes (`a.b.C`) or packages (`a.b.*`) of references that
     * neither the app nor the library defines and that are known to be harmless. Any other missing reference fails the
     * task: it would be a `NoClassDefFoundError` or `NoSuchMethodError` on the phone.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val missingReferenceAllowlist: RegularFileProperty

    /**
     * The variant's own R8 configuration files (its `proguardFiles` outside the build directory): a `-dontobfuscate` in
     * any of them makes it a non-obfuscating variant, which gets no rule set 4 ([TestApkKeepRules.obfuscates]); an
     * `-include` in any of them fails the task. AGP's default files, in the build directory, never hold one.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val r8Configurations: ConfigurableFileCollection

    /** The R8 that AGP runs, for its TraceReferences tool. */
    @get:Classpath
    abstract val r8Classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val keepRules: RegularFileProperty

    /**
     * TraceReferences' report of the test APK's references that neither the app nor the library defines (hidden APIs,
     * `java.beans`, `javax.lang.model`). No keep rule can provide them; the task fails unless
     * [missingReferenceAllowlist] covers each one.
     */
    @get:OutputFile
    abstract val missingReferences: RegularFileProperty

    @TaskAction
    fun generate() {
        val work = temporaryDir.apply { listFiles()?.forEach { it.deleteRecursively() } }
        val source = testApkJars.get().map { it.asFile } + testApkDirectories.get().mapIndexed { i, dir -> jarOf(dir.asFile, File(work, "source-$i.jar")) }
        val target = appJars.get().map { it.asFile } + appDirectories.get().mapIndexed { i, dir -> jarOf(dir.asFile, File(work, "target-$i.jar")) }
        val traced = File(work, "traced.pro")
        val arguments = File(work, "tracereferences.args")
        arguments.writeText(
            buildList {
                add("--keep-rules")
                libraryJars.get().forEach { add("--lib"); add(it.asFile.path) }
                optionalLibraryJars.files.sorted().forEach { add("--lib"); add(it.path) }
                target.forEach { add("--target"); add(it.path) }
                source.forEach { add("--source"); add(it.path) }
                add("--output"); add(traced.path)
            }.joinToString("\n"),
        )
        val report = missingReferences.get().asFile
        val result = report.outputStream().use { output ->
            exec.javaexec {
                classpath(r8Classpath)
                mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
                args("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info", "@${arguments.path}")
                maxHeapSize = "1g"
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
        }
        if (result.exitValue != 0) throw GradleException("R8 TraceReferences failed (exit ${result.exitValue}):\n${report.readText()}")
        val allowlist = missingReferenceAllowlist.get().asFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val unexpected = TestApkKeepRules.unexpectedMissing(report.readLines(), allowlist)
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "The test APK references ${unexpected.size} definitions that neither the app nor the platform jars have " +
                    "(DEC-103 (5)): a NoClassDefFoundError or NoSuchMethodError on the phone. Fix the dependency, or add the " +
                    "holder to ${missingReferenceAllowlist.get().asFile.name} with the reason it is harmless:\n" +
                    unexpected.joinToString("\n"),
            )
        }
        val app = ClassHeader.readAll(target)
        val chains = TestApkKeepRules.supertypeChains(ClassHeader.readAll(source), app)
        val services = TestApkKeepRules.serviceRules(ClassHeader.serviceTypes(source), app)
        val obfuscates = TestApkKeepRules.obfuscates(r8Configurations.files.filter(File::isFile).sorted().map(File::readText))
        keepRules.get().asFile.writeText(
            TestApkKeepRules.render(path, source.size, target.size, traced.readText(), chains, services, app, obfuscates),
        )
    }

    /** A jar of the class files under [directory], for R8, which reads class files from archives. */
    private fun jarOf(directory: File, jar: File): File {
        ZipOutputStream(jar.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter(File::isFile).sortedBy { it.invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return jar
    }
}

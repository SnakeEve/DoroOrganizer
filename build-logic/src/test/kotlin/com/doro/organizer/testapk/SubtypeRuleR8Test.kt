package com.doro.organizer.testapk

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rule set 4 of DEC-103 (5) against the R8 that AGP runs (its `builder` artifact, the AGP version of the catalog): the
 * minimal reproduction of the T22 `qa` crash in `src/test/java/.../repackaging`. R8 runs in full mode (its default, as
 * in AGP) with `-allowaccessmodification` (from `proguard-android-optimize.txt`) and writes dex, as for the app: with
 * class-file output R8 9.5.29 does not move the class. Only the mapping is read.
 *
 * If [withoutRuleSetFour_r8MovesAnIndirectSubtypeOfTheKeptPackagePrivatePartOutOfItsPackage] fails after an AGP update,
 * R8 no longer moves the class here: the fixture no longer reproduces the crash and must be reshaped (with the `qa`
 * access check of DEC-103 (5)) before the other test proves anything.
 */
class SubtypeRuleR8Test {
    @get:Rule
    val temporary = TemporaryFolder()

    private val pkg = "com.doro.organizer.testapk.repackaging"

    private val fixture = listOf("Facade", "Part0", "Unused1", "Unused2", "Part1", "Part2", "Part3", "app/Main")
        .map { "${pkg.replace('.', '/')}/$it" }

    /** The rules of the reproduction, as TraceReferences writes them: plain `-keep` of the facade and the used parts. */
    private val keepRules = listOf(
        "-keep class $pkg.app.Main {", "  public static void main(java.lang.String[]);", "}",
        "-keep class $pkg.Facade {", "}",
        "-keep class $pkg.Part0 {", "  public static int part0(java.util.List);", "}",
        "-keep class $pkg.Part3 {", "  public static boolean part3(java.util.Collection, java.lang.Iterable);", "}",
    )

    @Test
    fun ruleSetFour_ofTheFixture_keepsTheSubtypesOfBothKeptPackagePrivateParts() {
        assertEquals(
            listOf(
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * extends $pkg.Part0",
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * extends $pkg.Part3",
            ),
            ruleSetFour(),
        )
    }

    // The T22 crash: Part2 (CollectionsKt__MutableCollectionsJVMKt) renamed into the root package, away from Part0.
    @Test
    fun withoutRuleSetFour_r8MovesAnIndirectSubtypeOfTheKeptPackagePrivatePartOutOfItsPackage() {
        val mapping = minify(keepRules)
        assertEquals("$pkg.Part0", mapping.getValue("$pkg.Part0"))
        val part2 = mapping.getValue("$pkg.Part2")
        assertNotEquals(pkg, part2.substringBeforeLast('.', ""))
        assertTrue(movedOut(mapping).isNotEmpty())
    }

    @Test
    fun withRuleSetFour_everySurvivingClassOfThePackageStaysInIt() {
        val mapping = minify(keepRules + ruleSetFour())
        assertEquals("$pkg.Part2", mapping.getValue("$pkg.Part2"))
        assertEquals(emptyMap<String, String>(), movedOut(mapping))
    }

    private fun ruleSetFour(): List<String> {
        val app = fixture.associateWith { ClassHeader.read(classBytes(it).inputStream()) }
        return TestApkKeepRules.packageRules(TestApkKeepRules.keptTypes(keepRules), app)
    }

    /** The fixture's classes that R8 kept but renamed into another package, original to new name. */
    private fun movedOut(mapping: Map<String, String>): Map<String, String> = mapping.filter { (original, renamed) ->
        original.startsWith("$pkg.") && original.substringBeforeLast('.') == pkg && !renamed.startsWith(REMOVED) &&
            renamed.substringBeforeLast('.', "") != pkg
    }

    /** Runs R8 on the fixture with [rules] and returns its class mapping, original to new name. */
    private fun minify(rules: List<String>): Map<String, String> {
        val input = temporary.newFile("in.jar")
        ZipOutputStream(input.outputStream()).use { zip ->
            fixture.forEach { name ->
                zip.putNextEntry(ZipEntry("$name.class"))
                zip.write(classBytes(name))
                zip.closeEntry()
            }
        }
        val output = temporary.newFolder()
        val mapping = File(output, "mapping.txt")
        val jdk = JdkClassFileProvider.fromSystemJdk()
        try {
            R8.run(
                R8Command.builder()
                    .addProgramFiles(input.toPath())
                    .addLibraryResourceProvider(jdk)
                    .addProguardConfiguration(listOf("-allowaccessmodification") + rules, Origin.unknown())
                    .setMode(CompilationMode.RELEASE)
                    .setMinApiLevel(28)
                    .setProguardMapOutputPath(mapping.toPath())
                    .setOutput(output.toPath(), OutputMode.DexIndexed)
                    .build(),
            )
        } finally {
            (jdk as Closeable).close()
        }
        return mapping.readLines().mapNotNull { CLASS_LINE.matchEntire(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun classBytes(binaryName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$binaryName.class")) { "no class file for $binaryName" }.use { it.readBytes() }

    private companion object {
        val CLASS_LINE = Regex("""^(\S+) -> (\S+):$""")
        const val REMOVED = "R8\$\$REMOVED\$\$CLASS"
    }
}

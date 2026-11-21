package com.doro.organizer.testapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestApkKeepRulesTest {
    private fun header(name: String, superName: String? = "java/lang/Object", vararg interfaces: String) =
        name to ClassHeader(name, superName, interfaces.toList())

    private fun appInterface(name: String, vararg interfaces: String) =
        name to ClassHeader(name, "java/lang/Object", interfaces.toList(), isInterface = true)

    // The T22 case: kotlinx-coroutines-test's TestScopeImpl (test APK) extends AbstractCoroutine (app), whose superclass
    // JobSupport declares the toString() that TestScopeImpl overrides.
    private val app = mapOf(
        header("kotlinx/coroutines/AbstractCoroutine", "kotlinx/coroutines/JobSupport", "kotlinx/coroutines/Job", "kotlin/coroutines/Continuation", "kotlinx/coroutines/CoroutineScope"),
        header("kotlinx/coroutines/JobSupport", "java/lang/Object", "kotlinx/coroutines/Job", "kotlinx/coroutines/ChildJob", "kotlinx/coroutines/ParentJob"),
        header("kotlinx/coroutines/Job", "java/lang/Object", "kotlin/coroutines/CoroutineContext\$Element"),
        header("kotlinx/coroutines/ChildJob", "java/lang/Object", "kotlinx/coroutines/Job"),
        header("kotlinx/coroutines/ParentJob", "java/lang/Object", "kotlinx/coroutines/Job"),
        header("kotlin/coroutines/CoroutineContext\$Element", "java/lang/Object", "kotlin/coroutines/CoroutineContext"),
        header("kotlin/coroutines/CoroutineContext"),
        header("kotlin/coroutines/Continuation"),
        header("kotlinx/coroutines/CoroutineScope"),
        header("kotlinx/coroutines/Unrelated", "kotlinx/coroutines/JobSupport"),
        header("com/doro/organizer/data/NoteRepository"),
        appInterface("com/doro/organizer/ui/common/Announcer", "com/doro/organizer/ui/common/Speaker"),
        appInterface("com/doro/organizer/ui/common/Speaker"),
        header("com/doro/organizer/ui/common/AnnouncerImpl", "java/lang/Object", "com/doro/organizer/ui/common/Announcer"),
        appInterface("kotlinx/coroutines/internal/MainDispatcherFactory"),
    )

    @Test
    fun supertypeChains_followsEveryAppSupertype_toThePlatform() {
        val testApk = mapOf(
            header("kotlinx/coroutines/test/internal/TestScopeImpl", "kotlinx/coroutines/AbstractCoroutine", "kotlinx/coroutines/test/TestScope"),
            header("kotlinx/coroutines/test/TestScope", "java/lang/Object", "kotlinx/coroutines/CoroutineScope"),
        )
        assertEquals(
            listOf(
                "kotlin/coroutines/Continuation",
                "kotlin/coroutines/CoroutineContext",
                "kotlin/coroutines/CoroutineContext\$Element",
                "kotlinx/coroutines/AbstractCoroutine",
                "kotlinx/coroutines/ChildJob",
                "kotlinx/coroutines/CoroutineScope",
                "kotlinx/coroutines/Job",
                "kotlinx/coroutines/JobSupport",
                "kotlinx/coroutines/ParentJob",
            ),
            TestApkKeepRules.supertypeChains(testApk, app),
        )
    }

    @Test
    fun supertypeChains_ignoresPlatformAndTestApkSupertypes_andAppSubtypes() {
        val testApk = mapOf(
            header("com/doro/organizer/testing/FakeRunnable", "java/lang/Object", "java/lang/Runnable"),
            header("com/doro/organizer/testing/Base"),
            header("com/doro/organizer/testing/Derived", "com/doro/organizer/testing/Base"),
        )
        assertEquals(emptyList<String>(), TestApkKeepRules.supertypeChains(testApk, app))
    }

    @Test
    fun supertypeChains_anInterfaceAlone() {
        val testApk = mapOf(header("com/doro/organizer/testing/FakeRepository", "java/lang/Object", "com/doro/organizer/data/NoteRepository"))
        assertEquals(listOf("com/doro/organizer/data/NoteRepository"), TestApkKeepRules.supertypeChains(testApk, app))
    }

    // A lambda of the test APK compiles to an invokedynamic; D8 turns it into a synthetic class that implements the
    // functional interface, a class no class file of the test APK shows.
    @Test
    fun supertypeChains_followsTheFunctionalInterfacesOfTestApkLambdas_andTheirAppSuperinterfaces() {
        val testApk = mapOf(
            "com/doro/organizer/testing/Fixture" to ClassHeader(
                "com/doro/organizer/testing/Fixture", "java/lang/Object", emptyList(),
                lambdaTypes = listOf("com/doro/organizer/ui/common/Announcer", "com/doro/organizer/ui/common/AnnouncerImpl", "java/lang/String"),
            ),
        )
        assertEquals(
            listOf("com/doro/organizer/ui/common/Announcer", "com/doro/organizer/ui/common/Speaker"),
            TestApkKeepRules.supertypeChains(testApk, app),
        )
    }

    @Test
    fun appliesTo_onlyAMinifiedQaOrBenchmarkVariantWithATestApk_neverRelease() {
        assertTrue(TestApkKeepRules.appliesTo("qa", isMinifyEnabled = true, hasAndroidTest = true))
        assertTrue(TestApkKeepRules.appliesTo("benchmark", isMinifyEnabled = true, hasAndroidTest = true))
        assertFalse(TestApkKeepRules.appliesTo("release", isMinifyEnabled = true, hasAndroidTest = true))
        assertFalse(TestApkKeepRules.appliesTo("release", isMinifyEnabled = true, hasAndroidTest = false))
        assertFalse(TestApkKeepRules.appliesTo("debug", isMinifyEnabled = false, hasAndroidTest = true))
        assertFalse(TestApkKeepRules.appliesTo("debug", isMinifyEnabled = true, hasAndroidTest = true))
        assertFalse(TestApkKeepRules.appliesTo("qa", isMinifyEnabled = true, hasAndroidTest = false))
        assertFalse(TestApkKeepRules.appliesTo("benchmark", isMinifyEnabled = false, hasAndroidTest = true))
        assertFalse(TestApkKeepRules.appliesTo(null, isMinifyEnabled = true, hasAndroidTest = true))
    }

    @Test
    fun serviceRules_keepTheServiceTypesTheAppDefines() {
        assertEquals(
            listOf("-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }"),
            TestApkKeepRules.serviceRules(listOf("org/junit/NotInTheApp", "kotlinx/coroutines/internal/MainDispatcherFactory"), app),
        )
    }

    @Test
    fun unexpectedMissing_reportsWhatTheAllowlistDoesNotCover_byHolderClassOrPackage() {
        val report = listOf(
            "Info: Missing class java.beans.BeanInfo (referenced from: java.beans.PropertyDescriptor[] org.hamcrest.beans.PropertyUtil.propertyDescriptorsFor(java.lang.Object, java.lang.Class))",
            "Missing method java.beans.PropertyDescriptor[] java.beans.BeanInfo.getPropertyDescriptors() (referenced from: x)",
            "Missing class javax.lang.model.element.Modifier (referenced from: javax.lang.model.element.Modifier[] com.google.errorprone.annotations.IncompatibleModifiers.value() and 1 other context)",
            "Missing method android.app.Instrumentation\$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder) (referenced from: y)",
            "Missing method void androidx.compose.ui.Modifier.newApi(int, java.lang.String) (referenced from: void androidx.compose.ui.test.Z.a())",
            "Missing field int androidx.lifecycle.LifecycleRegistry.Companion (referenced from: z)",
            "Missing class javax.lang.model.SourceVersion (referenced from: w)",
            "Missing something we cannot parse",
            "Warning: an unrelated line",
        )
        val allowlist = listOf("java.beans.*", "javax.lang.model.element.Modifier", "androidx.test.internal.runner.hidden.ExposedInstrumentationApi")
        assertEquals(
            listOf(
                "Missing class javax.lang.model.SourceVersion",
                "Missing field int androidx.lifecycle.LifecycleRegistry.Companion",
                "Missing method void androidx.compose.ui.Modifier.newApi(int, java.lang.String)",
                "Missing something we cannot parse",
            ),
            TestApkKeepRules.unexpectedMissing(report, allowlist),
        )
        assertEquals(emptyList<String>(), TestApkKeepRules.unexpectedMissing(report.take(4), allowlist))
        assertEquals(listOf("Missing class java.beans.BeanInfo"), TestApkKeepRules.unexpectedMissing(report.take(1), listOf("java.*")))
    }

    // The T22 qa crash: the test APK calls the facade CollectionsKt.collectionSizeOrDefault, which the package-private
    // multifile part CollectionsKt__IterablesKt defines, so TraceReferences keeps the part. R8 moved its subtype
    // CollectionsKt__MutableCollectionsJVMKt to the root package: IllegalAccessError when the app starts.
    private val parts = mapOf(
        "kotlin/collections/CollectionsKt__IterablesKt" to ClassHeader("kotlin/collections/CollectionsKt__IterablesKt", "kotlin/collections/CollectionsKt__CollectionsKt", emptyList()),
        "kotlin/collections/CollectionsKt__MutableCollectionsJVMKt" to ClassHeader("kotlin/collections/CollectionsKt__MutableCollectionsJVMKt", "kotlin/collections/CollectionsKt__IterablesKt", emptyList()),
        "kotlin/collections/CollectionsKt" to ClassHeader("kotlin/collections/CollectionsKt", "kotlin/collections/CollectionsKt___CollectionsKt", emptyList(), isPublic = true),
        "a/b/Hidden\$Callback" to ClassHeader("a/b/Hidden\$Callback", "java/lang/Object", emptyList(), isInterface = true),
        "a/b/Visible" to ClassHeader("a/b/Visible", "java/lang/Object", emptyList(), isInterface = true, isPublic = true),
    )

    private val traced = """
        -keep class kotlin.collections.CollectionsKt {
        }
        -keep class kotlin.collections.CollectionsKt__IterablesKt {
          public static int collectionSizeOrDefault(java.lang.Iterable, int);
        }
        -keep interface a.b.Visible {
        }
        -keeppackagenames androidx.concurrent.futures
    """.trimIndent() + "\n"

    @Test
    fun obfuscates_unlessAConfigurationHoldsADontobfuscateOption() {
        assertTrue(TestApkKeepRules.obfuscates(emptyList()))
        assertTrue(TestApkKeepRules.obfuscates(listOf("-allowaccessmodification\n-keep class a.B { *; }\n")))
        assertTrue(TestApkKeepRules.obfuscates(listOf("# -dontobfuscate\n-keep class a.B # not -dontobfuscate\n", "-dontobfuscated\n")))
        assertFalse(TestApkKeepRules.obfuscates(listOf("-keep class a.B\n", "# Benchmark\n-dontobfuscate\n")))
        assertFalse(TestApkKeepRules.obfuscates(listOf("  -dontoptimize\t-dontobfuscate   # both\r\n")))
    }

    @Test
    fun obfuscates_failsOnAnIncludeItCannotFollow() {
        val failure = assertThrows(IllegalStateException::class.java) {
            TestApkKeepRules.obfuscates(listOf("-keep class a.B\n", "-include benchmark-extra.pro\n"))
        }
        assertTrue(failure.message!!.contains("-include"))
        assertTrue(TestApkKeepRules.obfuscates(listOf("# -include other.pro\n-keep class a.B\n")))
    }

    // qa has release's proguardFiles (AGP's default file and proguard-rules.pro); benchmark adds proguard-benchmark.pro.
    // The benchmark rules hash, which perf comparability depends on, holds only while benchmark is non-obfuscating.
    @Test
    fun obfuscates_theRealFiles_qaObfuscates_benchmarkDoesNot() {
        val appDir = File(checkNotNull(System.getProperty("doro.appDir")) { "doro.appDir is not set" })
        val rules = File(appDir, "proguard-rules.pro").readText()
        val benchmark = File(appDir, "proguard-benchmark.pro").readText()
        assertTrue(TestApkKeepRules.obfuscates(listOf(rules)))
        assertFalse(TestApkKeepRules.obfuscates(listOf(rules, benchmark)))
    }

    @Test
    fun unreadableOptions_areTheOptionLinesKeptTypesCannotRead() {
        val rules = traced.lines() + listOf(
            "-keep class a.b.Whole\$Nested { *; }", "-keep class a.b.NoSpace{", "-keep,allowshrinking class a.b.NotPinned",
            "-keep class * extends a.b.Wildcard", "-keepclassmembers class a.b.Members {", "  public static void m();", "}",
            "  -dontwarn a.b.**",
        )
        assertEquals(
            listOf(
                "-keep class a.b.NoSpace{", "-keep,allowshrinking class a.b.NotPinned", "-keep class * extends a.b.Wildcard",
                "-keepclassmembers class a.b.Members {", "  -dontwarn a.b.**",
            ),
            TestApkKeepRules.unreadableOptions(rules),
        )
    }

    // Without the check, a kept non-public type in an unreadable rule would silently get no rule of set 4.
    @Test
    fun render_failsOnAnOptionLineKeptTypesCannotRead_inEveryVariant() {
        val unreadable = traced + "-keep class kotlin.collections.CollectionsKt__MapsKt{\n}\n"
        for (obfuscates in listOf(true, false)) {
            val failure = assertThrows(IllegalStateException::class.java) {
                TestApkKeepRules.render(":app:generateQaTestApkKeepRules", 3, 7, unreadable, emptyList(), emptyList(), parts, obfuscates)
            }
            assertTrue(failure.message!!.contains("-keep class kotlin.collections.CollectionsKt__MapsKt{"))
        }
    }

    @Test
    fun ownConfigurations_leaveOutTheBuildDirectory() {
        val project = File("project").absoluteFile
        val build = File(project, "build")
        val own = File(project, "proguard-benchmark.pro")
        val rules = File(project, "proguard-rules.pro")
        val default = File(build, "intermediates/default_proguard_files/global/proguard-android-optimize.txt-9.5.1")
        assertEquals(listOf(rules, own), TestApkKeepRules.ownConfigurations(listOf(default, rules, own, rules), build))
    }

    @Test
    fun keptTypes_areTheTypesOfPlainKeepRules() {
        val rules = traced.lines() + listOf(
            "-keep enum a.b.Mode {", "-keep @interface a.b.Marker {", "-keep class a.b.Whole\$Nested { *; }",
            "-keep,allowshrinking class a.b.NotPinned", "-keep class * extends a.b.Wildcard", "-keepclassmembers class a.b.Members {",
            "  public static void m();", "}",
        )
        assertEquals(
            listOf(
                "a/b/Marker", "a/b/Mode", "a/b/Visible", "a/b/Whole\$Nested",
                "kotlin/collections/CollectionsKt", "kotlin/collections/CollectionsKt__IterablesKt",
            ),
            TestApkKeepRules.keptTypes(rules),
        )
    }

    @Test
    fun packageRules_keepTheSubtypesOfEveryNonPublicKeptAppType_byExtendsOrImplements() {
        assertEquals(
            listOf(
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * implements a.b.Hidden\$Callback",
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * extends kotlin.collections.CollectionsKt__IterablesKt",
            ),
            TestApkKeepRules.packageRules(
                listOf("kotlin/collections/CollectionsKt__IterablesKt", "kotlin/collections/CollectionsKt", "a/b/Visible", "a/b/Hidden\$Callback", "a/b/NotInTheApp", "kotlin/collections/CollectionsKt__IterablesKt"),
                parts,
            ),
        )
    }

    @Test
    fun render_aNonObfuscatingVariant_getsRuleSetsOneToThreeOnly() {
        assertEquals(
            """
            # doro.test-apk-keep-rules: generated by :app:generateBenchmarkTestApkKeepRules (DEC-103 (5)); never edit. Test APK: 3 inputs, app: 7 inputs.
            # 1. R8 TraceReferences: every class, field and method of the app that the test APK references.
            -keep class kotlin.collections.CollectionsKt {
            }
            -keep class kotlin.collections.CollectionsKt__IterablesKt {
              public static int collectionSizeOrDefault(java.lang.Iterable, int);
            }
            -keep interface a.b.Visible {
            }
            -keeppackagenames androidx.concurrent.futures

            # 2. The 1 app types that test-APK classes or lambdas extend or implement, with their app supertypes, kept whole.
            -keep class a.b.Hidden${'$'}Callback { *; }
            # 3. The 0 app types the test APK provides META-INF/services implementations of, pinned: R8 then keeps
            #    the app's ServiceLoader.load of them and their names (kotlinx-coroutines-test's providers, which runTest needs).

            """.trimIndent(),
            TestApkKeepRules.render(":app:generateBenchmarkTestApkKeepRules", 3, 7, traced.replace("\n", "\r\n"), listOf("a/b/Hidden\$Callback"), emptyList(), parts, obfuscates = false),
        )
    }

    @Test
    fun render_anObfuscatingVariant_addsRuleSetFour_forTheNonPublicTypesOfEveryRuleSet() {
        val rendered = TestApkKeepRules.render(":app:generateQaTestApkKeepRules", 3, 7, traced, listOf("a/b/Hidden\$Callback"), emptyList(), parts, obfuscates = true)
        val withoutFour = TestApkKeepRules.render(":app:generateQaTestApkKeepRules", 3, 7, traced, listOf("a/b/Hidden\$Callback"), emptyList(), parts, obfuscates = false)
        assertEquals(
            withoutFour +
                "# 4. The 2 non-public app types that rule sets 1 to 3 keep: R8 keeps their access, so every app subtype\n" +
                "#    keeps its name and package, or R8 may move it away from them (IllegalAccessError). Obfuscating variants only.\n" +
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * implements a.b.Hidden\$Callback\n" +
                "-keep,allowshrinking,allowoptimization,allowaccessmodification class * extends kotlin.collections.CollectionsKt__IterablesKt\n",
            rendered,
        )
    }

    @Test
    fun wholeTypeRule_usesSourceNames_withNestedClassesByDollar() {
        assertEquals("-keep class kotlin.coroutines.CoroutineContext\$Element { *; }", TestApkKeepRules.wholeTypeRule("kotlin/coroutines/CoroutineContext\$Element"))
    }
}

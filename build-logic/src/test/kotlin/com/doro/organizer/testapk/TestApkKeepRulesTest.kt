package com.doro.organizer.testapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun wholeTypeRule_usesSourceNames_withNestedClassesByDollar() {
        assertEquals("-keep class kotlin.coroutines.CoroutineContext\$Element { *; }", TestApkKeepRules.wholeTypeRule("kotlin/coroutines/CoroutineContext\$Element"))
    }
}

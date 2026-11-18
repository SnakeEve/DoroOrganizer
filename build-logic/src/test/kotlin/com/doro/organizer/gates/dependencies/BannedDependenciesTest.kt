package com.doro.organizer.gates.dependencies

import com.doro.organizer.gates.ConfigFiles
import com.doro.organizer.gates.DocFiles
import com.doro.organizer.gates.configEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BannedDependenciesTest {
    private val rules = BannedDependencies(listOf("com.google.firebase", "androidx.sqlite:sqlite-bundled", "io.ktor"))

    @Test
    fun groupMatchesExactlyOrAsPrefixWithDot() {
        assertEquals("com.google.firebase", rules.banningEntry("com.google.firebase:firebase-analytics"))
        assertEquals("com.google.firebase", rules.banningEntry("com.google.firebase.crashlytics:core"))
        assertNull(rules.banningEntry("com.google.firebasex:lib"))
        assertNull(rules.banningEntry("com.google:firebase"))
    }

    @Test
    fun groupArtifactEntryMatchesOnlyThatArtifact() {
        assertEquals("androidx.sqlite:sqlite-bundled", rules.banningEntry("androidx.sqlite:sqlite-bundled"))
        assertNull(rules.banningEntry("androidx.sqlite:sqlite"))
        assertNull(rules.banningEntry("androidx.sqlite:sqlite-bundled-jvm"))
    }

    @Test
    fun violationsNameTheModuleAndTheConfiguration() {
        val found = rules.violations(
            mapOf(
                "releaseRuntimeClasspath" to ModuleGraph(listOf("androidx.core:core", "io.ktor:ktor-client-core"), emptyList()),
                "debugAndroidTestRuntimeClasspath" to ModuleGraph(listOf("androidx.sqlite:sqlite-bundled"), emptyList()),
            ),
        )
        assertEquals(
            listOf(
                "androidx.sqlite:sqlite-bundled in debugAndroidTestRuntimeClasspath (banned by 'androidx.sqlite:sqlite-bundled')",
                "io.ktor:ktor-client-core in releaseRuntimeClasspath (banned by 'io.ktor')",
            ),
            found,
        )
    }

    @Test
    fun unresolvedModules_failAndAreStillMatchedAgainstTheList() {
        val found = rules.violations(
            mapOf("qaRuntimeClasspath" to ModuleGraph(listOf("androidx.core:core"), listOf("io.ktor:ktor-client-core", "androidx.core:core-ktx"))),
        )
        assertEquals(
            listOf(
                "io.ktor:ktor-client-core in qaRuntimeClasspath (banned by 'io.ktor')",
                "io.ktor:ktor-client-core in qaRuntimeClasspath could not be resolved",
                "androidx.core:core-ktx in qaRuntimeClasspath could not be resolved",
            ),
            found,
        )
    }

    @Test
    fun committedList_isExactlyTheDocumentedEntries() {
        // testing.md § 9: the security.md § 9.3 list copied verbatim, plus the architecture and test bans.
        val security = coordinates(bullet("security.md", "- Banned dependencies,"))
        val architecture = coordinates(bullet("testing.md", "- Architecture: `com.google.dagger`"))
        val tests = coordinates(bullet("testing.md", "- Tests (DEC-12):"))
        assertEquals(listOf(29, 6, 3), listOf(security.size, architecture.size, tests.size))
        assertEquals(
            (security + architecture + tests).sorted(),
            configEntries(ConfigFiles.read("banned-dependencies.txt")).sorted(),
        )
    }

    @Test
    fun committedList_bansTheDocumentedModulesAndAllowsThePinnedStack() {
        val committed = BannedDependencies.parse(ConfigFiles.read("banned-dependencies.txt"))
        listOf(
            "com.google.firebase:firebase-analytics",
            "com.google.android.gms:play-services-base",
            "com.squareup.okhttp3:okhttp",
            "com.jakewharton.timber:timber",
            "androidx.security:security-crypto",
            "net.zetetic:android-database-sqlcipher",
            "com.google.dagger:hilt-android",
            "io.insert-koin:koin-android",
            "androidx.room3:room3-runtime",
            "androidx.sqlite:sqlite-bundled",
            "androidx.navigation:navigation-compose",
            "androidx.paging:paging-runtime",
            "org.mockito:mockito-core",
            "io.mockk:mockk",
            "org.robolectric:robolectric",
        ).forEach { assertNotNull(it, committed.banningEntry(it)) }
        listOf(
            "androidx.room:room-runtime",
            "androidx.sqlite:sqlite",
            "net.zetetic:sqlcipher-android",
            "androidx.navigation3:navigation3-ui",
            "androidx.biometric:biometric",
            "androidx.security:security-identity-credential",
        ).forEach { assertNull(it, committed.banningEntry(it)) }
    }

    private fun bullet(doc: String, prefix: String): String = DocFiles.read(doc).lineSequence().single { it.startsWith(prefix) }

    private fun coordinates(text: String): List<String> =
        Regex("`([^`]+)`").findAll(text).map { it.groupValues[1] }.filter(MAVEN_COORDINATE::matches).toList()

    private companion object {
        val MAVEN_COORDINATE = Regex("""[a-z][\w-]*(\.[\w-]+)+(:[\w.-]+)?""")
    }
}

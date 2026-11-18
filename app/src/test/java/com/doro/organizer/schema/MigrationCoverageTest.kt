package com.doro.organizer.schema

import com.doro.organizer.architecture.KotlinSource
import com.doro.organizer.architecture.SourceTree
import org.junit.Assert.assertEquals
import org.junit.Test

/** testing.md I-48: a schema version N ≥ 2 without a `migrate<N-1>To<N>` test fails the build (DEC-88). */
class MigrationCoverageTest {
    @Test
    fun everySchemaVersion_hasMigrationTest() {
        val tests = MigrationCoverage.sources(SourceTree.srcRoot.resolve("androidTest"))
        val missing = MigrationCoverage.missing(SchemaFiles.versions().keys, tests)
        assertEquals("schema versions without a migrate<N-1>To<N> test in src/androidTest", emptyList<Int>(), missing)
    }

    @Test
    fun missingStep_isReported() {
        val tests = listOf(
            test(
                """
                class MigrationTest {
                    @Test
                    fun migrate1To2() = Unit
                    // @Test fun migrate2To3() = Unit
                    fun migrate3To4() = Unit
                    @Test @SdkSuppress(minSdkVersion = 30)
                    fun migrate4To5() = Unit
                }
                """,
            ),
        )
        assertEquals(listOf(3, 4), MigrationCoverage.missing(setOf(1, 2, 3, 4, 5), tests))
    }

    @Test
    fun firstVersion_needsNoMigrationTest() {
        assertEquals(emptyList<Int>(), MigrationCoverage.missing(setOf(1), emptyList()))
    }

    private fun test(text: String) = KotlinSource("MigrationTest.kt", "androidTest", text.trimIndent())
}

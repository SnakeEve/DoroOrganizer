package com.doro.organizer.schema

import com.doro.organizer.architecture.KotlinSource
import java.io.File

/**
 * testing.md I-48 (DEC-88): every exported schema version N ≥ 2 has an instrumented `@Test` method named
 * `migrate<N-1>To<N>`. Commented-out methods do not count.
 */
object MigrationCoverage {
    private val MIGRATION_TEST = Regex("""@Test\s+(?:@[\w.]+(?:\([^)]*\))?\s+)*fun\s+migrate(\d+)To(\d+)\s*\(""")

    /** The schema versions that lack their migration test, ascending. */
    fun missing(schemaVersions: Set<Int>, testSources: List<KotlinSource>): List<Int> {
        val tested = testSources.flatMap { source ->
            MIGRATION_TEST.findAll(source.code).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        }.toSet()
        return schemaVersions.filter { it >= 2 && (it - 1 to it) !in tested }.sorted()
    }

    /** Every Kotlin file under [dir], as the scanner reads it. */
    fun sources(dir: File): List<KotlinSource> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { KotlinSource(it.relativeTo(dir).invariantSeparatorsPath, "androidTest", it.readText()) }
            .toList()
}

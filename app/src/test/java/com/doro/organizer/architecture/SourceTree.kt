package com.doro.organizer.architecture

import java.io.File

/** The app's production sources: `src/{main,debug,release}/{java,kotlin}` (qa and benchmark reuse `src/release`). */
object SourceTree {
    /** `app/src`, passed by `app/build.gradle.kts`. */
    val srcRoot: File =
        File(checkNotNull(System.getProperty("doro.srcRoot")) { "doro.srcRoot is not set; run through Gradle" })

    fun production(): List<KotlinSource> = listOf("main", "debug", "release").flatMap { sourceSet ->
        listOf("java", "kotlin")
            .map { srcRoot.resolve("$sourceSet/$it") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { file ->
                KotlinSource("src/" + file.relativeTo(srcRoot).invariantSeparatorsPath, sourceSet, file.readText())
            }
    }
}

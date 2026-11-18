package com.doro.organizer.gates

import java.io.File

/** The repository's `docs/` directory, passed in by build-logic/build.gradle.kts; tests only read it. */
internal object DocFiles {
    private val directory: File = File(checkNotNull(System.getProperty("doro.docsDir")) { "doro.docsDir is not set" })

    fun read(name: String): String = File(directory, name).readText()
}

package com.doro.organizer.gates

import java.io.File

/** The repository's `config/` directory, passed in by build-logic/build.gradle.kts. */
internal object ConfigFiles {
    val directory: File = File(checkNotNull(System.getProperty("doro.configDir")) { "doro.configDir is not set" })

    fun read(name: String): String = File(directory, name).readText()
}

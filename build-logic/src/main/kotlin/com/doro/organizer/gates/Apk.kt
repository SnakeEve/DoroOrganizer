package com.doro.organizer.gates

import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.file.Directory
import java.io.File

/** The single APK that AGP wrote into [directory] (no splits, no flavors). */
internal fun singleApk(loader: BuiltArtifactsLoader, directory: Directory): File {
    val artifacts = checkNotNull(loader.load(directory)) { "No APK metadata in ${directory.asFile}" }
    return File(artifacts.elements.single().outputFile)
}

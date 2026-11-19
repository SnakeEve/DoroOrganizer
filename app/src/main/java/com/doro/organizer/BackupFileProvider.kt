package com.doro.organizer

import androidx.core.content.FileProvider

/**
 * Serves share-sheet backups from `cacheDir/share/` only (`res/xml/file_paths.xml`); never exported, read grants only
 * (security.md § 9.1, DEC-68). Empty on purpose: a subclass keeps its manifest entry apart from any library's.
 */
class BackupFileProvider : FileProvider()

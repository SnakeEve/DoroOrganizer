package com.doro.organizer.data

import java.io.File

/**
 * The database file locations (db-lifecycle.md § Files on disk), used only by DatabaseHolder and DbMaintenance.
 * The caller chooses [databasesDir], so tests point it at a temporary directory.
 */
class DbFiles(val databasesDir: File) {
    /** The main file of generation [generation]. */
    fun live(generation: Long) = File(databasesDir, "doro-$generation.db")
}

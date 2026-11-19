package com.doro.organizer.data

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The database file locations (db-lifecycle.md § Files on disk), used only by DatabaseHolder and DbMaintenance. The
 * caller chooses the roots ([databasesDir], [noBackupDir] for `noBackupFilesDir`, `cacheDir`), so tests point every
 * one of them at a temporary directory. Lifecycle code never touches a name in [databasesDir] that is not a
 * generation file.
 */
class DbFiles(val databasesDir: File, val noBackupDir: File, cacheDir: File) {
    /** Generations under construction and the verified backup export. */
    val stagingDir = File(noBackupDir, "staging")

    /** The private copy of a restore input and its reference database. */
    val restoreDir = File(noBackupDir, "restore")

    /** Share-sheet copies of verified backups, served by `BackupFileProvider`. */
    val shareDir = File(cacheDir, "share")

    /** The data-reset marker of a Restore or Start fresh commit (db-lifecycle.md § Data-reset marker). */
    val resetMarker = File(noBackupDir, "data-reset.pending")

    /** The main file of generation [generation]. */
    fun live(generation: Long) = File(databasesDir, "doro-$generation.db")

    fun ensureDirs() {
        for (dir in listOf(databasesDir, stagingDir, restoreDir, shareDir)) check(dir.isDirectory || dir.mkdirs()) { "mkdirs" }
    }

    /** Generation number -> the existing files of that set (main file and sidecars, with or without the main file). */
    fun generationsOnDisk(): Map<Long, Set<File>> = generationFiles(databasesDir)
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }

    /** Every generation number named by a file in [databasesDir] or [stagingDir]: the floor of the next generation. */
    fun generationNumbersInUse(): Set<Long> =
        (generationFiles(databasesDir) + generationFiles(stagingDir)).mapTo(mutableSetOf()) { it.first }

    fun hasMainFile(generation: Long): Boolean = live(generation).isFile

    /**
     * Deletes the generation file set of [generation] with SQLCipher's `deleteDatabase` (main file, `-journal`, `-shm`,
     * `-wal`, `-mj*`). True only when none of the main, `-wal`, `-shm` and `-journal` files exists afterwards. Never
     * called for the committed generation or one with an open connection.
     */
    fun deleteGeneration(generation: Long): Boolean {
        val main = live(generation)
        SQLiteDatabase.deleteDatabase(main)
        return SIDECARS.none { File(main.path + it).exists() }
    }

    /**
     * Empties [dir], one of [stagingDir], [restoreDir] or [shareDir]; false when a child is still there. A missing
     * directory is empty.
     */
    fun clearDir(dir: File): Boolean {
        require(dir == stagingDir || dir == restoreDir || dir == shareDir) { "not a temporary directory" }
        dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return dir.listFiles().isNullOrEmpty()
    }

    /**
     * Erase, `ERASE_FILES` (db-lifecycle.md § Erase): every generation file set, the [committed] one last, then the
     * contents of [stagingDir], [restoreDir] and [shareDir], then the data-reset marker with its `AtomicFile`
     * siblings. Best effort: whatever remains is what an interrupted Erase leaves, and startup decides from it
     * (crash-safety rows CS-25, CS-26).
     */
    fun deleteEverything(committed: Long?) {
        erasureOrder(generationsOnDisk().keys, committed).forEach { deleteGeneration(it) }
        listOf(stagingDir, restoreDir, shareDir).forEach { clearDir(it) }
        AtomicFile(resetMarker).delete()
    }

    companion object {
        /**
         * The production roots: the directories `getDatabasePath`, `noBackupFilesDir` and `cacheDir` name, derived from
         * `applicationInfo.dataDir` because those getters create the directory on the calling thread, while
         * construction does no I/O (architecture.md § Dependency injection). [ensureDirs] creates them in E1;
         * `cache/` exists from installation. `noBackupDir` is the directory `DefaultKeyVault.create` keeps
         * `db-state.json` in, because every commit syncs the key-state's directory through it.
         */
        fun forApp(context: Context): DbFiles {
            val dataDir = File(context.applicationInfo.dataDir)
            return DbFiles(File(dataDir, "databases"), File(dataDir, "no_backup"), File(dataDir, "cache"))
        }

        /** The main file and the sidecars whose absence makes a generation deleted. */
        private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

        /** (number, file) of every generation file name in [dir]. */
        private fun generationFiles(dir: File): List<Pair<Long, File>> =
            dir.listFiles().orEmpty().mapNotNull { file -> generationOf(file.name)?.let { it to file } }
    }

    override fun toString() = "DbFiles" // no paths in logs (DEC-70)
}

/** The order in which Erase deletes the generation sets [onDisk]: [committed], when present, last (§ Erase step 5). */
internal fun erasureOrder(onDisk: Set<Long>, committed: Long?): List<Long> =
    onDisk.filter { it != committed }.sorted() + onDisk.filter { it == committed }

package com.doro.organizer.data.maintenance

import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.column0
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.longOf
import java.io.Closeable
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The dedicated SQLCipher connection of every export (DEC-82, db-lifecycle.md § Maintenance connection); its main
 * database is the export source. Never ATTACH through Room. The only values interpolated into SQL text are integers
 * computed here and table names read from the source's own schema; user input never reaches these statements.
 */
internal class MaintenanceConnection private constructor(private val db: SQLiteDatabase) : Closeable {
    fun quickCheckOk(): Boolean = db.column0("PRAGMA quick_check") == listOf("ok")

    /** EXPORT into [target], which must not exist yet, keyed with [targetKey] (empty: plaintext). */
    fun exportTo(target: File, targetKey: ByteArray) {
        // The connection opens without CREATE; SQLite opens a 0-byte file as an empty database.
        check(target.createNewFile()) { "export target exists" }
        // The key is always bound: a missing or NULL key would silently reuse the source key (DEC-82).
        db.execSQL("ATTACH DATABASE ? AS target KEY ?", arrayOf<Any>(target.path, targetKey))
        try {
            db.query("SELECT sqlcipher_export('target')").use { check(it.moveToFirst()) { "export" } } // never rawExecSQL
            val version = db.longOf("PRAGMA main.user_version")
            db.execSQL("PRAGMA target.user_version = $version") // export copies neither pragma
            db.execSQL("PRAGMA target.application_id = ${AppDatabase.APPLICATION_ID}")
            checkCopied()
        } finally {
            runCatching { db.execSQL("DETACH DATABASE target") } // close() releases it anyway; keep the first failure
        }
    }

    /** The same row count in every table, and the same `sqlite_sequence`, in main and target. */
    private fun checkCopied() {
        val tables = db.column0(
            "SELECT name FROM main.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'",
        )
        for (name in tables) {
            val quoted = "\"" + checkNotNull(name).replace("\"", "\"\"") + "\""
            check(db.longOf("SELECT count(*) FROM main.$quoted") == db.longOf("SELECT count(*) FROM target.$quoted")) {
                "row count"
            }
        }
        check(
            db.column0("SELECT name || ':' || seq FROM main.sqlite_sequence ORDER BY name") ==
                db.column0("SELECT name || ':' || seq FROM target.sqlite_sequence ORDER BY name"),
        ) { "sqlite_sequence" }
    }

    override fun close() = db.close()

    companion object {
        /** Main database = export source: the live file with its key. */
        fun open(source: File, sourceKey: ByteArray) = MaintenanceConnection(RawConnections.open(source, sourceKey))
    }
}

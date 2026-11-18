package com.doro.organizer.data

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Process-wide SQLCipher setup (DEC-08, security.md § 3.4). Idempotent: the startup effect calls it first,
 * and every opener of a SQLCipher connection calls it defensively. Nothing calls it from Application.onCreate.
 */
internal object SqlcipherEngine {
    private var initialized = false

    @Synchronized
    fun init() {
        if (initialized) return
        System.loadLibrary("sqlcipher")
        SqlcipherLogging.configure() // before any connection exists (DEC-70)
        // Pins the SQLCipher 4 format for every later keyed connection, so a library upgrade cannot change
        // the on-disk format of live databases or backups. The in-memory connection only carries the pragma.
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", ByteArray(0), null, null)
        try {
            db.query("PRAGMA cipher_default_compatibility = 4").use { it.moveToFirst() }
        } finally {
            db.close()
        }
        initialized = true
    }
}

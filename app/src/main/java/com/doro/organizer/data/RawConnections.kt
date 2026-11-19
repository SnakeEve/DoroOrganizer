package com.doro.organizer.data

import android.database.sqlite.SQLiteException
import com.doro.organizer.data.db.AppDatabase
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/** SQLCipher connections outside Room (db-lifecycle.md § Open sequence). */
internal object RawConnections {
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) // 16 bytes

    /**
     * Strict: only a file shorter than 16 bytes counts as "not plaintext"; every other IOException propagates
     * (Transient), so a read error on a plaintext file never becomes the Corrupt screen. Used by the open sequence.
     */
    fun readsAsPlaintext(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        try {
            ByteArray(MAGIC.size).also { f.readFully(it) }.contentEquals(MAGIC)
        } catch (e: EOFException) {
            false
        }
    }

    /**
     * Conservative, for adoption at startup and the export target check: true only when the first 16 bytes are the
     * plaintext magic. A short or unreadable file is not plaintext (the safe side: it is never adopted).
     */
    fun isPlaintext(file: File): Boolean = try {
        readsAsPlaintext(file)
    } catch (e: IOException) {
        false
    }

    /**
     * Opens the existing [file] read-write. Never creates it, never enables WAL, never deletes on corruption and never
     * creates `android_metadata` (`NO_LOCALIZED_COLLATORS`). An empty [key] is plain mode (DEC-08).
     */
    fun open(file: File, key: ByteArray): SQLiteDatabase {
        SqlcipherEngine.init()
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        val hook = if (key.isEmpty()) null else CipherCompatibilityHook
        return SQLiteDatabase.openDatabase(file.path, key, null, flags, NoDeleteErrorHandler, hook)
    }
}

/** States the intent of DEC-85: a corrupt file is never deleted by the engine. */
object NoDeleteErrorHandler : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase, exception: SQLiteException) = Unit
}

internal fun SQLiteDatabase.longOf(sql: String): Long = query(sql).use { c ->
    check(c.moveToFirst()) { "no row" }
    c.getLong(0)
}

internal fun SQLiteDatabase.column0(sql: String): List<String?> =
    query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

/** A failed open check with its class; the message is constant (DEC-70). */
internal class CheckFailed(val failure: DbFailure, val keyRejected: Boolean = false) : Exception("open check failed")

/**
 * Steps 2–4 of the open sequence on the live [file] with [key]: presence, header against the key, then raw
 * verification on a dedicated connection. Throws [CheckFailed] for every Corrupt and NewerVersion outcome; other
 * exceptions propagate for [openFailureKind].
 */
internal fun verifyLive(file: File, key: ByteArray) {
    // Neither SQLite nor Room may ever create the live file (step 2).
    if (!file.isFile || file.length() == 0L) throw CheckFailed(DbFailure.Corrupt)
    // The committed key-state is authoritative, so a mismatch means this key does not fit the file (step 3).
    if (RawConnections.readsAsPlaintext(file) != key.isEmpty()) {
        throw CheckFailed(DbFailure.Corrupt, keyRejected = true)
    }
    var keyCheck = true
    try {
        RawConnections.open(file, key).use { db ->
            // sqlcipher-android reads the schema itself only for a non-empty key, so this is the first read in plain
            // mode (step 4.1).
            val objects = db.longOf("SELECT count(*) FROM sqlite_master WHERE name <> 'android_metadata'")
            keyCheck = false
            verifyOpened(db, objects)
        }
    } catch (e: SQLiteException) {
        throw rawCheckFailure(e, keyCheck)
    }
}

/**
 * The Opening-table row of an exception from raw verification. [keyCheck]: it came from opening the connection or
 * from its first read (step 4.1), where `SQLITE_NOTADB` means the key does not fit the file. Later it is plain
 * corruption, which [openFailureKind] maps by class. The checks are fixed statements, so a plain `SQLITE_ERROR` can
 * only come from the file.
 */
internal fun rawCheckFailure(e: SQLiteException, keyCheck: Boolean): Exception = when {
    e is SQLiteNotADatabaseException && keyCheck -> CheckFailed(DbFailure.Corrupt, keyRejected = true)
    e.javaClass == SQLiteException::class.java -> CheckFailed(DbFailure.Corrupt)
    else -> e
}

private fun verifyOpened(db: SQLiteDatabase, objects: Long) {
    if (objects == 0L) throw CheckFailed(DbFailure.Corrupt)
    val version = db.longOf("PRAGMA user_version")
    if (version > AppDatabase.VERSION) throw CheckFailed(DbFailure.NewerVersion)
    if (version < 1) throw CheckFailed(DbFailure.Corrupt)
    if (db.longOf("PRAGMA application_id") != AppDatabase.APPLICATION_ID.toLong()) throw CheckFailed(DbFailure.Corrupt)
    if (db.column0("PRAGMA quick_check") != listOf("ok")) throw CheckFailed(DbFailure.Corrupt)
}

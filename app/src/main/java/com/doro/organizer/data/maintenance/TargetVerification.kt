package com.doro.organizer.data.maintenance

import android.content.Context
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.buildRoom
import com.doro.organizer.data.column0
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.longOf
import java.io.File

/**
 * VERIFY_TARGET (DEC-82, db-lifecycle.md § Target verification), on a fresh connection after the export connection
 * closed and before anything can commit. A failed check is a programming or hardware fault, never the user's data.
 * On return no sidecar is left next to [file], which is the commit's precondition.
 */
internal fun verifyTarget(file: File, key: ByteArray) {
    // Catches an export keyed with the wrong (source) key.
    check(RawConnections.isPlaintext(file) == key.isEmpty()) { "target header" }
    removeEmptySidecars(file) // a zero-length -journal or -wal is never hot; journal mode TRUNCATE leaves one
    RawConnections.open(file, key).use { db ->
        check(db.column0("PRAGMA integrity_check") == listOf("ok")) { "integrity_check" }
        check(db.query("PRAGMA foreign_key_check").use { it.count == 0 }) { "foreign_key_check" }
        if (key.isNotEmpty()) check(db.query("PRAGMA cipher_integrity_check").use { it.count == 0 }) { "cipher_integrity" }
        check(db.longOf("PRAGMA user_version") == AppDatabase.VERSION.toLong()) { "user_version" }
        check(db.longOf("PRAGMA application_id") == AppDatabase.APPLICATION_ID.toLong()) { "application_id" }
    }
    removeEmptySidecars(file) // the verification connection may leave one too
}

/** Fails when a sidecar holds data (a hot journal, or a WAL with frames); deletes empty ones and any `-shm`. */
internal fun removeEmptySidecars(file: File) {
    for (suffix in listOf("-journal", "-wal", "-shm")) {
        val sidecar = File(file.path + suffix)
        if (!sidecar.exists()) continue
        if (suffix != "-shm") check(sidecar.length() == 0L) { "sidecar holds data" }
        check(sidecar.delete()) { "sidecar delete" }
    }
}

/**
 * CREATE_EMPTY: an empty plaintext database of this build at [file], which must not exist. Room's own `onCreate`
 * writes the schema, the identity hash, `application_id` and `user_version`, so it always matches the build.
 */
internal fun createEmptyDatabase(context: Context, file: File) {
    check(!file.exists()) { "empty database target exists" }
    val db = buildRoom(context, file, ByteArray(0), temporary = true)
    try {
        db.openHelper.writableDatabase
    } finally {
        db.close()
    }
}

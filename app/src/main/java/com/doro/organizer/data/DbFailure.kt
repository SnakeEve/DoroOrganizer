package com.doro.organizer.data

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * The failure classes DatabaseHolder and DbMaintenance decide themselves (db-lifecycle.md § Failure classification).
 * Everything is classified by exception class, never by message text; errno is a number.
 */
internal enum class DbFailure { Corrupt, NewerVersion, StorageFull, Transient, Programming }

internal fun Throwable.isStorageFull(): Boolean = this is SQLiteFullException ||
    generateSequence(this) { it.cause }.filterIsInstance<ErrnoException>()
        .any { it.errno == OsConstants.ENOSPC || it.errno == OsConstants.EDQUOT }

/** `SQLITE_CORRUPT`, or `SQLITE_NOTADB` (a wrong key, an encrypted file read as plaintext, a non-database file). */
internal fun Throwable.isCorruption(): Boolean =
    this is SQLiteNotADatabaseException || this is SQLiteDatabaseCorruptException

/**
 * The base mapping; the context functions below override it where their tables say so. Never classifies a
 * cancellation.
 */
internal fun classify(e: Throwable): DbFailure = when {
    e is CancellationException -> throw e
    e is CheckFailed -> e.failure
    e.isCorruption() -> DbFailure.Corrupt
    e.isStorageFull() -> DbFailure.StorageFull
    e is SQLiteDiskIOException || e is SQLiteCantOpenDatabaseException || e is SQLiteReadOnlyDatabaseException ||
        e is SQLiteAccessPermException || e is SQLiteDatabaseLockedException || e is SQLiteTableLockedException ||
        e is SQLiteOutOfMemoryException || e is IOException -> DbFailure.Transient
    else -> DbFailure.Programming
}

/**
 * Context Opening: the kind an open or reopen reports. NewerVersion (a downgrade) and storage full are Transient
 * (DEC-91); a Programming failure, such as Room's identity or migration `IllegalStateException`, is rethrown.
 */
internal fun openFailureKind(e: Throwable): OpenFailureKind = when (classify(e)) {
    DbFailure.Corrupt -> OpenFailureKind.Corrupt
    DbFailure.NewerVersion, DbFailure.StorageFull, DbFailure.Transient -> OpenFailureKind.Transient
    DbFailure.Programming -> throw e
}

/**
 * Context Runtime (`withDb`, `observe`). Narrower than the base mapping: the live file has a single owner, so locking
 * exceptions are bugs here, like constraint failures, `IOException` and every other unlisted class. A row of the
 * classification table overrides the base mapping for its context, so these classes also decide which flow errors
 * are retried, although the § Observation prose names the base mapping.
 */
internal fun runtimeFailure(e: Throwable): DbFailure = when {
    e is CancellationException -> throw e
    e.isCorruption() -> DbFailure.Corrupt
    e.isStorageFull() -> DbFailure.StorageFull
    e is SQLiteDiskIOException || e is SQLiteCantOpenDatabaseException || e is SQLiteReadOnlyDatabaseException ||
        e is SQLiteAccessPermException || e is SQLiteOutOfMemoryException -> DbFailure.Transient
    else -> DbFailure.Programming
}

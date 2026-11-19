package com.doro.organizer.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.doro.organizer.core.coroutines.runDetached
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope

/**
 * The frame of every public repository write (data-layer.md § Transactions and results): one transaction with one
 * `now`, taken inside it (DEC-20), detached in [applicationScope] so it finishes when its caller is cancelled
 * (architecture.md § Layering and data flow, rule 6). [block] uses only DAOs of the `db` it receives and internal
 * helpers: a nested public repository call would run outside the transaction and wait for the writer connection
 * this block holds.
 */
internal suspend fun <T> DatabaseHolder.writeTransaction(
    applicationScope: CoroutineScope,
    appTime: AppTime,
    block: suspend (db: AppDatabase, now: Long) -> T,
): AppResult<T> = applicationScope.runDetached {
    withDb { db -> db.transaction { block(db, appTime.nowMillis()) } }
}

/**
 * The one way the app opens a transaction: Room's `withTransaction`, except when SQLite rolled the transaction back by
 * itself. On `SQLITE_FULL`, `SQLITE_IOERR`, `SQLITE_BUSY` and `SQLITE_NOMEM` SQLite may roll the whole transaction back
 * (sqlite.org/lang_transaction.html, "Response To Errors Within A Transaction"); sqlcipher-android's `SQLiteSession`
 * then pops its transaction, runs `ROLLBACK`, which fails with "cannot rollback - no transaction is active"
 * (`SQLITE_ERROR`), and releases the connection. Room lets that failure replace the block's: a full disk would reach
 * `runClassified` as a Programming failure and crash the app instead of returning `Err(StorageFull)` with the
 * transaction rolled back (db-lifecycle.md § Classification table, Runtime). [transactionFailure] decides which
 * failure propagates. A block that succeeded keeps Room's behavior: a failing `COMMIT` propagates.
 */
internal suspend fun <T> AppDatabase.transaction(block: suspend () -> T): T {
    var blockFailure: Throwable? = null
    try {
        return withTransaction {
            try {
                block()
            } catch (t: Throwable) {
                blockFailure = t
                throw t
            }
        }
    } catch (t: Throwable) {
        val original = blockFailure
        // A coroutine stack-trace recovery copy of the block's own failure carries it as its cause.
        if (original == null || t === original || t.cause === original) throw t
        throw transactionFailure(original, t)
    }
}

/**
 * The failure that propagates when [block] failed and ending the transaction failed too ([end]). The block's
 * failure, with [end] suppressed, only in the case SQLite's automatic rollback explains: [block] is an
 * `SQLiteException` (only SQLite can roll back by itself) and [end] is exactly an `SQLiteException`, the class
 * sqlcipher-android throws for `SQLITE_ERROR`, which a `ROLLBACK` returns when no transaction is active. SQLite is then
 * in autocommit and nothing of the block remains. Any other end failure, such as an I/O error or a busy database, may
 * have left the transaction open on the connection, and every `Error` is the process's: they propagate, with [block]
 * suppressed.
 */
internal fun transactionFailure(block: Throwable, end: Throwable): Throwable {
    val autoRolledBack = block is SQLiteException && end.javaClass == SQLiteException::class.java
    return if (autoRolledBack) block.also { it.addSuppressed(end) } else end.also { it.addSuppressed(block) }
}

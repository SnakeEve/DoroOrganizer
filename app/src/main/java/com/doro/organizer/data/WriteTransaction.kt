package com.doro.organizer.data

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
    withDb { db -> db.withTransaction { block(db, appTime.nowMillis()) } }
}

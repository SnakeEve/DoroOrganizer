package com.doro.organizer.data

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The only owner of the live Room instance (DEC-81, db-lifecycle.md § DatabaseHolder). Repositories reach their DAOs
 * per call through [withDb] or per subscription through [observe], and never keep `db` or a DAO beyond the block.
 * This is the repositories' side of the interface; the lifecycle members of db-lifecycle.md § Interface join it with
 * their implementation.
 */
interface DatabaseHolder {
    /**
     * The flow [query] builds on the open database, subscribed again after every reopen (db-lifecycle.md
     * § Observation). It emits nothing while the database is not open and never a placeholder, an empty list or null
     * because of a close, so an existence probe may read null as "the row is gone".
     */
    fun <T> observe(query: (AppDatabase) -> Flow<T>): Flow<T>

    /**
     * Runs [block] on the open database (db-lifecycle.md § One-shot access: withDb). A closed or replaced database
     * and the expected SQLite failures come back as [AppResult.Err]; anything else propagates.
     */
    suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T>
}

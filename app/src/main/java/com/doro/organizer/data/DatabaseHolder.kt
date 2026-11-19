package com.doro.organizer.data

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase

/**
 * The only owner of the live Room instance (DEC-81, db-lifecycle.md § DatabaseHolder). Repositories reach their DAOs
 * per call through [withDb] and never keep `db` or a DAO beyond the block. This is the repositories' side of the
 * interface; the lifecycle members of db-lifecycle.md § Interface join it with their implementation.
 */
interface DatabaseHolder {
    /**
     * Runs [block] on the open database (db-lifecycle.md § One-shot access: withDb). A closed or replaced database
     * and the expected SQLite failures come back as [AppResult.Err]; anything else propagates.
     */
    suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T>
}

package com.doro.organizer.testing

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DefaultNoteRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope

/**
 * Temporary: a [DatabaseHolder] that is always open on [db], a [TestDatabases] instance. architecture.md
 * § Dependency injection says the holder is never faked; this stand-in exists only until the production
 * `DefaultDatabaseHolder` arrives with SPEC T9, which moves its callers to `TestDatabases.holder()` and deletes this
 * file. It maps no exception, so any database failure fails the test.
 */
class InterimDatabaseHolder(private val db: AppDatabase) : DatabaseHolder {
    override suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T> = AppResult.Ok(block(db))
}

/** The production [NoteRepository] on [db] with [time]; its detached writes run in [applicationScope]. */
fun noteRepository(db: AppDatabase, time: AppTime, applicationScope: ApplicationScopeRule): NoteRepository =
    DefaultNoteRepository(InterimDatabaseHolder(db), time, applicationScope.scope)

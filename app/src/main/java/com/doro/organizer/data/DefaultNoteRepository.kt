package com.doro.organizer.data

import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EpochDays
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Each write validates outside the transaction, so a rejected call writes nothing, then runs as one detached
 * transaction ([writeTransaction]). One-shot reads run in the caller's scope; flows go through
 * [DatabaseHolder.observe], so they survive a database swap (DEC-81). Stateless: the DAOs come from [databaseHolder]
 * per call.
 */
class DefaultNoteRepository(
    private val databaseHolder: DatabaseHolder,
    private val appTime: AppTime,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : NoteRepository {
    // distinctUntilChanged before the mapping: Room re-runs a query on every write to a table it reads, and an equal
    // result is neither parsed nor sorted again (data-layer.md § Reactivity).
    override fun observeRows(query: NoteQuery): Flow<List<NoteRow>> =
        databaseHolder.observe { db ->
            db.noteDao().observeRows(
                personId = query.personId,
                projectId = query.projectId,
                orphanOnly = query.orphanOnly,
                undatedOnly = query.undatedOnly,
                fromDay = query.fromDay,
                toDay = query.toDay,
                searchPattern = query.searchPattern,
            )
        }
            .distinctUntilChanged()
            .map { it.toNoteRows() }
            .flowOn(dispatchers.default)

    override fun observeMarkedDays(query: NoteQuery, monthFirstDay: Long, monthLastDay: Long): Flow<Set<Long>> =
        databaseHolder.observe { db ->
            db.noteDao().observeMarkedDays(
                fromDay = monthFirstDay,
                toDay = monthLastDay,
                personId = query.personId,
                projectId = query.projectId,
                searchPattern = query.searchPattern,
            )
        }
            .distinctUntilChanged()
            .map { it.toSet() }

    override suspend fun selectIds(query: NoteQuery): AppResult<List<Long>> = databaseHolder.withDb { db ->
        db.noteDao().selectIds(
            personId = query.personId,
            projectId = query.projectId,
            orphanOnly = query.orphanOnly,
            undatedOnly = query.undatedOnly,
            fromDay = query.fromDay,
            toDay = query.toDay,
            searchPattern = query.searchPattern,
        )
    }

    override suspend fun loadNote(id: Long): AppResult<NoteDetail?> = databaseHolder.withDb { db ->
        val notes = db.noteDao()
        db.transaction {
            notes.noteDetail(id)?.let { row ->
                val order = linkOrder()
                NoteDetail(
                    id = row.id,
                    content = row.content,
                    date = row.date,
                    checked = row.checked,
                    checkedAt = row.checkedAt,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    persons = notes.linkedPersons(id).sortedWith(order),
                    projects = notes.linkedProjects(id).sortedWith(order),
                )
            }
        }
    }

    override suspend fun hasAnyData(): AppResult<Boolean> = databaseHolder.withDb { it.noteDao().hasAnyData() }

    override suspend fun save(draft: NoteDraft): AppResult<SaveResult> {
        require(draft.content.length <= MAX_CONTENT_CHARS) { "content longer than $MAX_CONTENT_CHARS chars" }
        requireNoteDate(draft.date)
        newEntityError(draft.newPersons, draft.newProjects)?.let { return AppResult.Err(it) }
        return write { db, now -> saveNote(db, draft, now) }
    }

    override suspend fun setChecked(noteId: Long, checked: Boolean): AppResult<Boolean> = write { db, now ->
        val notes = db.noteDao()
        (if (checked) notes.check(noteId, now) else notes.uncheck(noteId, now)) == 1
    }

    override suspend fun associate(
        noteIds: Collection<Long>,
        personIds: Set<Long>,
        projectIds: Set<Long>,
        newPersons: List<NewEntity>,
        newProjects: List<NewEntity>,
    ): AppResult<BatchResult> {
        newEntityError(newPersons, newProjects)?.let { return AppResult.Err(it) }
        return write { db, now ->
            associateNotes(db, noteIds.toSet(), personIds, projectIds, newPersons, newProjects, now)
        }
    }

    override suspend fun setDate(noteIds: Collection<Long>, date: Long?): AppResult<BatchResult> {
        requireNoteDate(date)
        val selected = noteIds.toSet()
        return write { db, now ->
            val notes = db.noteDao()
            val changed = db.survivingNotes(selected).chunked(SQL_CHUNK).sumOf { notes.setDate(it, date, now) }
            BatchResult(selected.size, changed)
        }
    }

    override suspend fun deleteNotes(noteIds: Collection<Long>): AppResult<BatchResult> {
        val selected = noteIds.toSet()
        return write { db, _ ->
            val notes = db.noteDao()
            val deleted = db.survivingNotes(selected).chunked(SQL_CHUNK).sumOf { chunk ->
                notes.deletePersonLinksOfNotes(chunk)
                notes.deleteProjectLinksOfNotes(chunk)
                notes.deleteNotes(chunk)
            }
            BatchResult(selected.size, deleted)
        }
    }

    private suspend fun <T> write(block: suspend (db: AppDatabase, now: Long) -> T): AppResult<T> =
        databaseHolder.writeTransaction(applicationScope, appTime, block)

    /** INV-5: the date pickers' range, so a stored date never crashes the editor's picker (DEC-26). */
    private fun requireNoteDate(date: Long?) =
        require(date == null || date in EpochDays.MIN..EpochDays.MAX) { "date outside EpochDays" }
}

package com.doro.organizer.data

import androidx.room.withTransaction
import com.doro.organizer.core.coroutines.runDetached
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EpochDays
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.SaveResult
import kotlinx.coroutines.CoroutineScope

/**
 * Each write validates outside the transaction, so a rejected call writes nothing, then runs one transaction detached
 * in [applicationScope] (data-layer.md § Transactions and results, architecture.md § Layering and data flow, rule 6).
 * Reads run in the caller's scope. Stateless: the DAOs come from [databaseHolder] per call.
 */
class DefaultNoteRepository(
    private val databaseHolder: DatabaseHolder,
    private val appTime: AppTime,
    private val applicationScope: CoroutineScope,
) : NoteRepository {
    override suspend fun loadNote(id: Long): AppResult<NoteDetail?> = databaseHolder.withDb { db ->
        val notes = db.noteDao()
        db.withTransaction {
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

    /** One transaction with one `now`, taken inside it (DEC-20); only this method opens the transaction. */
    private suspend fun <T> write(block: suspend (db: AppDatabase, now: Long) -> T): AppResult<T> =
        applicationScope.runDetached {
            databaseHolder.withDb { db -> db.withTransaction { block(db, appTime.nowMillis()) } }
        }

    /** INV-5: the date pickers' range, so a stored date never crashes the editor's picker (DEC-26). */
    private fun requireNoteDate(date: Long?) =
        require(date == null || date in EpochDays.MIN..EpochDays.MAX) { "date outside EpochDays" }
}

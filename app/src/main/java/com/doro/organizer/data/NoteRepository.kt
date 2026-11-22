package com.doro.organizer.data

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.SaveResult
import kotlinx.coroutines.flow.Flow

/**
 * The editor's one-shot reads and every note and link write (data-layer.md § Editor loads, § Write rules). Each write
 * is one transaction with one `now` (DEC-20, DEC-36) that finishes even when its caller is cancelled. Vanished notes,
 * persons and projects are skipped, never errors (data-layer.md § Reactivity).
 */
interface NoteRepository {
    /**
     * The rows of [query] in the canonical order of DEC-23, with their links sorted like the pickers (DEC-32). Equal
     * results are not emitted again (data-layer.md § Reactivity).
     */
    fun observeRows(query: NoteQuery): Flow<List<NoteRow>>

    /**
     * The calendar marks of DEC-53: the distinct epoch days in [monthFirstDay]..[monthLastDay] that hold a note
     * matching the person, project and search of [query], checked notes included; the query's own date bounds are
     * replaced by the month's (data-layer.md § Perspective query). Equal results are not emitted again.
     */
    fun observeMarkedDays(query: NoteQuery, monthFirstDay: Long, monthLastDay: Long): Flow<Set<Long>>

    /** Select all (DEC-36): the ids of every row of [query], checked notes and off-screen rows included; unordered. */
    suspend fun selectIds(query: NoteQuery): AppResult<List<Long>>

    /** The note with its links sorted like row links, or `Ok(null)` when it is gone; writes nothing (DEC-21). */
    suspend fun loadNote(id: Long): AppResult<NoteDetail?>

    /** True when the database holds at least one note, person or project (DEC-73). */
    suspend fun hasAnyData(): AppResult<Boolean>

    /**
     * Inserts a new note or writes the changes of an existing one (data-layer.md § Note save). A blank new name is
     * `Err(InvalidInput(BlankName))`; content over `MAX_CONTENT_CHARS` or over `MAX_CONTENT_LINES` lines, a date
     * outside `EpochDays`, a new name over `MAX_NAME_CHARS` or a `NewEntity.key` used twice within one kind throws
     * `IllegalArgumentException`. Nothing is written in either case.
     */
    suspend fun save(draft: NoteDraft): AppResult<SaveResult>

    /**
     * Sets the note to [checked], never toggles (DEC-22): `Ok(true)` when the row changed, `Ok(false)` when it
     * already had that state or is gone. Check leaves `updated_at`; uncheck bumps it (DEC-21).
     */
    suspend fun setChecked(noteId: Long, checked: Boolean): AppResult<Boolean>

    /**
     * Links every surviving note of [noteIds] to the chosen and the newly created persons and projects, additively;
     * a note that gained a link is bumped (DEC-21, DEC-36). `changed` counts those notes.
     */
    suspend fun associate(
        noteIds: Collection<Long>,
        personIds: Set<Long>,
        projectIds: Set<Long>,
        newPersons: List<NewEntity>,
        newProjects: List<NewEntity>,
    ): AppResult<BatchResult>

    /** Sets or, with null, removes the date of [noteIds]; only rows whose date changes are written and bumped. */
    suspend fun setDate(noteIds: Collection<Long>, date: Long?): AppResult<BatchResult>

    /** Hard-deletes [noteIds] and their links (DEC-35, spec §1); `changed` counts the deleted notes. */
    suspend fun deleteNotes(noteIds: Collection<Long>): AppResult<BatchResult>
}

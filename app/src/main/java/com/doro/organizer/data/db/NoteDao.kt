package com.doro.organizer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * Notes, their links and the list queries (data-layer.md § DAO surface). Called only by `NoteRepository`, through
 * `DatabaseHolder`; never cached (DEC-81). No `@Transaction` methods: repositories open transactions.
 *
 * The list arguments are the fields of `NoteQuery`; `null` and `false` mean "no condition".
 */
@Dao
interface NoteDao {
    /** Every note list, in the canonical order of DEC-23. */
    @Query(ROWS_SQL)
    fun observeRows(
        personId: Long?,
        projectId: Long?,
        orphanOnly: Boolean,
        undatedOnly: Boolean,
        fromDay: Long?,
        toDay: Long?,
        searchPattern: String?,
    ): Flow<List<NoteRowRaw>>

    /** The ids of every row of [observeRows] with the same arguments, unordered (Select all, DEC-36). */
    @Query(IDS_SQL)
    suspend fun selectIds(
        personId: Long?,
        projectId: Long?,
        orphanOnly: Boolean,
        undatedOnly: Boolean,
        fromDay: Long?,
        toDay: Long?,
        searchPattern: String?,
    ): List<Long>

    /** The distinct epoch days in [fromDay]..[toDay] that hold a note matching the other filters (DEC-53). */
    @Query(MARKED_DAYS_SQL)
    fun observeMarkedDays(
        fromDay: Long,
        toDay: Long,
        personId: Long?,
        projectId: Long?,
        searchPattern: String?,
    ): Flow<List<Long>>

    /** The editor's note, or null when it is gone. */
    @Query(NOTE_DETAIL_SQL)
    suspend fun noteDetail(id: Long): NoteDetailRow?

    /** The persons linked to [noteId], unsorted. */
    @Query(LINKED_PERSONS_SQL)
    suspend fun linkedPersons(noteId: Long): List<EntityRef>

    /** The projects linked to [noteId], unsorted. */
    @Query(LINKED_PROJECTS_SQL)
    suspend fun linkedProjects(noteId: Long): List<EntityRef>

    /** True when the database holds at least one note, person or project (DEC-73). */
    @Query(HAS_ANY_DATA_SQL)
    suspend fun hasAnyData(): Boolean

    /**
     * A new note with its fold and its preview, which SQLite computes from [content] in the same statement (DEC-106,
     * INV-13); returns the id of the inserted row. [checkedAt] is [now] exactly when [checked] (DEC-22).
     */
    @Query(INSERT_NOTE_SQL)
    suspend fun insertNote(date: Long?, checked: Boolean, checkedAt: Long?, now: Long, content: String, fold: String): Long

    /** What a save compares, or null when the note is gone. */
    @Query(SAVE_PROBE_SQL)
    suspend fun saveProbe(id: Long): SaveProbe?

    @Query(PERSON_IDS_OF_SQL)
    suspend fun personIdsOf(noteId: Long): List<Long>

    @Query(PROJECT_IDS_OF_SQL)
    suspend fun projectIdsOf(noteId: Long): List<Long>

    @Query(UPDATE_NOTE_SQL)
    suspend fun updateNote(id: Long, content: String, fold: String, date: Long?, now: Long): Int

    /** Returns one rowid per link, aligned with [links]: `-1` for a link that already existed (DEC-36). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPersonLinks(links: List<NotePersonCrossRef>): List<Long>

    /** As [insertPersonLinks]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProjectLinks(links: List<NoteProjectCrossRef>): List<Long>

    @Query(UNLINK_PERSONS_SQL)
    suspend fun unlinkPersons(noteId: Long, personIds: List<Long>): Int

    @Query(UNLINK_PROJECTS_SQL)
    suspend fun unlinkProjects(noteId: Long, projectIds: List<Long>): Int

    /** 1 when the note was unchecked and is now checked, else 0 (DEC-22). */
    @Query(CHECK_SQL)
    suspend fun check(id: Long, now: Long): Int

    /** 1 when the note was checked and is now unchecked, else 0 (DEC-22). */
    @Query(UNCHECK_SQL)
    suspend fun uncheck(id: Long, now: Long): Int

    @Query(EXISTING_NOTE_IDS_SQL)
    suspend fun existingNoteIds(ids: List<Long>): List<Long>

    @Query(BUMP_SQL)
    suspend fun bump(ids: List<Long>, now: Long): Int

    @Query(SET_DATE_SQL)
    suspend fun setDate(ids: List<Long>, date: Long?, now: Long): Int

    @Query(DELETE_PERSON_LINKS_OF_NOTES_SQL)
    suspend fun deletePersonLinksOfNotes(ids: List<Long>): Int

    @Query(DELETE_PROJECT_LINKS_OF_NOTES_SQL)
    suspend fun deleteProjectLinksOfNotes(ids: List<Long>): Int

    @Query(DELETE_NOTES_SQL)
    suspend fun deleteNotes(ids: List<Long>): Int
}

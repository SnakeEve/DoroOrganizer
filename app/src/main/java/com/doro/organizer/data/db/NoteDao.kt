package com.doro.organizer.data.db

import androidx.room.Dao
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
}

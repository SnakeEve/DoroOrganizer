package com.doro.organizer.data.db

import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * The statements [PersonDao] and [ProjectDao] share in shape, so `EntityRepository` serves both kinds with one code
 * path. Each DAO supplies its own SQL; this interface carries no Room annotation.
 */
interface EntityDao {
    /** Every entity for a picker that forms no first group, unsorted, none `coLinked` (DEC-111). */
    fun observeOptions(): Flow<List<EntityOptionRow>>

    /**
     * Every entity for a picker, unsorted; `coLinked`: linked to a note, other than [exceptNoteId], that links any id
     * of [coLinkedTo], ids of the other kind (DEC-28, DEC-111). [coLinkedTo] holds 1 to `SQL_CHUNK` ids, never none:
     * callers go through `observeOptionRows`.
     */
    fun observeCoLinkedOptions(coLinkedTo: List<Long>, exceptNoteId: Long?): Flow<List<EntityOptionRow>>

    /** The entity [id], or null once it is gone: the existence probe of data-layer.md § Reactivity. */
    fun observe(id: Long): Flow<EntityRef?>

    /**
     * Every entity's id and name, unsorted: the id-to-name map of the note lists, which carry link ids only
     * (DEC-106, data-layer.md § Rows).
     */
    fun observeNames(): Flow<List<EntityRef>>

    /** The ids of [ids] that still exist. */
    suspend fun existingIds(ids: List<Long>): List<Long>

    /** 1 when the entity [id] exists, else 0. */
    suspend fun exists(id: Long): Int

    /** N of DEC-34: the notes linked to the entity. */
    suspend fun impactN(id: Long): Int

    /** M of DEC-34: the linked notes that are left without any person or project once the entity is gone. */
    suspend fun impactM(id: Long): Int

    /** Deletes the entity's junction rows; returns how many. */
    suspend fun deleteLinks(id: Long): Int

    /** Deletes the entity row; returns 1, or 0 when it was gone. */
    suspend fun delete(id: Long): Int
}

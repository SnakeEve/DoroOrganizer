package com.doro.organizer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * Persons (data-layer.md § DAO surface). Called only by the repositories, through `DatabaseHolder`; never cached
 * (DEC-81).
 */
@Dao
interface PersonDao : EntityDao {
    /** [coLinkedTo] is a project id or null ([PERSON_OPTIONS_SQL]). */
    @Query(PERSON_OPTIONS_SQL)
    override fun observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>

    @Query(PERSON_REF_SQL)
    override fun observe(id: Long): Flow<EntityRef?>

    /** A new person; returns its id. */
    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Query(EXISTING_PERSON_IDS_SQL)
    override suspend fun existingIds(ids: List<Long>): List<Long>

    @Query(PERSON_EXISTS_SQL)
    override suspend fun exists(id: Long): Int

    @Query(PERSON_IMPACT_N_SQL)
    override suspend fun impactN(id: Long): Int

    @Query(PERSON_IMPACT_M_SQL)
    override suspend fun impactM(id: Long): Int

    @Query(DELETE_PERSON_LINKS_SQL)
    override suspend fun deleteLinks(id: Long): Int

    @Query(DELETE_PERSON_SQL)
    override suspend fun delete(id: Long): Int
}

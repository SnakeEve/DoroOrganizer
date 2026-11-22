package com.doro.organizer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * Projects (data-layer.md § DAO surface). Called only by the repositories, through `DatabaseHolder`; never cached
 * (DEC-81).
 */
@Dao
interface ProjectDao : EntityDao {
    /** [coLinkedTo] is a person id or null ([PROJECT_OPTIONS_SQL]). */
    @Query(PROJECT_OPTIONS_SQL)
    override fun observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>

    @Query(PROJECT_REF_SQL)
    override fun observe(id: Long): Flow<EntityRef?>

    @Query(PROJECT_NAMES_SQL)
    override fun observeNames(): Flow<List<EntityRef>>

    /** A new project; returns its id. */
    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Query(EXISTING_PROJECT_IDS_SQL)
    override suspend fun existingIds(ids: List<Long>): List<Long>

    @Query(PROJECT_EXISTS_SQL)
    override suspend fun exists(id: Long): Int

    @Query(PROJECT_IMPACT_N_SQL)
    override suspend fun impactN(id: Long): Int

    @Query(PROJECT_IMPACT_M_SQL)
    override suspend fun impactM(id: Long): Int

    @Query(DELETE_PROJECT_LINKS_SQL)
    override suspend fun deleteLinks(id: Long): Int

    @Query(DELETE_PROJECT_SQL)
    override suspend fun delete(id: Long): Int
}

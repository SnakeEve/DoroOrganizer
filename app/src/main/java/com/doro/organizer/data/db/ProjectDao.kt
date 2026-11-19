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
interface ProjectDao {
    /** Every project for a picker; [coLinkedTo] is a person id or null ([PROJECT_OPTIONS_SQL]). */
    @Query(PROJECT_OPTIONS_SQL)
    fun observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>

    /** The project [id], or null once it is gone: the existence probe of data-layer.md § Reactivity. */
    @Query(PROJECT_REF_SQL)
    fun observe(id: Long): Flow<EntityRef?>

    /** A new project; returns its id. */
    @Insert
    suspend fun insert(project: ProjectEntity): Long

    /** The ids of [ids] that still exist. */
    @Query(EXISTING_PROJECT_IDS_SQL)
    suspend fun existingIds(ids: List<Long>): List<Long>
}

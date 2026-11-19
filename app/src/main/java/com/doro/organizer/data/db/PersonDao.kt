package com.doro.organizer.data.db

import androidx.room.Dao
import androidx.room.Query
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * Persons (data-layer.md § DAO surface). Called only by `EntityRepository`, through `DatabaseHolder`; never cached
 * (DEC-81).
 */
@Dao
interface PersonDao {
    /** Every person for a picker; [coLinkedTo] is a project id or null ([PERSON_OPTIONS_SQL]). */
    @Query(PERSON_OPTIONS_SQL)
    fun observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>

    /** The person [id], or null once it is gone: the existence probe of data-layer.md § Reactivity. */
    @Query(PERSON_REF_SQL)
    fun observe(id: Long): Flow<EntityRef?>
}

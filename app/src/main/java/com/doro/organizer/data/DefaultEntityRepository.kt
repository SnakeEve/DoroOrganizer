package com.doro.organizer.data

import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.EntityDeleteResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Renames and deletes run as one detached transaction each ([writeTransaction]); the impact read runs in the caller's
 * scope. Flows go through [DatabaseHolder.observe], so they survive a database swap (DEC-81), and an equal result is
 * neither sorted nor emitted again. Stateless: the DAOs come from [databaseHolder] per call.
 */
class DefaultEntityRepository(
    private val databaseHolder: DatabaseHolder,
    private val appTime: AppTime,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : EntityRepository {
    override fun observeOptions(
        kind: EntityKind,
        coLinkedTo: List<Long>,
        exceptNoteId: Long?,
    ): Flow<List<EntityOption>> =
        databaseHolder.observe { db -> db.entities(kind).observeOptionRows(coLinkedTo, exceptNoteId) }
            .distinctUntilChanged()
            .map { it.toEntityOptions() }
            .flowOn(dispatchers.default)

    override fun observe(kind: EntityKind, id: Long): Flow<EntityRef?> =
        databaseHolder.observe { db -> db.entities(kind).observe(id) }.distinctUntilChanged()

    override suspend fun personDeleteImpact(id: Long): AppResult<DeleteImpact> = deleteImpact(EntityKind.Person, id)

    override suspend fun projectDeleteImpact(id: Long): AppResult<DeleteImpact> = deleteImpact(EntityKind.Project, id)

    override suspend fun deletePerson(id: Long): AppResult<EntityDeleteResult> = delete(EntityKind.Person, id)

    override suspend fun deleteProject(id: Long): AppResult<EntityDeleteResult> = delete(EntityKind.Project, id)

    override suspend fun rename(kind: EntityKind, id: Long, name: String): AppResult<Boolean> {
        val stored = storedName(name) ?: return AppResult.Err(AppError.InvalidInput(InputRule.BlankName))
        return databaseHolder.writeTransaction(applicationScope, appTime) { db, now ->
            db.entities(kind).rename(id, stored, now) == 1
        }
    }

    private suspend fun deleteImpact(kind: EntityKind, id: Long): AppResult<DeleteImpact> =
        databaseHolder.withDb { db ->
            val entities = db.entities(kind)
            db.transaction { DeleteImpact(n = entities.impactN(id), m = entities.impactM(id)) }
        }

    // data-layer.md § Deletes, steps 1 to 4. The junction rows go first and explicitly: whether Room's invalidation
    // sees cascaded deletes is undocumented, and picker counts read only the junction tables.
    private suspend fun delete(kind: EntityKind, id: Long): AppResult<EntityDeleteResult> =
        databaseHolder.writeTransaction(applicationScope, appTime) { db, _ ->
            val entities = db.entities(kind)
            if (entities.exists(id) == 0) {
                EntityDeleteResult(deleted = false, n = 0, m = 0)
            } else {
                val impact = DeleteImpact(n = entities.impactN(id), m = entities.impactM(id))
                entities.deleteLinks(id)
                entities.delete(id)
                EntityDeleteResult(deleted = true, n = impact.n, m = impact.m)
            }
        }
}

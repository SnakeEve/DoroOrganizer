package com.doro.organizer.ui.notes

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.testing.unused
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The [NoteRepository] side of a list: [rows] and [marks] answer per query, and every query asked for is recorded, so a
 * test sees exactly what the ViewModel requested. A closed database is `rows = { emptyFlow() }`, as
 * `DatabaseHolder.observe` gives while not open. `selectIds` answers [selectIdsResult], or by default the ids of the
 * query's current [rows], after [selectIdsGate] (when set) completes. Batch actions are recorded in [batchCalls]; each
 * succeeds with every passed id changed, or fails with [batchError], after [batchGate] (when set) completes.
 */
class ScriptedNotes : NoteRepository by unused() {
    val rowQueries = mutableListOf<NoteQuery>()
    val markQueries = mutableListOf<NoteQuery>()
    var rows: (NoteQuery) -> Flow<List<NoteRow>> = { flowOf(emptyList()) }
    var marks: (query: NoteQuery, month: LongRange) -> Set<Long> = { _, _ -> emptySet() }
    var setCheckedResult: AppResult<Boolean> = AppResult.Ok(true)
    val selectIdsQueries = mutableListOf<NoteQuery>()
    var selectIdsResult: AppResult<List<Long>>? = null
    var selectIdsGate: CompletableDeferred<Unit>? = null
    val batchCalls = mutableListOf<BatchCall>()
    var batchError: AppError? = null
    var batchGate: CompletableDeferred<Unit>? = null

    override fun observeRows(query: NoteQuery): Flow<List<NoteRow>> {
        rowQueries += query
        return rows(query)
    }

    override fun observeMarkedDays(query: NoteQuery, monthFirstDay: Long, monthLastDay: Long): Flow<Set<Long>> {
        markQueries += query
        return flowOf(marks(query, monthFirstDay..monthLastDay))
    }

    override suspend fun setChecked(noteId: Long, checked: Boolean): AppResult<Boolean> = setCheckedResult

    override suspend fun selectIds(query: NoteQuery): AppResult<List<Long>> {
        selectIdsQueries += query
        selectIdsGate?.await()
        return selectIdsResult ?: AppResult.Ok(rows(query).first().map { it.id })
    }

    override suspend fun setDate(noteIds: Collection<Long>, date: Long?) = batch(BatchCall.SetDate(noteIds.toSet(), date))

    override suspend fun deleteNotes(noteIds: Collection<Long>) = batch(BatchCall.Delete(noteIds.toSet()))

    override suspend fun associate(
        noteIds: Collection<Long>,
        personIds: Set<Long>,
        projectIds: Set<Long>,
        newPersons: List<NewEntity>,
        newProjects: List<NewEntity>,
    ) = batch(BatchCall.Associate(noteIds.toSet(), personIds, projectIds, newPersons, newProjects))

    private suspend fun batch(call: BatchCall): AppResult<BatchResult> {
        batchCalls += call
        batchGate?.await()
        return batchError?.let { AppResult.Err(it) } ?: AppResult.Ok(BatchResult(call.noteIds.size, call.noteIds.size))
    }
}

/** One batch call of [ScriptedNotes], with the note ids it received. */
sealed interface BatchCall {
    val noteIds: Set<Long>

    data class SetDate(override val noteIds: Set<Long>, val date: Long?) : BatchCall

    data class Delete(override val noteIds: Set<Long>) : BatchCall

    data class Associate(
        override val noteIds: Set<Long>,
        val personIds: Set<Long>,
        val projectIds: Set<Long>,
        val newPersons: List<NewEntity>,
        val newProjects: List<NewEntity>,
    ) : BatchCall
}

/** The existence probes of [EntityRepository]: an entity exists from [put] until [delete]. */
class ScriptedEntities : EntityRepository by unused() {
    private val refs = mutableMapOf<Pair<EntityKind, Long>, MutableStateFlow<EntityRef?>>()

    fun put(kind: EntityKind, id: Long, name: String) {
        ref(kind, id).value = EntityRef(id, name)
    }

    fun delete(kind: EntityKind, id: Long) {
        ref(kind, id).value = null
    }

    override fun observe(kind: EntityKind, id: Long): Flow<EntityRef?> = ref(kind, id)

    private fun ref(kind: EntityKind, id: Long) = refs.getOrPut(kind to id) { MutableStateFlow(null) }
}

/** A list row of note [id], linked to [persons] and [projects]. */
fun noteRow(
    id: Long,
    date: Long? = null,
    checked: Boolean = false,
    persons: List<EntityRef> = emptyList(),
    projects: List<EntityRef> = emptyList(),
) = NoteRow(id, "note $id", date, checked, null, 0, 0, persons, projects)

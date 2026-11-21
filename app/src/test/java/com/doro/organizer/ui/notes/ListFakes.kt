package com.doro.organizer.ui.notes

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.testing.unused
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The [NoteRepository] side of a list: [rows] and [marks] answer per query, and every query asked for is recorded, so a
 * test sees exactly what the ViewModel requested. A closed database is `rows = { emptyFlow() }`, as
 * `DatabaseHolder.observe` gives while not open.
 */
class ScriptedNotes : NoteRepository by unused() {
    val rowQueries = mutableListOf<NoteQuery>()
    val markQueries = mutableListOf<NoteQuery>()
    var rows: (NoteQuery) -> Flow<List<NoteRow>> = { flowOf(emptyList()) }
    var marks: (query: NoteQuery, month: LongRange) -> Set<Long> = { _, _ -> emptySet() }
    var setCheckedResult: AppResult<Boolean> = AppResult.Ok(true)

    override fun observeRows(query: NoteQuery): Flow<List<NoteRow>> {
        rowQueries += query
        return rows(query)
    }

    override fun observeMarkedDays(query: NoteQuery, monthFirstDay: Long, monthLastDay: Long): Flow<Set<Long>> {
        markQueries += query
        return flowOf(marks(query, monthFirstDay..monthLastDay))
    }

    override suspend fun setChecked(noteId: Long, checked: Boolean): AppResult<Boolean> = setCheckedResult
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

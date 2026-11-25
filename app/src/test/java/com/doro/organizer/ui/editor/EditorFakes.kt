package com.doro.organizer.ui.editor

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.EntityDeleteResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.SaveResult
import com.doro.organizer.testing.unused
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A note as [EditorNotes] stores it. */
data class StoredNote(
    val content: String,
    val date: Long? = null,
    val checked: Boolean = false,
    val persons: Set<Long> = emptySet(),
    val projects: Set<Long> = emptySet(),
) {
    override fun toString() = "StoredNote(date=$date)"
}

/**
 * The editor's side of [NoteRepository] in memory, following data-layer.md § Note save: an insert gets the next id and
 * inserts the draft's new entities into [entities]; a save of a vanished note is `NoteGone`; an unchanged note is not
 * written. Every save draft and delete call is recorded; [saveError] makes saves fail without writing, and a set
 * [saveGate] or [deleteGate] holds saves or deletes until it completes.
 */
class EditorNotes(private val entities: EditorEntities) : NoteRepository by unused() {
    val notes = mutableMapOf<Long, StoredNote>()
    val saves = mutableListOf<NoteDraft>()
    val inserts get() = saves.count { it.noteId == null }
    val deletes = mutableListOf<Set<Long>>()
    var saveError: AppError? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var deleteGate: CompletableDeferred<Unit>? = null
    private var nextId = 100L

    override suspend fun loadNote(id: Long): AppResult<NoteDetail?> = AppResult.Ok(
        notes[id]?.let {
            NoteDetail(
                id, it.content, it.date, it.checked, null, 0, 0,
                entities.refs(EntityKind.Person, it.persons), entities.refs(EntityKind.Project, it.projects),
            )
        },
    )

    override suspend fun save(draft: NoteDraft): AppResult<SaveResult> {
        saves += draft
        saveGate?.await()
        saveError?.let { return AppResult.Err(it) }
        val existing = draft.noteId?.let { notes[it] ?: return AppResult.Ok(SaveResult.NoteGone) }
        val createdPersons = draft.newPersons.associate { it.key to entities.insert(EntityKind.Person, it.name) }
        val createdProjects = draft.newProjects.associate { it.key to entities.insert(EntityKind.Project, it.name) }
        val next = StoredNote(
            content = draft.content,
            date = draft.date,
            checked = existing?.checked ?: draft.checked,
            persons = draft.personIds.filterTo(HashSet()) { entities.exists(EntityKind.Person, it) } + createdPersons.values,
            projects = draft.projectIds.filterTo(HashSet()) { entities.exists(EntityKind.Project, it) } + createdProjects.values,
        )
        val noteId = draft.noteId ?: nextId++
        val wrote = next != existing
        if (wrote) notes[noteId] = next
        return AppResult.Ok(SaveResult.Saved(noteId, wrote, createdPersons, createdProjects))
    }

    override suspend fun setChecked(noteId: Long, checked: Boolean): AppResult<Boolean> {
        val note = notes[noteId] ?: return AppResult.Ok(false)
        if (note.checked == checked) return AppResult.Ok(false)
        notes[noteId] = note.copy(checked = checked)
        return AppResult.Ok(true)
    }

    override suspend fun deleteNotes(noteIds: Collection<Long>): AppResult<BatchResult> {
        deleteGate?.await()
        deletes += noteIds.toSet()
        val gone = noteIds.count { notes.remove(it) != null }
        return AppResult.Ok(BatchResult(noteIds.size, gone))
    }
}

/** Persons and projects in memory: the existence probes the editor reads, and the DEC-34 deletes. */
class EditorEntities : EntityRepository by unused() {
    private val names = EntityKind.entries.associateWith { MutableStateFlow(mapOf<Long, String>()) }
    private var nextId = 500L

    /** The counts every impact read and every delete reports. */
    var impact = DeleteImpact(n = 0, m = 0)

    fun put(kind: EntityKind, id: Long, name: String) {
        names.getValue(kind).value += id to name
    }

    fun insert(kind: EntityKind, name: String): Long = nextId++.also { put(kind, it, name) }

    fun exists(kind: EntityKind, id: Long) = id in names.getValue(kind).value

    /** The links that `loadNote` reads for [ids]: the existing ones, with their names. */
    fun refs(kind: EntityKind, ids: Set<Long>): List<EntityRef> =
        ids.mapNotNull { id -> names.getValue(kind).value[id]?.let { EntityRef(id, it) } }

    override fun observe(kind: EntityKind, id: Long): Flow<EntityRef?> =
        names.getValue(kind).map { map -> map[id]?.let { EntityRef(id, it) } }

    override suspend fun personDeleteImpact(id: Long): AppResult<DeleteImpact> = AppResult.Ok(impact)

    override suspend fun projectDeleteImpact(id: Long): AppResult<DeleteImpact> = AppResult.Ok(impact)

    override suspend fun deletePerson(id: Long) = delete(EntityKind.Person, id)

    override suspend fun deleteProject(id: Long) = delete(EntityKind.Project, id)

    private fun delete(kind: EntityKind, id: Long): AppResult<EntityDeleteResult> {
        val existed = exists(kind, id)
        names.getValue(kind).value -= id
        return AppResult.Ok(EntityDeleteResult(existed, impact.n, impact.m))
    }
}

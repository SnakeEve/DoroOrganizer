package com.doro.organizer.data

import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * The note list of [rows] with the names of [persons] and [projects] (data-layer.md § Rows, § Reactivity; DEC-106).
 * All three come from the same database instance (one `DatabaseHolder.observe` block). Each input skips a result
 * equal to its previous one, so an equal emission is not mapped again. A combination in which a link id is missing
 * from its map (one input has seen a commit that another has not yet) emits nothing: the next emission of any input
 * is tried again, and the committed state always resolves (INV-7). The first list needs all three inputs.
 */
internal fun linkedNoteRows(
    rows: Flow<List<NoteRowRaw>>,
    persons: Flow<List<EntityRef>>,
    projects: Flow<List<EntityRef>>,
): Flow<List<NoteRow>> =
    combine(rows.distinctUntilChanged(), persons.nameMap(), projects.nameMap()) { raw, personNames, projectNames ->
        raw.toNoteRows(personNames, projectNames)
    }.filterNotNull()

/** The id-to-name map of one entity kind; an equal map is skipped. */
private fun Flow<List<EntityRef>>.nameMap(): Flow<Map<Long, String>> =
    map { refs -> refs.associate { it.id to it.name } }.distinctUntilChanged()

/**
 * Maps one emission of `NoteDao.observeRows` (data-layer.md § Rows): splits the link ids, takes each name from the
 * kind's map and sorts each list by [linkOrder]. Returns null when any link id of any row is missing from its map.
 * Runs on the background dispatcher (architecture.md § Threading).
 */
internal fun List<NoteRowRaw>.toNoteRows(persons: Map<Long, String>, projects: Map<Long, String>): List<NoteRow>? {
    val order = linkOrder()
    return map { raw ->
        NoteRow(
            id = raw.id,
            preview = raw.preview,
            date = raw.date,
            checked = raw.checked,
            checkedAt = raw.checkedAt,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
            persons = links(raw.personIds, persons, raw.id, PERSON_IDS)?.sortedWith(order) ?: return null,
            projects = links(raw.projectIds, projects, raw.id, PROJECT_IDS)?.sortedWith(order) ?: return null,
        )
    }
}

private const val PERSON_IDS = "person_ids"
private const val PROJECT_IDS = "project_ids"

/** The links of [ids] named from [names]; null when an id is missing from [names]. */
private fun links(ids: String?, names: Map<Long, String>, noteId: Long, column: String): List<EntityRef>? {
    if (ids == null) return emptyList() // most rows have no link of one kind: no parsing
    return linkIds(ids, noteId, column).map { id -> EntityRef(id, names[id] ?: return null) }
}

/**
 * The ids of a `group_concat` link column: decimal, separated by `,`. Anything else throws with constant text, the
 * note id and the column name only, and no cause: never the input (DEC-70, security.md § 10).
 */
private fun linkIds(ids: String, noteId: Long, column: String): List<Long> =
    ids.split(',').map { part ->
        part.toLongOrNull() ?: throw IllegalStateException("$column of note $noteId is malformed")
    }

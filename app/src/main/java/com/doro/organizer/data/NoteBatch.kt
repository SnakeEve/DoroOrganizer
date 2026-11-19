package com.doro.organizer.data

import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.data.db.NoteProjectCrossRef
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity

/**
 * Batch associate (data-layer.md § Batch operations), inside the caller's transaction: additive, `INSERT OR IGNORE`,
 * and only notes that gained at least one link are bumped (DEC-21, DEC-36).
 */
internal suspend fun associateNotes(
    db: AppDatabase,
    noteIds: Set<Long>,
    personIds: Set<Long>,
    projectIds: Set<Long>,
    newPersons: List<NewEntity>,
    newProjects: List<NewEntity>,
    now: Long,
): BatchResult {
    val notes = db.noteDao()
    val alive = db.survivingNotes(noteIds)
    if (alive.isEmpty()) return BatchResult(noteIds.size, changed = 0) // creates no entity (DEC-33)

    val created = EntityRepository.insertNew(db, newPersons, newProjects, now)
    val persons = db.survivingEntities(EntityKind.Person, personIds) + created.persons.values
    val projects = db.survivingEntities(EntityKind.Project, projectIds) + created.projects.values

    val personLinks = alive.flatMap { note -> persons.map { NotePersonCrossRef(note, it) } }
    val projectLinks = alive.flatMap { note -> projects.map { NoteProjectCrossRef(note, it) } }
    // Room returns -1 for each link that already existed; the rowids align with the input list.
    val gained = HashSet<Long>()
    notes.insertPersonLinks(personLinks).forEachIndexed { i, rowId ->
        if (rowId != -1L) gained += personLinks[i].noteId
    }
    notes.insertProjectLinks(projectLinks).forEachIndexed { i, rowId ->
        if (rowId != -1L) gained += projectLinks[i].noteId
    }
    gained.chunked(SQL_CHUNK).forEach { notes.bump(it, now) }
    return BatchResult(noteIds.size, changed = gained.size)
}

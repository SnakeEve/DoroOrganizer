package com.doro.organizer.data

import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.NoteDao
import com.doro.organizer.data.db.NoteEntity
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.data.db.NoteProjectCrossRef
import com.doro.organizer.data.db.SaveProbe
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.SaveResult
import com.doro.organizer.model.SearchText

/**
 * Steps 3 to 7 of data-layer.md § Note save, inside the caller's transaction. Nothing is written before the
 * existence check, so a draft of a deleted note creates none of its new entities (DEC-33).
 */
internal suspend fun saveNote(db: AppDatabase, draft: NoteDraft, now: Long): SaveResult {
    val noteId = draft.noteId ?: return saveNewNote(db, draft, now)
    val probe = db.noteDao().saveProbe(noteId) ?: return SaveResult.NoteGone
    return saveExistingNote(db, noteId, probe, draft, now)
}

private suspend fun saveNewNote(db: AppDatabase, draft: NoteDraft, now: Long): SaveResult {
    val links = desiredLinks(db, draft, now)
    val note = NoteEntity(
        date = draft.date,
        checked = draft.checked,
        checkedAt = if (draft.checked) now else null, // DEC-22: a note created checked has checked_at = created_at
        createdAt = now,
        updatedAt = now,
        content = draft.content,
        contentFold = SearchText.fold(draft.content),
    )
    val noteId = db.noteDao().insertNote(note)
    db.noteDao().link(noteId, links.persons, links.projects)
    return SaveResult.Saved(noteId, wrote = true, links.created.persons, links.created.projects)
}

// Never writes checked or checked_at: editing a checked note keeps it checked (DEC-22).
private suspend fun saveExistingNote(
    db: AppDatabase,
    noteId: Long,
    probe: SaveProbe,
    draft: NoteDraft,
    now: Long,
): SaveResult {
    val notes = db.noteDao()
    val links = desiredLinks(db, draft, now)
    val oldPersons = notes.personIdsOf(noteId).toSet()
    val oldProjects = notes.projectIdsOf(noteId).toSet()
    // Exact String equality: a whitespace edit is a change. A created entity is always a new link.
    val unchanged = probe.content == draft.content && probe.date == draft.date &&
        links.persons == oldPersons && links.projects == oldProjects
    if (unchanged) return SaveResult.Saved(noteId, wrote = false, emptyMap(), emptyMap())

    notes.updateNote(noteId, draft.content, SearchText.fold(draft.content), draft.date, now)
    (oldPersons - links.persons).chunked(SQL_CHUNK).forEach { notes.unlinkPersons(noteId, it) }
    (oldProjects - links.projects).chunked(SQL_CHUNK).forEach { notes.unlinkProjects(noteId, it) }
    notes.link(noteId, links.persons - oldPersons, links.projects - oldProjects)
    return SaveResult.Saved(noteId, wrote = true, links.created.persons, links.created.projects)
}

private class DesiredLinks(val persons: Set<Long>, val projects: Set<Long>, val created: CreatedIds)

/** Step 4: the draft's links minus vanished entities, plus the entities it creates. */
private suspend fun desiredLinks(db: AppDatabase, draft: NoteDraft, now: Long): DesiredLinks {
    val persons = db.survivingPersons(draft.personIds)
    val projects = db.survivingProjects(draft.projectIds)
    val created = insertNew(db, draft.newPersons, draft.newProjects, now)
    return DesiredLinks(
        persons = (persons + created.persons.values).toSet(),
        projects = (projects + created.projects.values).toSet(),
        created = created,
    )
}

private suspend fun NoteDao.link(noteId: Long, personIds: Set<Long>, projectIds: Set<Long>) {
    insertPersonLinks(personIds.map { NotePersonCrossRef(noteId, it) })
    insertProjectLinks(projectIds.map { NoteProjectCrossRef(noteId, it) })
}

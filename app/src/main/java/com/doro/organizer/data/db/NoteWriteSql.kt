package com.doro.organizer.data.db

// The write statements of NoteDao (data-layer.md § Write rules, § Deletes, § Batch operations). Every `IN (:ids)`
// list is a chunk of at most SQL_CHUNK ids, cut by the repository.

/** What a save compares: an existing note's content and date (data-layer.md § Note save, step 3). */
const val SAVE_PROBE_SQL = "SELECT content, date FROM notes WHERE id = :id"

/** The person ids linked to `:noteId`. */
const val PERSON_IDS_OF_SQL = "SELECT person_id FROM note_persons WHERE note_id = :noteId"

/** The project ids linked to `:noteId`. */
const val PROJECT_IDS_OF_SQL = "SELECT project_id FROM note_projects WHERE note_id = :noteId"

/** An editor save with a change: content and its fold together, the single write path of DEC-29 (DEC-21). */
const val UPDATE_NOTE_SQL =
    "UPDATE notes SET content = :content, content_fold = :fold, date = :date, updated_at = :now WHERE id = :id"

const val UNLINK_PERSONS_SQL = "DELETE FROM note_persons WHERE note_id = :noteId AND person_id IN (:personIds)"

const val UNLINK_PROJECTS_SQL = "DELETE FROM note_projects WHERE note_id = :noteId AND project_id IN (:projectIds)"

/** DEC-22: guarded, so a repeated check changes nothing; `updated_at` stays. */
const val CHECK_SQL = "UPDATE notes SET checked = 1, checked_at = :now WHERE id = :id AND checked = 0"

/** DEC-22: guarded, so a repeated uncheck changes nothing; it bumps `updated_at` (DEC-21). */
const val UNCHECK_SQL =
    "UPDATE notes SET checked = 0, checked_at = NULL, updated_at = :now WHERE id = :id AND checked = 1"

/** The ids of `:ids` that still exist: a link insert to a vanished note would abort on its foreign key. */
const val EXISTING_NOTE_IDS_SQL = "SELECT id FROM notes WHERE id IN (:ids)"

/** The bump of the notes that gained a link in a batch associate (DEC-21). */
const val BUMP_SQL = "UPDATE notes SET updated_at = :now WHERE id IN (:ids)"

/** Batch set date: `IS NOT` compares NULL as a value, so only rows whose date changes are written and bumped. */
const val SET_DATE_SQL = "UPDATE notes SET date = :date, updated_at = :now WHERE id IN (:ids) AND date IS NOT :date"

/** Junction rows go explicitly before their notes, so Room's invalidation sees them (data-layer.md § Deletes). */
const val DELETE_PERSON_LINKS_OF_NOTES_SQL = "DELETE FROM note_persons WHERE note_id IN (:ids)"

const val DELETE_PROJECT_LINKS_OF_NOTES_SQL = "DELETE FROM note_projects WHERE note_id IN (:ids)"

const val DELETE_NOTES_SQL = "DELETE FROM notes WHERE id IN (:ids)"

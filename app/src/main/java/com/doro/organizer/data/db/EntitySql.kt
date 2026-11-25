package com.doro.organizer.data.db

// The read statements of PersonDao and ProjectDao (data-layer.md § Editor loads, pickers and existence probes).
// Pickers list every entity, zero-note ones included (DEC-33), and sort in Kotlin: SQLite has no Collator (DEC-32).

/** Every person with its note count, for a picker without a first group: `coLinked` is false (DEC-111). */
const val PERSON_OPTIONS_SQL = """SELECT p.id, p.name, p.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_persons c WHERE c.person_id = p.id) AS noteCount,
    0 AS coLinked
    FROM persons p"""

/**
 * Every person with its note count; `coLinked`: linked to a note, other than `:exceptNoteId`, that links any project of
 * `:coLinkedTo` (DEC-28, DEC-111). `:coLinkedTo` is a Room collection parameter of 1 to 900 ids, never empty (an empty
 * `IN ()` plans a scan of `note_projects`); `IS NOT` keeps every note when `:exceptNoteId` is null. The subquery is not
 * correlated, so SQLite runs it once per query.
 */
const val PERSON_CO_LINKED_OPTIONS_SQL = """SELECT p.id, p.name, p.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_persons c WHERE c.person_id = p.id) AS noteCount,
    p.id IN (SELECT x.person_id FROM note_projects y
        JOIN note_persons x ON x.note_id = y.note_id
        WHERE y.project_id IN (:coLinkedTo) AND y.note_id IS NOT :exceptNoteId) AS coLinked
    FROM persons p"""

/** The mirror of [PERSON_OPTIONS_SQL]. */
const val PROJECT_OPTIONS_SQL = """SELECT j.id, j.name, j.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_projects c WHERE c.project_id = j.id) AS noteCount,
    0 AS coLinked
    FROM projects j"""

/** The mirror of [PERSON_CO_LINKED_OPTIONS_SQL]: `:coLinkedTo` are person ids. */
const val PROJECT_CO_LINKED_OPTIONS_SQL = """SELECT j.id, j.name, j.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_projects c WHERE c.project_id = j.id) AS noteCount,
    j.id IN (SELECT y.project_id FROM note_persons x
        JOIN note_projects y ON y.note_id = x.note_id
        WHERE x.person_id IN (:coLinkedTo) AND x.note_id IS NOT :exceptNoteId) AS coLinked
    FROM projects j"""

/** The existence probe of a person: no row once it is deleted (data-layer.md § Reactivity). */
const val PERSON_REF_SQL = "SELECT id, name FROM persons WHERE id = :id"

/** The existence probe of a project. */
const val PROJECT_REF_SQL = "SELECT id, name FROM projects WHERE id = :id"

/** Every person's name, for the name map of the note lists (DEC-106, data-layer.md § Rows); unsorted. */
const val PERSON_NAMES_SQL = "SELECT id, name FROM persons"

/** Every project's name, for the name map of the note lists. */
const val PROJECT_NAMES_SQL = "SELECT id, name FROM projects"

/** The ids of `:ids` that are still persons (data-layer.md § Note save, step 4). */
const val EXISTING_PERSON_IDS_SQL = "SELECT id FROM persons WHERE id IN (:ids)"

/** The ids of `:ids` that are still projects. */
const val EXISTING_PROJECT_IDS_SQL = "SELECT id FROM projects WHERE id IN (:ids)"

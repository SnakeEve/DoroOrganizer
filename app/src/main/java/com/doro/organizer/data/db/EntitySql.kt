package com.doro.organizer.data.db

// The read statements of PersonDao and ProjectDao (data-layer.md § Editor loads, pickers and existence probes).
// Pickers list every entity, zero-note ones included (DEC-33), and sort in Kotlin: SQLite has no Collator (DEC-32).

/**
 * Every person with its note count. `coLinked`: shares a note with the project `:coLinkedTo`, for the secondary
 * person filter of the Project perspective (DEC-28); false when `:coLinkedTo` is null.
 */
const val PERSON_OPTIONS_SQL = """SELECT p.id, p.name, p.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_persons c WHERE c.person_id = p.id) AS noteCount,
    (:coLinkedTo IS NOT NULL AND EXISTS (SELECT 1 FROM note_projects y
        JOIN note_persons x ON x.note_id = y.note_id
        WHERE y.project_id = :coLinkedTo AND x.person_id = p.id)) AS coLinked
    FROM persons p"""

/** The mirror of [PERSON_OPTIONS_SQL]: `:coLinkedTo` is a person, for the Individual perspective's project filter. */
const val PROJECT_OPTIONS_SQL = """SELECT j.id, j.name, j.created_at AS createdAt,
    (SELECT COUNT(*) FROM note_projects c WHERE c.project_id = j.id) AS noteCount,
    (:coLinkedTo IS NOT NULL AND EXISTS (SELECT 1 FROM note_persons x
        JOIN note_projects y ON y.note_id = x.note_id
        WHERE x.person_id = :coLinkedTo AND y.project_id = j.id)) AS coLinked
    FROM projects j"""

/** The existence probe of a person: no row once it is deleted (data-layer.md § Reactivity). */
const val PERSON_REF_SQL = "SELECT id, name FROM persons WHERE id = :id"

/** The existence probe of a project. */
const val PROJECT_REF_SQL = "SELECT id, name FROM projects WHERE id = :id"

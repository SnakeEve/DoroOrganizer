package com.doro.organizer.data.db

// The delete statements of PersonDao and ProjectDao (data-layer.md § Deletes, DEC-34). The impact counts read only
// the junction tables, through idx_note_persons_person / idx_note_projects_project (testing.md I-43).

/** 1 when the person exists, else 0. */
const val PERSON_EXISTS_SQL = "SELECT COUNT(*) FROM persons WHERE id = :id"

/** N: the notes linked to the person. */
const val PERSON_IMPACT_N_SQL = "SELECT COUNT(*) FROM note_persons WHERE person_id = :id"

/** M, a subset of N: the linked notes that have no other person and no project, so they become orphans (DEC-27). */
const val PERSON_IMPACT_M_SQL = """SELECT COUNT(*) FROM note_persons x WHERE x.person_id = :id
    AND NOT EXISTS (SELECT 1 FROM note_persons o WHERE o.note_id = x.note_id AND o.person_id <> :id)
    AND NOT EXISTS (SELECT 1 FROM note_projects y WHERE y.note_id = x.note_id)"""

/** The person's junction rows, deleted explicitly before the person (data-layer.md § Deletes). */
const val DELETE_PERSON_LINKS_SQL = "DELETE FROM note_persons WHERE person_id = :id"

const val DELETE_PERSON_SQL = "DELETE FROM persons WHERE id = :id"

/** The mirror of [PERSON_EXISTS_SQL]. */
const val PROJECT_EXISTS_SQL = "SELECT COUNT(*) FROM projects WHERE id = :id"

/** The mirror of [PERSON_IMPACT_N_SQL]. */
const val PROJECT_IMPACT_N_SQL = "SELECT COUNT(*) FROM note_projects WHERE project_id = :id"

/** The mirror of [PERSON_IMPACT_M_SQL]: linked notes with no other project and no person. */
const val PROJECT_IMPACT_M_SQL = """SELECT COUNT(*) FROM note_projects y WHERE y.project_id = :id
    AND NOT EXISTS (SELECT 1 FROM note_projects o WHERE o.note_id = y.note_id AND o.project_id <> :id)
    AND NOT EXISTS (SELECT 1 FROM note_persons x WHERE x.note_id = y.note_id)"""

const val DELETE_PROJECT_LINKS_SQL = "DELETE FROM note_projects WHERE project_id = :id"

const val DELETE_PROJECT_SQL = "DELETE FROM projects WHERE id = :id"

package com.doro.organizer.data.db

// The statements of NoteDao (data-layer.md § Read model). All SQL is static: `@Query` strings are built only from
// these constants, with a bind parameter for every value (CLAUDE.md rule 3). Every list query aliases notes as n.
// QueryPlanTest runs EXPLAIN QUERY PLAN on each statement constant (testing.md I-43).

/** Notes linked to `:personId`, or every note when it is null. */
const val PERSON_PRED = """(:personId IS NULL OR EXISTS (SELECT 1 FROM note_persons x
    WHERE x.note_id = n.id AND x.person_id = :personId))"""

/** Notes linked to `:projectId`, or every note when it is null. */
const val PROJECT_PRED = """(:projectId IS NULL OR EXISTS (SELECT 1 FROM note_projects y
    WHERE y.note_id = n.id AND y.project_id = :projectId))"""

/** The only definition of an orphan: no person link and no project link, derived, never stored (DEC-27, INV-6). */
const val ORPHAN_PRED = """(NOT EXISTS (SELECT 1 FROM note_persons a WHERE a.note_id = n.id)
    AND NOT EXISTS (SELECT 1 FROM note_projects b WHERE b.note_id = n.id))"""

/** `:searchPattern` comes escaped from `SearchText.toLikeTerm`; the column is folded in Kotlin (DEC-29). */
const val SEARCH_PRED = """(:searchPattern IS NULL
    OR n.content_fold LIKE '%' || :searchPattern || '%' ESCAPE '\')"""

/**
 * Every dimension of `NoteQuery`, combined with AND (DEC-28). A range compares with NULL as false, so it excludes
 * undated notes.
 */
const val PERSPECTIVE_WHERE = """WHERE $PERSON_PRED AND $PROJECT_PRED
    AND (:orphanOnly = 0 OR $ORPHAN_PRED) AND (:undatedOnly = 0 OR n.date IS NULL)
    AND (:fromDay IS NULL OR n.date >= :fromDay) AND (:toDay IS NULL OR n.date <= :toDay)
    AND $SEARCH_PRED"""

/** DEC-23: the only ORDER BY for note lists. The note `date` never sorts. */
const val CANONICAL_ORDER = """ORDER BY n.checked ASC,
    CASE WHEN n.checked = 0 THEN n.updated_at END DESC,
    CASE WHEN n.checked = 0 THEN n.created_at END DESC,
    CASE WHEN n.checked = 1 THEN n.checked_at END ASC,
    n.id DESC"""

/**
 * The code points for which Kotlin's `Char.isWhitespace()` is true (WhitespaceCharsTest), so a preview is blank
 * exactly when the content is blank (DEC-24, DEC-25).
 */
const val WHITESPACE_CHARS = """char(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195,
    8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)"""

/**
 * The columns of `NoteRowRaw`: a preview instead of the content (`substr` counts code points, DEC-24), and the links
 * as JSON arrays of `[id, name]`, because names are free text that may contain any separator (data-layer.md § Rows).
 */
const val ROW_COLUMNS = """n.id, substr(ltrim(n.content, $WHITESPACE_CHARS), 1, 300) AS preview, n.date, n.checked,
    n.checked_at, n.created_at, n.updated_at,
    (SELECT json_group_array(json_array(p.id, p.name)) FROM note_persons np
        JOIN persons p ON p.id = np.person_id WHERE np.note_id = n.id) AS persons_json,
    (SELECT json_group_array(json_array(j.id, j.name)) FROM note_projects nj
        JOIN projects j ON j.id = nj.project_id WHERE nj.note_id = n.id) AS projects_json"""

/** Every note list (data-layer.md § Perspective query). */
const val ROWS_SQL = "SELECT $ROW_COLUMNS FROM notes n $PERSPECTIVE_WHERE $CANONICAL_ORDER"

/** Select all: the same WHERE as [ROWS_SQL], so it covers every row of the filtered list (DEC-36). */
const val IDS_SQL = "SELECT n.id FROM notes n $PERSPECTIVE_WHERE"

/**
 * The marked days of `MonthGrid` (DEC-53): non-null bounds, so SQLite searches `idx_notes_date`. Checked notes mark
 * days too.
 */
const val MARKED_DAYS_SQL = """SELECT DISTINCT n.date FROM notes n WHERE n.date BETWEEN :fromDay AND :toDay
    AND $PERSON_PRED AND $PROJECT_PRED AND $SEARCH_PRED"""

/** The editor's note columns: the full content, never `content_fold`, which only [SEARCH_PRED] reads. */
const val NOTE_DETAIL_SQL =
    "SELECT id, content, date, checked, checked_at, created_at, updated_at FROM notes WHERE id = :id"

/** The editor's person links. */
const val LINKED_PERSONS_SQL =
    "SELECT p.id, p.name FROM note_persons x JOIN persons p ON p.id = x.person_id WHERE x.note_id = :noteId"

/** The editor's project links. */
const val LINKED_PROJECTS_SQL =
    "SELECT j.id, j.name FROM note_projects y JOIN projects j ON j.id = y.project_id WHERE y.note_id = :noteId"

/** The empty-database probe of the startup-password backup offer (DEC-73). */
const val HAS_ANY_DATA_SQL =
    "SELECT EXISTS (SELECT 1 FROM notes) OR EXISTS (SELECT 1 FROM persons) OR EXISTS (SELECT 1 FROM projects)"

package com.doro.organizer.data.db

/**
 * The restore checks R-n of data-layer.md § Restore checks: statements that each return 0 on a backup this app can
 * read, run on the restore candidate before any migration, so they read the backup's own schema version.
 */
object RestoreChecks {
    /** R-1: the DEC-22 invariant `checked = 1` ⇔ `checked_at IS NOT NULL`. */
    private const val R1 = "SELECT COUNT(*) FROM notes WHERE checked NOT IN (0, 1) OR (checked = 1) <> (checked_at IS NOT NULL)"

    /** R-2: content bytes; 200,000 UTF-16 units encode to at most 600,000 UTF-8 bytes (`length()` on text stops at NUL). */
    private const val R2 = "SELECT COUNT(*) FROM notes WHERE length(CAST(content AS BLOB)) > 600000"

    /** R-3: dates within `EpochDays.MIN..MAX` (DEC-26). */
    private const val R3 = "SELECT COUNT(*) FROM notes WHERE date IS NOT NULL AND date NOT BETWEEN -25567 AND 47846"

    /** R-4: column value types, which Room is not strict about and a cursor read would throw on. */
    private const val R4 = """SELECT (SELECT COUNT(*) FROM notes WHERE typeof(content) <> 'text' OR typeof(content_fold) <> 'text'
            OR typeof(preview) <> 'text' OR typeof(checked) <> 'integer' OR typeof(date) NOT IN ('integer', 'null')
            OR typeof(checked_at) NOT IN ('integer', 'null')
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer')
     + (SELECT COUNT(*) FROM persons WHERE typeof(name) <> 'text'
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer')
     + (SELECT COUNT(*) FROM projects WHERE typeof(name) <> 'text'
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer')"""

    /** R-5: name bytes; 200 UTF-16 units encode to at most 600 UTF-8 bytes (INV-9). */
    private const val R5 = """SELECT (SELECT COUNT(*) FROM persons WHERE length(CAST(name AS BLOB)) > 600)
     + (SELECT COUNT(*) FROM projects WHERE length(CAST(name AS BLOB)) > 600)"""

    /**
     * R-6: the stored preview is [PREVIEW_EXPR] of the row's content (INV-13, DEC-106), built from the constant the
     * write path uses. A release that changes [PREVIEW_EXPR] first gives the older versions' lists a literal copy of
     * this text, so an older list never changes meaning.
     */
    private const val R6 = "SELECT COUNT(*) FROM notes WHERE preview IS NOT $PREVIEW_EXPR"

    /**
     * Version to its list. A migration that renames, retypes or drops a checked column adds a list for its target
     * version and never edits an older one. R-6 and R-4's `preview` term joined the version-1 list only because
     * version 1 is unreleased and its schema changed with them (DEC-14, DEC-106).
     */
    private val BY_VERSION: Map<Int, List<String>> = mapOf(1 to listOf(R1, R2, R3, R4, R5, R6))

    /** The checks of a backup at schema [version] (≥ 1): its own list, or the newest list below it. */
    fun forVersion(version: Int): List<String> {
        require(version >= 1) { "schema version" }
        return BY_VERSION.filterKeys { it <= version }.maxBy { it.key }.value
    }
}

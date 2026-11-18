package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/**
 * testing.md I-01, asserted in `@After` of every database test class: the data invariants of data-layer.md
 * (INV-1, INV-3 to INV-5, INV-7, INV-9, INV-10) and restore checks R-1 to R-3 and R-5. Fails with every violation,
 * naming rows by id only (DEC-70). The limits are literals copied from the docs, so a changed constant fails here.
 */
fun assertDbInvariants(db: SupportSQLiteDatabase) {
    val violations = SQL_CHECKS.flatMap { (label, sql) -> db.ids(sql).map { "$label: id $it" } } +
        db.foreignKeyViolations() + db.contentViolations() +
        db.nameViolations("persons") + db.nameViolations("projects")
    if (violations.isNotEmpty()) {
        throw AssertionError(violations.joinToString("\n", prefix = "database invariants (testing.md I-01):\n"))
    }
}

private const val MAX_CONTENT_CHARS = 200_000 // DEC-24, INV-1
private const val MAX_NAME_CHARS = 200 // DEC-32, INV-9

private val SQL_CHECKS = listOf(
    "notes.checked not 0 or 1 (DEC-22)" to "SELECT id FROM notes WHERE checked NOT IN (0, 1)",
    "notes.checked = 1 not equal to checked_at IS NOT NULL (DEC-22, R-1)" to
        "SELECT id FROM notes WHERE (checked = 1) <> (checked_at IS NOT NULL)",
    "notes timestamp NULL (DEC-20)" to "SELECT id FROM notes WHERE created_at IS NULL OR updated_at IS NULL",
    "persons timestamp NULL (DEC-20)" to "SELECT id FROM persons WHERE created_at IS NULL OR updated_at IS NULL",
    "projects timestamp NULL (DEC-20)" to "SELECT id FROM projects WHERE created_at IS NULL OR updated_at IS NULL",
    "persons.role or persons.notes not NULL (DEC-31)" to
        "SELECT id FROM persons WHERE role IS NOT NULL OR notes IS NOT NULL",
    "projects.description or projects.status not NULL (DEC-31)" to
        "SELECT id FROM projects WHERE description IS NOT NULL OR status IS NOT NULL",
    "notes.content over 600,000 bytes (R-2)" to "SELECT id FROM notes WHERE length(CAST(content AS BLOB)) > 600000",
    // EpochDays.MIN..MAX = 1900-01-01..2100-12-31 (DEC-26, INV-5, R-3)
    "notes.date outside 1900-01-01..2100-12-31 (R-3)" to
        "SELECT id FROM notes WHERE date IS NOT NULL AND date NOT BETWEEN -25567 AND 47846",
)

private fun SupportSQLiteDatabase.ids(sql: String): List<Long> =
    query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

private fun SupportSQLiteDatabase.foreignKeyViolations(): List<String> =
    query("PRAGMA foreign_key_check").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add("foreign key (INV-7): ${cursor.getString(0)} rowid ${cursor.getLong(1)}")
        }
    }

// Kotlin, not SQL: the fold needs Locale.ROOT lowercasing (DEC-29) and the length counts UTF-16 units (INV-1).
private fun SupportSQLiteDatabase.contentViolations(): List<String> =
    query("SELECT id, content, content_fold FROM notes").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val content = cursor.getString(1)
                if (content.length > MAX_CONTENT_CHARS) {
                    add("notes.content over $MAX_CONTENT_CHARS chars (INV-1): id $id")
                }
                if (cursor.getString(2) != content.lowercase(Locale.ROOT)) {
                    add("notes.content_fold differs from content (DEC-29): id $id")
                }
            }
        }
    }

// Kotlin, not SQL: SQLite trim() strips spaces only, Kotlin trim() every Char.isWhitespace (INV-9).
private fun SupportSQLiteDatabase.nameViolations(table: String): List<String> =
    query("SELECT id, name FROM $table").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.isEmpty() || name != name.trim() || name.length > MAX_NAME_CHARS) {
                    add("$table.name blank, untrimmed or over $MAX_NAME_CHARS chars (INV-9): id ${cursor.getLong(0)}")
                }
            }
        }
    }

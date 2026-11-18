package com.doro.organizer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins `assertDbInvariants` on the version 1 tables of data-layer.md § Entities, written here as DDL because the
 * Room entities do not exist yet; each case plants exactly one violation into an otherwise valid database.
 */
@RunWith(AndroidJUnit4::class)
class DbInvariantsTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun validDatabase_passes() {
        assertDbInvariants(validDatabase("valid.db"))
    }

    @Test
    fun eachViolation_isReportedByLabelAndId() {
        val cases = listOf(
            sql("UPDATE notes SET checked = 2 WHERE id = 1", "notes.checked not 0 or 1" to 1L),
            sql("UPDATE notes SET checked = 1 WHERE id = 1", "checked_at IS NOT NULL" to 1L),
            sql("UPDATE notes SET checked_at = NULL WHERE id = 2", "checked_at IS NOT NULL" to 2L),
            sql("UPDATE persons SET role = 'r' WHERE id = 1", "persons.role or persons.notes" to 1L),
            sql("UPDATE projects SET status = 's' WHERE id = 1", "projects.description or projects.status" to 1L),
            sql("UPDATE notes SET date = -25568 WHERE id = 2", "notes.date outside" to 2L),
            sql("UPDATE notes SET date = 47847 WHERE id = 1", "notes.date outside" to 1L),
            sql("UPDATE notes SET content_fold = 'Ä' WHERE id = 1", "notes.content_fold differs" to 1L),
            sql(setContent(repeated("a", 200_001)), "notes.content over 200000 chars" to 1L),
            // 200,000 UTF-16 units never exceed 600,000 bytes, so the byte check fires only together with INV-1.
            sql(
                setContent(repeated("€", 200_001)),
                "notes.content over 200000 chars" to 1L,
                "notes.content over 600,000 bytes" to 1L,
            ),
            sql("UPDATE persons SET name = '' WHERE id = 1", "persons.name blank" to 1L),
            sql("UPDATE persons SET name = 'Ann' || char(10) WHERE id = 1", "persons.name blank" to 1L),
            sql("UPDATE projects SET name = ${repeated("p", 201)} WHERE id = 1", "projects.name blank" to 1L),
            // The planted row is the second of note_persons, so foreign_key_check reports its rowid 2.
            Case(listOf("foreign key (INV-7): note_persons" to 2L)) { db ->
                db.setForeignKeyConstraintsEnabled(false)
                db.execSQL("INSERT INTO note_persons (note_id, person_id) VALUES (1, 99)")
            },
        )
        cases.forEachIndexed { index, case ->
            val db = validDatabase("case-$index.db")
            case.mutate(db)

            val error = assertThrows(AssertionError::class.java) { assertDbInvariants(db) }

            val reported = error.message.orEmpty().lines().drop(1)
            assertEquals("case $index: $reported", case.expected.size, reported.size)
            val allReported = case.expected.all { (label, id) ->
                val idAtEnd = Regex(""" (?:row)?id $id$""")
                reported.any { label in it && idAtEnd.containsMatchIn(it) }
            }
            assertTrue("case $index: $reported", allReported)
        }
    }

    @Test
    fun boundaryValues_pass() {
        val db = validDatabase("bounds.db")
        db.execSQL("UPDATE notes SET date = -25567 WHERE id = 1")
        db.execSQL("UPDATE notes SET date = 47846 WHERE id = 2")
        db.execSQL(setContent(repeated("€", 200_000)))
        db.execSQL("UPDATE persons SET name = ${repeated("n", 200)} WHERE id = 1")

        assertDbInvariants(db)
    }

    /** A mutation and the violations it must produce, each as its label and the id of the offending row. */
    private class Case(val expected: List<Pair<String, Long>>, val mutate: (SQLiteDatabase) -> Unit)

    private fun sql(statement: String, vararg expected: Pair<String, Long>) =
        Case(expected.toList()) { it.execSQL(statement) }

    /** Sets note 1's content and its matching fold to [value], an SQL expression. */
    private fun setContent(value: String) = "UPDATE notes SET content = $value, content_fold = $value WHERE id = 1"

    /** An SQL expression for [char] repeated [count] times. */
    private fun repeated(char: String, count: Int) = "replace(hex(zeroblob($count)), '00', '$char')"

    /** Two notes (one unchecked and linked, one checked), one person and one project, all valid. */
    private fun validDatabase(name: String): SQLiteDatabase {
        val db = databases.createRaw(databases.file(name), TestKey.Plain)
        V1_TABLES.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO notes (id, date, checked, checked_at, created_at, updated_at, content, content_fold) " +
                "VALUES (1, 20000, 0, NULL, 10, 20, 'Ärger Über', 'ärger über'), (2, NULL, 1, 30, 10, 10, '', '')",
        )
        db.execSQL("INSERT INTO persons (id, name, created_at, updated_at) VALUES (1, 'Ann', 10, 10)")
        db.execSQL("INSERT INTO projects (id, name, created_at, updated_at) VALUES (1, 'Apollo', 10, 10)")
        db.execSQL("INSERT INTO note_persons (note_id, person_id) VALUES (1, 1)")
        db.execSQL("INSERT INTO note_projects (note_id, project_id) VALUES (1, 1)")
        return db
    }

    private companion object {
        val V1_TABLES = listOf(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date INTEGER, " +
                "checked INTEGER NOT NULL DEFAULT 0, checked_at INTEGER, created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, content TEXT NOT NULL, content_fold TEXT NOT NULL)",
            "CREATE TABLE persons (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, role TEXT, " +
                "notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "description TEXT, status TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE note_persons (note_id INTEGER NOT NULL, person_id INTEGER NOT NULL, " +
                "PRIMARY KEY(note_id, person_id), FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE, " +
                "FOREIGN KEY(person_id) REFERENCES persons(id) ON DELETE CASCADE)",
            "CREATE TABLE note_projects (note_id INTEGER NOT NULL, project_id INTEGER NOT NULL, " +
                "PRIMARY KEY(note_id, project_id), FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE, " +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)",
        )
    }
}

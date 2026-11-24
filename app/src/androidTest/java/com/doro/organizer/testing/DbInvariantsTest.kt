package com.doro.organizer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.PREVIEW_EXPR
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins `assertDbInvariants` on the schema that the production Room builder creates (data-layer.md § Entities); each
 * case plants exactly one violation into an otherwise valid database through a raw connection, which enforces no
 * constraint Room would add.
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
            sql("UPDATE notes SET preview = 'Ärger' WHERE id = 1", "notes.preview differs" to 1L),
            sql("UPDATE notes SET preview = ' ' WHERE id = 2", "notes.preview differs" to 2L),
            sql("UPDATE notes SET content = ' x', content_fold = ' x' WHERE id = 2", "notes.preview differs" to 2L),
            sql(setContent(repeated("a", 200_001)), "notes.content over 200000 chars" to 1L),
            // 200,000 UTF-16 units never exceed 600,000 bytes, so the byte check fires only together with INV-1.
            sql(
                setContent(repeated("€", 200_001)),
                "notes.content over 200000 chars" to 1L,
                "notes.content over 600,000 bytes" to 1L,
            ),
            // 100,000 line feeds are 100,001 lines (Q14), in 100,000 units and bytes: within the other two limits.
            sql(setContent(repeated("\n", 100_000)), "notes.content over 100000 lines" to 1L),
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
        // 99,999 line feeds are 100,000 lines (Q14): alone in note 2, and with 100,001 more units in note 3, which is
        // thereby at the 200,000-unit limit too.
        db.execSQL(setContent(repeated("\n", 99_999), id = 2))
        db.execSQL(RAW_NOTE_INSERT, arrayOf<Any?>(3, null, 0, null, 10, 10, "", ""))
        db.execSQL(setContent("${repeated("\n", 99_999)} || ${repeated("a", 100_001)}", id = 3))
        db.execSQL("UPDATE persons SET name = ${repeated("n", 200)} WHERE id = 1")
        // What each note holds, measured by SQLite: characters (here each one UTF-16 unit), line feeds, UTF-8 bytes.
        val sizes = db.stringsOf(
            "SELECT length(content) || ' ' || (length(content) - length(replace(content, char(10), ''))) || ' ' || " +
                "length(CAST(content AS BLOB)) FROM notes ORDER BY id",
        )
        assertEquals(listOf("200000 0 600000", "99999 99999 99999", "200000 99999 200000"), sizes)

        assertDbInvariants(db)
    }

    /** A mutation and the violations it must produce, each as its label and the id of the offending row. */
    private class Case(val expected: List<Pair<String, Long>>, val mutate: (SQLiteDatabase) -> Unit)

    private fun sql(statement: String, vararg expected: Pair<String, Long>) =
        Case(expected.toList()) { it.execSQL(statement) }

    /** Sets note [id]'s content and its matching fold and preview to [value], an SQL expression (INV-13 holds). */
    private fun setContent(value: String, id: Int = 1) = "UPDATE notes SET content = $value, content_fold = $value, " +
        "preview = (SELECT $PREVIEW_EXPR FROM (SELECT $value AS content)) WHERE id = $id"

    /**
     * An SQL expression for [char] repeated [count] times. [char] goes into an SQL string literal as it is, so `"\n"`
     * gives line feeds.
     */
    private fun repeated(char: String, count: Int) = "replace(hex(zeroblob($count)), '00', '$char')"

    /** Two notes (one unchecked and linked, one checked), one person and one project, all valid. */
    private fun validDatabase(name: String): SQLiteDatabase {
        val file = databases.file(name)
        databases.room(file, TestKey.Plain).run {
            openHelper.writableDatabase // creates the schema
            close()
        }
        val db = databases.raw(file, TestKey.Plain)
        db.execSQL(RAW_NOTE_INSERT, arrayOf<Any?>(1, 20000, 0, null, 10, 20, "Ärger Über", "ärger über"))
        db.execSQL(RAW_NOTE_INSERT, arrayOf<Any?>(2, null, 1, 30, 10, 10, "", ""))
        db.execSQL("INSERT INTO persons (id, name, created_at, updated_at) VALUES (1, 'Ann', 10, 10)")
        db.execSQL("INSERT INTO projects (id, name, created_at, updated_at) VALUES (1, 'Apollo', 10, 10)")
        db.execSQL("INSERT INTO note_persons (note_id, person_id) VALUES (1, 1)")
        db.execSQL("INSERT INTO note_projects (note_id, project_id) VALUES (1, 1)")
        return db
    }
}

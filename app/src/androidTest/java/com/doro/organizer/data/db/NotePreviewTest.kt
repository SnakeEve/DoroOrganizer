package com.doro.organizer.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.MONGOLIAN_VOWEL_SEPARATOR
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.WHITESPACE_CODE_POINTS
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.rows
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-57 (a) to (d) on SQLCipher: the stored list preview `notes.preview` (data-layer.md INV-13, § Note save,
 * DEC-24, DEC-106). Every note is written through the real write path, `NoteRepository.save` (`INSERT_NOTE_SQL`,
 * `UPDATE_NOTE_SQL`), and its stored preview is compared in SQL with [PREVIEW_EXPR] evaluated by SQLite on the stored
 * content; no Kotlin code computes a preview. Literal expectations pin what the expression yields where it is known.
 */
@RunWith(AndroidJUnit4::class)
class NotePreviewTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        repository = noteRepository(holder, time, applicationScope)
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    /**
     * Q15 (A), DEC-106: the preview is stored when the note is written, from the content bound to that same
     * statement. The update from `"  a"` to `"b"` stores `"b"`: the subquery reads the bound `:content`, not the row's
     * old content, which an UPDATE's right-hand side would see.
     */
    @Test
    fun preview_isStoredFromTheBoundContent_onInsertAndUpdate() = runTest(timeout = 10.seconds) {
        val id = insert("  a")
        assertEquals("a", storedPreview(id))
        assertStoredPreviewMatches(id, "insert")

        update(id, "b")

        assertEquals("b", storedPreview(id))
        assertStoredPreviewMatches(id, "update")
        assertEquals("b", db.noteDao().rows().single().preview)
    }

    /** (a) and the update half of (b): content that each edge of the expression meets, inserted and saved over. */
    @Test
    fun preview_equalsTheExpressionOfTheStoredContent_forEveryEdgeCase() = runTest(timeout = 60.seconds) {
        edgeCases().forEach { (label, content, expected) ->
            val inserted = insert(content)
            assertStoredPreviewMatches(inserted, "$label, insert")
            if (expected != null) assertEquals("$label, insert", expected, storedPreview(inserted))

            val updated = insert("seed text that the update replaces")
            update(updated, content)
            assertStoredPreviewMatches(updated, "$label, update")
            if (expected != null) assertEquals("$label, update", expected, storedPreview(updated))
        }
    }

    /** DEC-24, DEC-25: blank exactly when the content is blank, by the app's rule, on this runtime. */
    @Test
    fun preview_isBlankExactlyWhenTheContentIsBlank_throughTheWritePath() = runTest(timeout = 30.seconds) {
        val whitespace = WHITESPACE_CODE_POINTS.joinToString("") { it.toChar().toString() }
        listOf("", whitespace, whitespace + "x", "$MONGOLIAN_VOWEL_SEPARATOR", " $MONGOLIAN_VOWEL_SEPARATOR ")
            .forEach { content ->
                val id = insert(content)
                val label = content.map { "U+%04X".format(it.code) }.toString()

                assertEquals(label, TextWhitespace.isBlank(content), TextWhitespace.isBlank(storedPreview(id)))
            }
    }

    /** (b): writes that keep the content keep the preview, and every one leaves INV-13 true. */
    @Test
    fun writesThatKeepTheContent_leaveThePreviewUnchanged() = runTest(timeout = 10.seconds) {
        val person = TestRows(sql).person("Ann", time.nowMillis())
        val id = insert("\n  Title\nbody")
        val stored = storedPreview(id)
        assertEquals("Title\nbody", stored)

        val writes = listOf<Pair<String, suspend () -> Unit>>(
            "save without a change" to { assertFalse(repository.save(draft(noteId = id, content = "\n  Title\nbody")).saved().wrote) },
            "check" to { assertTrue(repository.setChecked(id, true).ok()) },
            "uncheck" to { assertTrue(repository.setChecked(id, false).ok()) },
            "batch associate" to {
                assertEquals(1, repository.associate(listOf(id), setOf(person), emptySet(), emptyList(), emptyList()).ok().changed)
            },
            "batch set date" to { assertEquals(1, repository.setDate(listOf(id), TEST_DAY).ok().changed) },
            "save with a new date only" to { assertTrue(repository.save(draft(noteId = id, content = "\n  Title\nbody", date = TEST_DAY + 1, personIds = setOf(person))).saved().wrote) },
        )
        writes.forEach { (label, write) ->
            write()

            assertEquals(label, stored, storedPreview(id))
            assertStoredPreviewMatches(id, label)
        }
    }

    /** (c): the `@Query` INSERT returns the id of the row it inserted, also after a deleted highest id (INV-11). */
    @Test
    fun insertNote_returnsTheIdOfTheInsertedRow() = runTest(timeout = 10.seconds) {
        val now = time.nowMillis()
        val dao = db.noteDao()
        val first = dao.insertNote(null, false, null, now, "first", "first")
        val second = dao.insertNote(TEST_DAY, true, now, now, "Second", "second")
        sql.execSQL("DELETE FROM notes WHERE id = ?", arrayOf(second))
        val third = dao.insertNote(null, false, null, now, "third", "third")

        assertEquals(first, sql.longOf("SELECT id FROM notes WHERE content = 'first'"))
        assertEquals(second + 1, third)
        assertEquals(third, sql.longOf("SELECT id FROM notes WHERE content = 'third'"))
        assertEquals(listOf(first, third), db.noteDao().rows().map { it.id }.sorted())
        assertEquals("third", storedPreview(third))
    }

    /**
     * (d): lists read the stored column, not the content. After a raw UPDATE gives one row another preview, both the
     * DAO rows and the repository's rows show it; the test puts the preview back before the invariants run.
     */
    @Test
    fun lists_readTheStoredPreview_notTheContent() = runTest(timeout = 10.seconds) {
        val id = insert("the content")
        val other = insert("another note")
        sql.execSQL("UPDATE notes SET preview = ? WHERE id = ?", arrayOf<Any>("a stored text", id))

        assertEquals("a stored text", db.noteDao().rows().single { it.id == id }.preview)
        assertEquals("a stored text", repository.observeRows(ALL_NOTES).first().single { it.id == id }.preview)
        assertEquals("another note", repository.observeRows(ALL_NOTES).first().single { it.id == other }.preview)

        sql.execSQL("UPDATE notes SET preview = $PREVIEW_EXPR WHERE id = ?", arrayOf(id))
        assertEquals("the content", storedPreview(id))
    }

    /** A content to write, with the preview it must store when that is known exactly (null: INV-13 only). */
    private data class Case(val label: String, val content: String, val expected: String?)

    private fun edgeCases(): List<Case> {
        val emoji = "😀"
        val leadingWhitespace = WHITESPACE_CODE_POINTS.map { cp ->
            val c = cp.toChar()
            Case("U+%04X then text".format(cp), "$c$c" + "Title", "Title")
        }
        return leadingWhitespace + listOf(
            Case("empty", "", ""),
            Case("every whitespace code point", WHITESPACE_CODE_POINTS.joinToString("") { it.toChar().toString() }, ""),
            // U+180E is text to the app's rule (DEC-24), so the preview keeps it.
            Case("U+180E then text", "$MONGOLIAN_VOWEL_SEPARATOR" + "Title", "$MONGOLIAN_VOWEL_SEPARATOR" + "Title"),
            Case("301 emoji", emoji.repeat(301), emoji.repeat(300)),
            Case("a surrogate pair at code point 300", "a".repeat(299) + emoji + "b", "a".repeat(299) + emoji),
            Case("a surrogate pair after code point 300", "a".repeat(300) + emoji, "a".repeat(300)),
            Case("markdown as typed", "# H\n- [ ] **b** `c` \\*", "# H\n- [ ] **b** `c` \\*"),
            Case("a lone high surrogate inside", "a\uD83Db", null),
            Case("a lone high surrogate at the end", "a\uD83D", null),
            Case("a lone low surrogate", "\uDE00a", null),
            Case("an embedded NUL", "a\u0000b", null),
            Case("$MAX_CONTENT_CHARS characters", " " + "c".repeat(MAX_CONTENT_CHARS - 1), "c".repeat(300)),
        )
    }

    private suspend fun insert(content: String): Long = repository.save(draft(content = content)).saved().noteId

    private suspend fun update(id: Long, content: String) {
        assertTrue(repository.save(draft(noteId = id, content = content)).saved().wrote)
    }

    private fun storedPreview(id: Long): String =
        sql.query("SELECT preview FROM notes WHERE id = ?", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()) { "no note $id" }
            cursor.getString(0)
        }

    /** INV-13 for one row, compared in SQL, so no value passes through a Java string on the way. */
    private fun assertStoredPreviewMatches(id: Long, label: String) {
        val matches = sql.query("SELECT preview IS $PREVIEW_EXPR FROM notes WHERE id = ?", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()) { "no note $id" }
            cursor.getLong(0)
        }
        assertEquals("$label: preview IS PREVIEW_EXPR(content)", 1L, matches)
    }
}

package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.toNoteRows
import com.doro.organizer.model.EntityRef
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.WHITESPACE_CODE_POINTS
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.kotlinWhitespaceCodePoints
import com.doro.organizer.testing.rows
import kotlin.time.Duration.Companion.seconds
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
 * The row columns of the list query on SQLCipher (data-layer.md § Rows): the preview of DEC-24 and DEC-25
 * (testing.md I-09, with [WHITESPACE_CHARS] checked against ART's `Char.isWhitespace()`) and the links' JSON, which
 * `json_group_array` writes and [toNoteRows] reads back (I-47). The JSON round trip also proves that the shipped
 * SQLCipher build has the JSON functions (data-layer.md § Rows).
 */
@RunWith(AndroidJUnit4::class)
class NoteRowTest {
    @get:Rule
    val databases = TestDatabases()

    private val now = FakeAppTime().nowMillis()
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows

    @Before
    fun createDatabase() {
        db = databases.create()
        rows = TestRows(db.openHelper.writableDatabase)
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun preview_holdsAtMost300CodePoints() = runTest(timeout = 10.seconds) {
        val id = rows.note("a".repeat(1_000), createdAt = now)

        assertEquals("a".repeat(300), preview(id))
    }

    @Test
    fun preview_ofEmoji_counts300CodePoints_withoutLoneSurrogate() = runTest(timeout = 10.seconds) {
        val id = rows.note("😀".repeat(400), createdAt = now)

        val preview = preview(id)

        assertEquals(300, preview.codePointCount(0, preview.length))
        assertEquals(600, preview.length)
        assertFalse(preview.last().isHighSurrogate())
    }

    @Test
    fun whitespaceChars_listExactlyKotlinWhitespaceOnThisRuntime() {
        assertEquals(kotlinWhitespaceCodePoints(), WHITESPACE_CODE_POINTS)
    }

    @Test
    fun preview_isBlankExactlyWhenContentIsBlank() = runTest(timeout = 10.seconds) {
        val whitespace = WHITESPACE_CODE_POINTS.joinToString("") { it.toChar().toString() }
        val empty = rows.note("", createdAt = now)
        val blank = rows.note(whitespace, createdAt = now)
        val text = rows.note(whitespace + "x", createdAt = now)

        assertEquals("", preview(empty))
        assertEquals("", preview(blank))
        assertEquals("x", preview(text))
    }

    @Test
    fun preview_startsAtTheFirstNonWhitespaceCharacter() = runTest(timeout = 10.seconds) {
        val id = rows.note("\n".repeat(400) + "Title\nsecond line " + "b".repeat(500), createdAt = now)

        val preview = preview(id)

        assertTrue(preview, preview.startsWith("Title\nsecond line "))
        assertEquals(300, preview.codePointCount(0, preview.length))
        assertEquals("Title", preview.lines().first { it.isNotBlank() }) // the row title of DEC-39
    }

    @Test
    fun preview_keepsMarkdownAsTyped() = runTest(timeout = 10.seconds) {
        val markdown = "# Heading\n- [ ] item **bold** `code` \\*"
        val id = rows.note(markdown, createdAt = now)

        assertEquals(markdown, preview(id))
    }

    @Test
    fun links_roundTripThroughSqliteJson() = runTest(timeout = 10.seconds) {
        val note = rows.note("x", createdAt = now)
        val names = listOf("say \"hi\"", "back\\slash", "a, b", "[x] ]y[", "line1\nline2", "smile 😀")
        val persons = (names + "n".repeat(200) + "Twin" + "Twin").map { EntityRef(rows.person(it, now), it) }
        val projects = names.map { EntityRef(rows.project(it, now), it) }
        persons.forEach { rows.linkPerson(note, it.id) }
        projects.forEach { rows.linkProject(note, it.id) }

        val row = rawRows().toNoteRows().single()

        assertEquals(persons.toSet(), row.persons.toSet())
        assertEquals(persons.size, row.persons.size)
        assertEquals(projects.toSet(), row.projects.toSet())
        assertFalse(row.orphan)
    }

    @Test
    fun noLinks_giveEmptyJsonArrays() = runTest(timeout = 10.seconds) {
        rows.note("x", createdAt = now)

        val raw = rawRows().single()

        assertEquals("[]", raw.personsJson)
        assertEquals("[]", raw.projectsJson)
    }

    private suspend fun preview(id: Long): String = rawRows().single { it.id == id }.preview

    private suspend fun rawRows(): List<NoteRowRaw> = db.noteDao().rows()
}

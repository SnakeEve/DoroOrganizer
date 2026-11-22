package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.WHITESPACE_CODE_POINTS
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.rows
import com.doro.organizer.testing.runtimeWhitespaceDifferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row columns of the list query on SQLCipher (data-layer.md § Rows): the preview of DEC-24 and DEC-25
 * (testing.md I-09: [WHITESPACE_CHARS] cuts what [TextWhitespace] calls whitespace, checked on ART where
 * `Char.isWhitespace()` differs) and the link ids, which `group_concat` writes and `NoteRepository.observeRows` names
 * from the name maps (I-47, DEC-106).
 */
@RunWith(AndroidJUnit4::class)
class NoteRowTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
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
    fun preview_isBlankExactlyWhenContentIsBlank() = runTest(timeout = 10.seconds) {
        val whitespace = WHITESPACE_CODE_POINTS.joinToString("") { it.toChar().toString() }
        val empty = rows.note("", createdAt = now)
        val blank = rows.note(whitespace, createdAt = now)
        val text = rows.note(whitespace + "x", createdAt = now)

        assertEquals("", preview(empty))
        assertEquals("", preview(blank))
        assertEquals("x", preview(text))
    }

    /**
     * The SQL preview cuts exactly the app's whitespace, also at the code points where this runtime's
     * `Char.isWhitespace()` disagrees with it (on ART U+180E, always included): a preview starts at the first character
     * that [TextWhitespace] calls text and is blank exactly when its content is (DEC-24, DEC-25).
     */
    @Test
    fun preview_followsTheAppsRule_whereTheRuntimeDiffers() = runTest(timeout = 10.seconds) {
        runtimeWhitespaceDifferences().map(Int::toChar).forEach { c ->
            val label = "U+%04X".format(c.code)
            listOf("$c", " $c ", "$c".repeat(300) + "Title").forEach { content ->
                val preview = preview(rows.note(content, createdAt = now))

                assertEquals(label, !TextWhitespace.isWhitespace(c), preview.startsWith("$c"))
                assertEquals(label, TextWhitespace.isBlank(content), TextWhitespace.isBlank(preview))
            }
        }
    }

    @Test
    fun preview_startsAtTheFirstNonWhitespaceCharacter() = runTest(timeout = 10.seconds) {
        val id = rows.note("\n".repeat(400) + "Title\nsecond line " + "b".repeat(500), createdAt = now)

        val preview = preview(id)

        assertTrue(preview, preview.startsWith("Title\nsecond line "))
        assertEquals(300, preview.codePointCount(0, preview.length))
        assertEquals("Title", preview.lines().first { !TextWhitespace.isBlank(it) }) // the row title of DEC-39
    }

    @Test
    fun preview_keepsMarkdownAsTyped() = runTest(timeout = 10.seconds) {
        val markdown = "# Heading\n- [ ] item **bold** `code` \\*"
        val id = rows.note(markdown, createdAt = now)

        assertEquals(markdown, preview(id))
    }

    /**
     * The names of I-47, cut to the 200-character maximum (`MAX_NAME_CHARS`), come out of the database exactly: the
     * rows carry the ids and `NoteRepository.observeRows` names them from the name maps (DEC-106).
     */
    @Test
    fun links_roundTripThroughTheRepository() = runTest(timeout = 10.seconds) {
        val note = rows.note("x", createdAt = now)
        val names = listOf("say \"hi\"", "back\\slash", "a, b", "[x] ]y[", "line1\nline2", "smile 😀")
        val persons = (names + "n".repeat(200) + "Twin" + "Twin").map { EntityRef(rows.person(it, now), it) }
        val projects = names.map { EntityRef(rows.project(it, now), it) }
        persons.forEach { rows.linkPerson(note, it.id) }
        projects.forEach { rows.linkProject(note, it.id) }

        val row = repository().observeRows(ALL_NOTES).first().single()

        assertEquals(persons.toSet(), row.persons.toSet())
        assertEquals(persons.size, row.persons.size)
        assertEquals(projects.toSet(), row.projects.toSet())
        assertEquals(projects.size, row.projects.size)
        assertFalse(row.orphan)
    }

    /** `group_concat` writes each linked id in decimal, 19-digit ids included, separated by `,` (DEC-106). */
    @Test
    fun linkIds_comeAsGroupConcatOfTheIds() = runTest(timeout = 10.seconds) {
        val note = rows.note("x", createdAt = now)
        val other = rows.note("y", createdAt = now)
        val persons = listOf(rows.person("A", now), rows.person("B", now), insertPerson(BIG_ID, "C"))
        val project = rows.project("X", now)
        persons.forEach { rows.linkPerson(note, it) }
        rows.linkProject(note, project)
        rows.linkPerson(other, persons.first())

        val raw = rawRows().single { it.id == note }
        val personIds = checkNotNull(raw.personIds).split(',')

        assertEquals(persons.toSet(), personIds.map(String::toLong).toSet())
        assertEquals(persons.size, personIds.size)
        assertTrue(personIds.toString(), "$BIG_ID" in personIds)
        assertEquals("$project", raw.projectIds)
        assertEquals("${persons.first()}", rawRows().single { it.id == other }.personIds)
    }

    @Test
    fun noLinks_giveNullIdColumns() = runTest(timeout = 10.seconds) {
        rows.note("x", createdAt = now)

        val raw = rawRows().single()

        assertNull(raw.personIds)
        assertNull(raw.projectIds)
        assertTrue(repository().observeRows(ALL_NOTES).first().single().orphan)
    }

    private fun insertPerson(id: Long, name: String): Long {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO persons (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(id, name, now, now),
        )
        return id
    }

    private suspend fun preview(id: Long): String = rawRows().single { it.id == id }.preview

    private suspend fun rawRows(): List<NoteRowRaw> = db.noteDao().rows()

    private fun repository(): NoteRepository = noteRepository(holder, time, applicationScope)

    private companion object {
        /** A 19-digit id: `group_concat` must write it in decimal, as `toLongOrNull` reads it back. */
        const val BIG_ID = 9_000_000_000_000_000_007L
    }
}

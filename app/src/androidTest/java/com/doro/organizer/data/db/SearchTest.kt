package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.SearchText
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.listedIds
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search on SQLCipher's SQLite (DEC-29): testing.md I-24 (escaping), I-25 (Unicode folding), I-26 (the placeholder
 * is not content) and I-27 (long queries). Queries go through the production `SearchText.toLikeTerm`, as
 * `NoteFilter.toQuery` binds them; content and `content_fold` are written by [TestRows], and by `NoteRepository` for
 * the edit case.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

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
    fun escaping_wildcardsMatchLiterally() = runTest(timeout = 10.seconds) {
        val percent = note("50% done")
        val fiveHundred = note("500 done")
        val underscore = note("a_b")
        val axb = note("axb")
        val backslash = note("C:\\x")
        val noBackslash = note("C:x")

        assertEquals(listOf(percent), found("50%"))
        assertEquals(listOf(underscore), found("a_b"))
        assertEquals(listOf(backslash), found("C:\\x"))
        assertEquals("% matches only notes containing %", listOf(percent), found("%"))
        assertEquals("_ matches only notes containing _", listOf(underscore), found("_"))
        assertEquals(listOf(backslash), found("\\"))
        assertEquals(listOf(underscore), found("  a_b \n"))
        assertEquals(
            "a blank query is no filter",
            listOf(noBackslash, backslash, axb, underscore, fiveHundred, percent),
            found("   "),
        )
    }

    @Test
    fun unicode_foldsBothDirections() = runTest(timeout = 10.seconds) {
        val pairs = listOf("ÄRGER" to "ärger", "ŁÓDŹ" to "łódź", "ПРИВЕТ" to "привет", "ΣΟΦΙΑ" to "σοφια")
        pairs.forEach { (upper, lower) ->
            val upperNote = note("x $upper y")
            val lowerNote = note("x $lower y")

            assertEquals(upper, listOf(lowerNote, upperNote), found(upper))
            assertEquals(lower, listOf(lowerNote, upperNote), found(lower))
            clear()
        }
    }

    @Test
    fun unicode_pinnedNonMatches() = runTest(timeout = 10.seconds) {
        val dottedCapital = note("İSTANBUL") // folds to i + combining dot above
        val dotless = note("istanbul")
        val sharpS = note("Straße")
        val doubleS = note("STRASSE")

        assertEquals(listOf(dotless), found("istanbul"))
        assertEquals(listOf(dottedCapital), found("İSTANBUL"))
        assertEquals(listOf(sharpS), found("straße"))
        assertEquals(listOf(doubleS), found("strasse"))
    }

    @Test
    fun unicode_finalSigmaLimit() = runTest(timeout = 10.seconds) {
        val word = note("ΟΔΟΣ")
        note("ΟΔΟΣΑ") // Σ inside a word folds to σ, at its end to ς (Final_Sigma)

        assertEquals(listOf(word), found("ΟΔΟΣ"))
    }

    @Test
    fun unicode_editUpdatesTheFold() = runTest(timeout = 10.seconds) {
        val repository = noteRepository(db, FakeAppTime(), applicationScope)
        val id = repository.save(draft(content = "ÄRGER")).saved().noteId
        assertEquals(listOf(id), found("ärger"))

        repository.save(draft(noteId = id, content = "ŁÓDŹ")).saved()

        assertEquals(emptyList<Long>(), found("ärger"))
        assertEquals(listOf(id), found("łódź"))
    }

    @Test
    fun names_areNeverSearched() = runTest(timeout = 10.seconds) {
        val linked = note("meeting notes")
        rows.linkPerson(linked, rows.person("Zebra", now))
        rows.linkProject(linked, rows.project("Okapi", now))

        assertEquals(emptyList<Long>(), found("zebra"))
        assertEquals(emptyList<Long>(), found("okapi"))
        assertEquals(listOf(linked), found("MEETING"))
    }

    @Test
    fun checkedHits_stayAtTheBottom() = runTest(timeout = 10.seconds) {
        val checked = rows.note("budget plan", createdAt = now, updatedAt = now + 10, checkedAt = now + 10)
        val older = rows.note("Budget", createdAt = now)
        val newer = rows.note("BUDGET review", createdAt = now + 1)
        note("unrelated")

        assertEquals(listOf(newer, older, checked), found("budget"))
    }

    @Test
    fun placeholder_isNotContent() = runTest(timeout = 10.seconds) {
        note("")
        note("  \n\t")
        val other = note("data")

        assertEquals(emptyList<Long>(), found("(No data)"))
        assertEquals(emptyList<Long>(), found("no data"))
        assertEquals(listOf(other), found("data"))
    }

    @Test
    fun longQuery_matchesOnItsFirstMaxTermChars() = runTest(timeout = 10.seconds) {
        val query = "Lorem%Ipsum_dolor\\".repeat(4_000).take(60_000) // no whitespace: trimming never shortens it
        val head = query.take(SearchText.MAX_TERM_CHARS)
        val fullHead = note("before ${head.uppercase()} after")
        note(head.dropLast(1)) // one character short

        val hits = found(query)

        assertEquals(listOf(fullHead), hits)
        assertEquals(found(head), hits)
    }

    @Test
    fun longQuery_neverCutsInsideASurrogatePair() = runTest(timeout = 10.seconds) {
        val head = "x".repeat(SearchText.MAX_TERM_CHARS - 1)
        val query = head + "\uD83D\uDE00" + "y".repeat(60_000) // the emoji's high surrogate is character 1,000
        val match = note("$head without the emoji")
        note(head.dropLast(1))

        assertEquals(SearchText.MAX_TERM_CHARS - 1, SearchText.toLikeTerm(query)!!.length)
        assertEquals(listOf(match), found(query))
    }

    private fun note(content: String): Long = rows.note(content, createdAt = now)

    private fun clear() = db.openHelper.writableDatabase.execSQL("DELETE FROM notes")

    private suspend fun found(input: String): List<Long> =
        db.noteDao().listedIds(ALL_NOTES.copy(searchPattern = SearchText.toLikeTerm(input)))
}

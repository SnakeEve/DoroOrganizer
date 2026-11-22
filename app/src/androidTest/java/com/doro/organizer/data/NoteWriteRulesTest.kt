package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.NoteDetailRow
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EpochDays
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.MAX_CONTENT_LINES
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.longsOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.runtimeWhitespaceDifferences
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.saved
import com.doro.organizer.testing.stringsOf
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
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
 * `NoteRepository` writes against data-layer.md § Write rules: the `updated_at` matrix of DEC-21 and the bounds
 * (testing.md I-05), one `now` per transaction (I-06) and the guarded toggles of DEC-22 (I-07). Setup rows are
 * written at T0 by [TestRows], an independent write path; the action runs at T1, one minute later. The person and
 * project delete rows of the matrix belong to `EntityRepository` and its tests.
 */
@RunWith(AndroidJUnit4::class)
class NoteWriteRulesTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val t0 = time.nowMillis()
    private val t1 = t0 + MINUTE
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private lateinit var repository: NoteRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        rows = TestRows(sql)
        repository = noteRepository(holder, time, applicationScope)
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun updatedAt_insert_setsCreatedAndUpdatedToNow() = runTest(timeout = 10.seconds) {
        atT1()
        val saved = repository.save(draft(content = "new", date = TEST_DAY)).saved()

        assertTrue(saved.wrote)
        val note = detail(saved.noteId)
        assertEquals(listOf(t1, t1), listOf(note.createdAt, note.updatedAt))
        assertFalse(note.checked)
        assertNull(note.checkedAt)
        assertEquals("new", note.content)
        assertEquals(TEST_DAY, note.date)
    }

    @Test
    fun updatedAt_contentChange_bumps() = runTest(timeout = 10.seconds) {
        val id = rows.note("before", createdAt = t0)
        atT1()

        assertTrue(repository.save(draft(noteId = id, content = "after")).saved().wrote)

        assertEquals("after", detail(id).content)
        assertEquals(t0, detail(id).createdAt)
        assertBumped(id)
    }

    @Test
    fun updatedAt_whitespaceOnlyEdit_bumps() = runTest(timeout = 10.seconds) {
        val id = rows.note("text", createdAt = t0)
        atT1()

        assertTrue(repository.save(draft(noteId = id, content = "text ")).saved().wrote)
        assertBumped(id)
    }

    @Test
    fun updatedAt_dateSetChangedOrCleared_bumps() = runTest(timeout = 10.seconds) {
        val set = rows.note("a", createdAt = t0)
        val changed = rows.note("b", createdAt = t0, date = TEST_DAY)
        val cleared = rows.note("c", createdAt = t0, date = TEST_DAY)
        atT1()

        repository.save(draft(noteId = set, content = "a", date = TEST_DAY)).saved()
        repository.save(draft(noteId = changed, content = "b", date = TEST_DAY + 1)).saved()
        repository.save(draft(noteId = cleared, content = "c", date = null)).saved()

        assertEquals(listOf(TEST_DAY, TEST_DAY + 1, null), listOf(set, changed, cleared).map { detail(it).date })
        listOf(set, changed, cleared).forEach { assertBumped(it) }
    }

    @Test
    fun updatedAt_linkAdded_bumps() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val q = rows.person("Q", t0)
        val id = rows.note("a", createdAt = t0)
        rows.linkPerson(id, p)
        atT1()

        repository.save(draft(noteId = id, content = "a", personIds = setOf(p, q))).saved()

        assertEquals(setOf(p, q), personLinks(id))
        assertBumped(id)
    }

    @Test
    fun updatedAt_linksRemoved_bumps() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val x = rows.project("X", t0)
        val id = rows.note("a", createdAt = t0)
        rows.linkPerson(id, p)
        rows.linkProject(id, x)
        atT1()

        repository.save(draft(noteId = id, content = "a")).saved()

        assertEquals(emptySet<Long>(), personLinks(id))
        assertEquals(emptySet<Long>(), projectLinks(id))
        assertEquals("unlinking keeps the entities", 2L, sql.longOf(ENTITY_COUNT))
        assertBumped(id)
    }

    @Test
    fun updatedAt_createdEntity_isALinkChange() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0)
        atT1()

        val saved = repository.save(draft(noteId = id, content = "a", newProjects = listOf(NewEntity("k", "New"))))
            .saved()

        assertTrue(saved.wrote)
        assertEquals(setOf(saved.createdProjects.getValue("k")), projectLinks(id))
        assertBumped(id)
    }

    @Test
    fun updatedAt_saveWithoutChange_writesNothing() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val x = rows.project("X", t0)
        val id = rows.note("same", createdAt = t0, date = TEST_DAY)
        rows.linkPerson(id, p)
        rows.linkProject(id, x)
        val before = canonicalDump(sql)
        atT1()

        val saved = repository.save(
            draft(noteId = id, content = "same", date = TEST_DAY, personIds = setOf(p), projectIds = setOf(x)),
        ).saved()

        assertFalse(saved.wrote)
        assertEquals(emptyMap<String, Long>(), saved.createdPersons)
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun updatedAt_contentEditOfCheckedNote_bumps() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0, checkedAt = t0)
        atT1()

        repository.save(draft(noteId = id, content = "b", checked = true)).saved()

        assertBumped(id)
    }

    @Test
    fun updatedAt_openingANote_writesNothing() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val id = rows.note("text", createdAt = t0, date = TEST_DAY)
        rows.linkPerson(id, p)
        val before = canonicalDump(sql)
        atT1()

        val note = checkNotNull(repository.loadNote(id).ok())

        assertEquals(listOf("text", t0), listOf(note.content, note.updatedAt))
        assertEquals(listOf(p), note.persons.map { it.id })
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun updatedAt_check_keeps_uncheck_bumps() = runTest(timeout = 10.seconds) {
        val toCheck = rows.note("a", createdAt = t0)
        val toUncheck = rows.note("b", createdAt = t0, checkedAt = t0)
        atT1()

        assertTrue(repository.setChecked(toCheck, true).ok())
        assertTrue(repository.setChecked(toUncheck, false).ok())

        assertEquals(t0, detail(toCheck).updatedAt)
        assertBumped(toUncheck)
    }

    @Test
    fun updatedAt_associate_bumpsOnlyNotesThatGainALink() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val linked = rows.note("a", createdAt = t0)
        val unlinked = rows.note("b", createdAt = t0)
        rows.linkPerson(linked, p)
        atT1()

        val result = repository.associate(setOf(linked, unlinked), setOf(p), emptySet(), emptyList(), emptyList())

        assertEquals(BatchResult(selected = 2, changed = 1), result.ok())
        assertEquals(t0, detail(linked).updatedAt)
        assertBumped(unlinked)
        assertEquals(setOf(p), personLinks(unlinked))
    }

    @Test
    fun updatedAt_setDate_bumpsOnlyRowsWhoseDateChanges() = runTest(timeout = 10.seconds) {
        val same = rows.note("a", createdAt = t0, date = TEST_DAY)
        val other = rows.note("b", createdAt = t0, date = TEST_DAY + 1)
        val undated = rows.note("c", createdAt = t0)
        atT1()

        val result = repository.setDate(listOf(same, other, undated), TEST_DAY).ok()

        assertEquals(BatchResult(selected = 3, changed = 2), result)
        assertEquals(listOf(TEST_DAY, TEST_DAY, TEST_DAY), listOf(same, other, undated).map { detail(it).date })
        assertEquals(t0, detail(same).updatedAt)
        assertBumped(other)
        assertBumped(undated)
    }

    @Test
    fun updatedAt_removeDate_bumpsDatedNotes_notUndatedOnes() = runTest(timeout = 10.seconds) {
        val dated = rows.note("a", createdAt = t0, date = TEST_DAY)
        val undated = rows.note("b", createdAt = t0)
        atT1()

        assertEquals(BatchResult(selected = 2, changed = 1), repository.setDate(setOf(dated, undated), null).ok())

        assertNull(detail(dated).date)
        assertBumped(dated)
        assertEquals(t0, detail(undated).updatedAt)
    }

    @Test
    fun delete_removesNotesAndTheirLinks_keepsEntities() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", t0)
        val x = rows.project("X", t0)
        val doomed = rows.note("a", createdAt = t0)
        val kept = rows.note("b", createdAt = t0)
        listOf(doomed, kept).forEach {
            rows.linkPerson(it, p)
            rows.linkProject(it, x)
        }
        atT1()

        assertEquals(BatchResult(selected = 1, changed = 1), repository.deleteNotes(listOf(doomed)).ok())

        assertEquals(listOf(kept), sql.longsOf("SELECT id FROM notes"))
        assertEquals(listOf(kept), sql.longsOf("SELECT note_id FROM note_persons"))
        assertEquals(listOf(kept), sql.longsOf("SELECT note_id FROM note_projects"))
        assertEquals(2L, sql.longOf(ENTITY_COUNT))
        assertEquals(t0, detail(kept).updatedAt)
    }

    @Test
    fun save_storesNewNamesTrimmed() = runTest(timeout = 10.seconds) {
        val persons = listOf(NewEntity("p", " \tAnna\n"))
        val projects = listOf(NewEntity("x", "  Q4 "))

        repository.save(draft(newPersons = persons, newProjects = projects)).saved()

        assertEquals(listOf("Anna"), sql.stringsOf("SELECT name FROM persons"))
        assertEquals(listOf("Q4"), sql.stringsOf("SELECT name FROM projects"))
    }

    /**
     * New names are trimmed and blank by the app's rule ([TextWhitespace], DEC-32, INV-9) on every runtime, also at the
     * code points where this runtime's `Char.isWhitespace()` disagrees with it: on ART U+180E (always included), which
     * a plain `trim()` would strip, turning a name of U+180E alone into `BlankName`.
     */
    @Test
    fun save_trimsNewNamesByTheAppsRule_whereTheRuntimeDiffers() = runTest(timeout = 10.seconds) {
        runtimeWhitespaceDifferences().map(Int::toChar).forEach { c ->
            val label = "U+%04X".format(c.code)

            repository.save(draft(newPersons = listOf(NewEntity("p", " ${c}y$c\t")))).saved()
            val alone = repository.save(draft(newPersons = listOf(NewEntity("p", "$c"))))

            val newest = sql.stringsOf("SELECT name FROM persons ORDER BY id DESC")
            if (TextWhitespace.isWhitespace(c)) {
                assertEquals(label, AppResult.Err(AppError.InvalidInput(InputRule.BlankName)), alone)
                assertEquals(label, "y", newest.first())
            } else {
                alone.saved()
                assertEquals(label, listOf("$c", "${c}y$c"), newest.take(2))
            }
        }
    }

    @Test
    fun bounds_limitsThemselvesAreAccepted() = runTest(timeout = 10.seconds) {
        val name = "n".repeat(MAX_NAME_CHARS)
        // Both content limits at once (INV-1): 200,000 units holding 99,999 line breaks, so 100,000 lines.
        val content = "\n".repeat(MAX_CONTENT_LINES - 1) + "c".repeat(MAX_CONTENT_CHARS - (MAX_CONTENT_LINES - 1))

        listOf(EpochDays.MIN, EpochDays.MAX).forEach { day ->
            val newPersons = listOf(NewEntity("p", " $name "))
            repository.save(draft(content = content, date = day, newPersons = newPersons)).saved()
        }
        repository.setDate(sql.longsOf("SELECT id FROM notes"), EpochDays.MAX).ok()

        assertEquals(listOf(EpochDays.MAX, EpochDays.MAX), sql.longsOf("SELECT date FROM notes"))
        assertEquals(listOf(name, name), sql.stringsOf("SELECT name FROM persons"))
    }

    @Test
    fun bounds_outOfRangeInput_throwsAndWritesNothing() = runTest(timeout = 10.seconds) {
        val existing = rows.note("kept", createdAt = t0)
        val before = canonicalDump(sql)
        atT1()

        val rejected = listOf(
            draft(date = EpochDays.MAX + 1),
            draft(noteId = existing, content = "kept", date = EpochDays.MIN - 1),
            draft(content = "c".repeat(MAX_CONTENT_CHARS + 1)),
            draft(noteId = existing, content = "c".repeat(MAX_CONTENT_CHARS + 1)),
            draft(content = "\n".repeat(MAX_CONTENT_LINES)), // 100,001 lines (Q14)
            draft(noteId = existing, content = "c\n".repeat(MAX_CONTENT_LINES)), // 200,000 units, 100,001 lines
            draft(newPersons = listOf(NewEntity("p", "p".repeat(TOO_LONG)))),
            draft(noteId = existing, content = "kept", newProjects = listOf(NewEntity("x", "x".repeat(TOO_LONG)))),
        )
        rejected.forEachIndexed { i, it -> assertThrowsIllegalArgument("draft $i") { repository.save(it) } }
        assertThrowsIllegalArgument("setDate") { repository.setDate(listOf(existing), EpochDays.MIN - 1) }
        assertThrowsIllegalArgument("key used twice") {
            repository.associate(setOf(existing), emptySet(), emptySet(), listOf(X, NewEntity("x", "Y")), emptyList())
        }

        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun bounds_blankNewName_isInvalidInputAndWritesNothing() = runTest(timeout = 10.seconds) {
        val existing = rows.note("kept", createdAt = t0)
        val before = canonicalDump(sql)
        val blank = listOf(NewEntity("b", " \t\n"))
        val valid = listOf(NewEntity("v", "Valid"))
        val expected = AppResult.Err(AppError.InvalidInput(InputRule.BlankName))

        assertEquals(expected, repository.save(draft(content = "new", newPersons = valid + blank)))
        assertEquals(expected, repository.save(draft(noteId = existing, content = "edit", newProjects = blank)))
        assertEquals(expected, repository.associate(setOf(existing), emptySet(), emptySet(), valid, blank))

        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun oneNow_saveInsertingNoteWithTwoNewPersons() = runTest(timeout = 10.seconds) {
        atT1()
        time.tickPerNowCall = Duration.ofMillis(1)
        val newPersons = listOf(NewEntity("a", "Anna"), NewEntity("b", "Ben"))

        val saved = repository.save(
            draft(content = "with people", checked = true, newPersons = newPersons, newProjects = listOf(X)),
        ).saved()

        val note = detail(saved.noteId)
        assertEquals(listOf(t1, t1, t1), listOf(note.createdAt, note.updatedAt, note.checkedAt))
        assertEquals(List(6) { t1 }, entityTimestamps())
        assertEquals(setOf("a", "b"), saved.createdPersons.keys)
        assertEquals(saved.createdPersons.values.toSet(), personLinks(saved.noteId))
        assertEquals(saved.createdProjects.values.toSet(), projectLinks(saved.noteId))
    }

    @Test
    fun oneNow_associateBumpsAndCreatesWithOneValue() = runTest(timeout = 10.seconds) {
        val notes = List(3) { rows.note("n$it", createdAt = t0) }
        val p = rows.person("P", t0)
        atT1()
        time.tickPerNowCall = Duration.ofMillis(1)

        val result = repository.associate(notes, setOf(p), emptySet(), listOf(NewEntity("q", "Q")), listOf(X)).ok()

        assertEquals(BatchResult(selected = 3, changed = 3), result)
        assertEquals(listOf(t1, t1, t1), notes.map { detail(it).updatedAt })
        val created = listOf(t0, t0, t1, t1, t1, t1)
        assertEquals("P keeps T0; Q and X are created at T1", created, entityTimestamps().sorted())
    }

    @Test
    fun oneNow_setDateBumpsWithOneValue() = runTest(timeout = 10.seconds) {
        val notes = List(3) { rows.note("n$it", createdAt = t0) }
        atT1()
        time.tickPerNowCall = Duration.ofMillis(1)

        repository.setDate(notes, TEST_DAY).ok()

        assertEquals(listOf(t1, t1, t1), notes.map { detail(it).updatedAt })
    }

    @Test
    fun oneNow_consecutiveOperationsGetDifferentValues() = runTest(timeout = 10.seconds) {
        time.tickPerNowCall = Duration.ofMillis(1)

        val first = repository.save(draft(content = "1")).saved().noteId
        val second = repository.save(draft(content = "2")).saved().noteId
        repository.save(draft(noteId = first, content = "1 edited")).saved()

        val stamps = listOf(detail(first).createdAt, detail(second).createdAt, detail(first).updatedAt)
        assertEquals(stamps.toString(), 3, stamps.toSet().size)
    }

    @Test
    fun toggle_checkSetsCheckedAt_keepsUpdatedAt_repeatChangesNothing() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0)
        atT1()

        assertTrue(repository.setChecked(id, true).ok())
        val checked = detail(id)
        assertTrue(checked.checked)
        assertEquals(t1, checked.checkedAt)
        assertEquals(t0, checked.updatedAt)

        time.advanceBy(Duration.ofMinutes(1))
        assertFalse(repository.setChecked(id, true).ok())
        assertEquals(checked, detail(id))
    }

    @Test
    fun toggle_uncheckClearsCheckedAt_bumps_repeatChangesNothing() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0, checkedAt = t0)
        atT1()

        assertTrue(repository.setChecked(id, false).ok())
        val unchecked = detail(id)
        assertFalse(unchecked.checked)
        assertNull(unchecked.checkedAt)
        assertEquals(t1, unchecked.updatedAt)

        time.advanceBy(Duration.ofMinutes(1))
        assertFalse(repository.setChecked(id, false).ok())
        assertEquals(unchecked, detail(id))
    }

    @Test
    fun toggle_contentEditOfCheckedNote_keepsCheckedAndCheckedAt() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0, checkedAt = t0)
        atT1()

        // The draft's checked flag is read only on insert (DEC-22), so false here must change nothing.
        repository.save(draft(noteId = id, content = "b", checked = false)).saved()

        val note = detail(id)
        assertTrue(note.checked)
        assertEquals(t0, note.checkedAt)
    }

    @Test
    fun toggle_noteCreatedChecked_hasCheckedAtEqualToCreatedAt() = runTest(timeout = 10.seconds) {
        listOf(Duration.ZERO, Duration.ofMillis(1)).forEach { tick ->
            time.tickPerNowCall = tick
            val note = detail(repository.save(draft(content = "done", checked = true)).saved().noteId)

            assertTrue(note.checked)
            assertEquals("tick $tick", note.createdAt, note.checkedAt)
        }
    }

    @Test
    fun toggle_goneNote_returnsFalse() = runTest(timeout = 10.seconds) {
        val id = rows.note("a", createdAt = t0)
        repository.deleteNotes(listOf(id)).ok()

        assertFalse(repository.setChecked(id, true).ok())
        assertFalse(repository.setChecked(id, false).ok())
    }

    private fun atT1() = time.advanceBy(Duration.ofMillis(MINUTE))

    private suspend fun detail(id: Long): NoteDetailRow = checkNotNull(db.noteDao().noteDetail(id)) { "note $id gone" }

    private suspend fun assertBumped(id: Long) = assertEquals("updated_at of note $id", t1, detail(id).updatedAt)

    private suspend fun personLinks(id: Long): Set<Long> = db.noteDao().personIdsOf(id).toSet()

    private suspend fun projectLinks(id: Long): Set<Long> = db.noteDao().projectIdsOf(id).toSet()

    private fun entityTimestamps(): List<Long> = listOf("persons", "projects").flatMap { table ->
        sql.longsOf("SELECT created_at FROM $table") + sql.longsOf("SELECT updated_at FROM $table")
    }

    private suspend fun assertThrowsIllegalArgument(label: String, call: suspend () -> Any) {
        val thrown = runCatching { call() }.exceptionOrNull()
        assertTrue("$label threw $thrown", thrown is IllegalArgumentException)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val TOO_LONG = MAX_NAME_CHARS + 1
        const val ENTITY_COUNT = "SELECT (SELECT COUNT(*) FROM persons) + (SELECT COUNT(*) FROM projects)"
        val X = NewEntity("x", "X")
    }
}

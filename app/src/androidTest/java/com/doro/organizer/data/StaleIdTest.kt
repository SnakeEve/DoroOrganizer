package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.SaveResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.longsOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-42 (a) to (d), repository part: ids that name rows deleted since the UI read them are skipped, never
 * errors, and the existence probe of a deleted entity emits null (data-layer.md § Reactivity). The editor closing on
 * `NoteGone`, the filters that clear on that null and the selection pruning are UI tests.
 */
@RunWith(AndroidJUnit4::class)
class StaleIdTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
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
    fun deletedNote_saveReturnsNoteGone_writesNothing() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", now)
        val id = rows.note("draft", createdAt = now)
        repository.deleteNotes(listOf(id)).ok()
        val before = canonicalDump(sql)

        val result = repository.save(
            draft(noteId = id, content = "edited", personIds = setOf(p), newPersons = listOf(NewEntity("n", "New"))),
        ).ok()

        assertEquals(SaveResult.NoteGone, result)
        assertEquals("never re-inserted, no entity created (DEC-33)", before, canonicalDump(sql))
    }

    @Test
    fun deletedNote_loadNoteReturnsNull() = runTest(timeout = 10.seconds) {
        val id = rows.note("draft", createdAt = now)
        repository.deleteNotes(listOf(id)).ok()

        assertNull(repository.loadNote(id).ok())
    }

    @Test
    fun deletedPerson_newNote_linksTheOthers() = runTest(timeout = 10.seconds) {
        val gone = rows.person("Gone", now)
        val kept = rows.person("Kept", now)
        val goneProject = rows.project("Gone", now)
        rows.deletePerson(gone)
        rows.deleteProject(goneProject)

        val saved = repository.save(
            draft(content = "new", personIds = setOf(gone, kept), projectIds = setOf(goneProject)),
        ).saved()

        assertEquals(listOf(kept), db.noteDao().personIdsOf(saved.noteId))
        assertEquals(emptyList<Long>(), db.noteDao().projectIdsOf(saved.noteId))
    }

    @Test
    fun deletedPerson_existingNote_dropsThatLink_savesTheRest() = runTest(timeout = 10.seconds) {
        val gone = rows.person("Gone", now)
        val added = rows.person("Added", now)
        val id = rows.note("old", createdAt = now)
        rows.linkPerson(id, gone)
        rows.deletePerson(gone)

        val saved = repository.save(draft(noteId = id, content = "new", personIds = setOf(gone, added))).saved()

        assertTrue(saved.wrote)
        assertEquals(listOf(added), db.noteDao().personIdsOf(id))
        assertEquals("new", db.noteDao().noteDetail(id)?.content)
    }

    @Test
    fun batchActions_skipADeletedNote_reportTheSelection() = runTest(timeout = 10.seconds) {
        val p = rows.person("P", now)
        val notes = List(3) { rows.note("n$it", createdAt = now) }
        val selection = notes.toSet()
        repository.deleteNotes(listOf(notes[1])).ok()
        val alive = listOf(notes[0], notes[2])

        assertEquals(
            BatchResult(selected = 3, changed = 2),
            repository.associate(selection, setOf(p), emptySet(), emptyList(), emptyList()).ok(),
        )
        assertEquals(alive, sql.longsOf("SELECT note_id FROM note_persons ORDER BY note_id"))

        assertEquals(BatchResult(selected = 3, changed = 2), repository.setDate(selection, TEST_DAY).ok())
        assertEquals(listOf(TEST_DAY, TEST_DAY), sql.longsOf("SELECT date FROM notes"))

        assertEquals(BatchResult(selected = 3, changed = 2), repository.deleteNotes(selection).ok())
        assertEquals(0L, sql.longOf("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun associate_whenEverySelectedNoteIsGone_createsNoEntity() = runTest(timeout = 10.seconds) {
        val id = rows.note("n", createdAt = now)
        repository.deleteNotes(listOf(id)).ok()
        val before = canonicalDump(sql)

        val result = repository.associate(setOf(id), emptySet(), emptySet(), listOf(NewEntity("p", "P")), emptyList())

        assertEquals(BatchResult(selected = 1, changed = 0), result.ok())
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun deletedEntity_existenceProbeEmitsNull() = runTest(timeout = 10.seconds) {
        val entities = entityRepository(holder, time, applicationScope)
        val person = rows.person("P", now)
        val project = rows.project("X", now)

        entities.observe(EntityKind.Person, person).test {
            assertEquals(EntityRef(person, "P"), awaitItem())
            entities.deletePerson(person).ok()
            assertNull("the filter that used it clears (DEC-34)", awaitItem())
        }
        entities.observe(EntityKind.Project, project).test {
            assertEquals(EntityRef(project, "X"), awaitItem())
            entities.deleteProject(project).ok()
            assertNull(awaitItem())
        }
    }
}

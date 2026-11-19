package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityRef
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
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
 * The editor's reads and the empty-database probe of `NoteDao` (data-layer.md § Editor loads, pickers and existence
 * probes): `noteDetail`, `linkedPersons`, `linkedProjects` and `hasAnyData` (DEC-73).
 */
@RunWith(AndroidJUnit4::class)
class EditorLoadQueryTest {
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
    fun noteDetail_readsEveryEditorColumn() = runTest(timeout = 10.seconds) {
        val content = "  Title\nbody ".repeat(30)
        val id = rows.note(content, createdAt = now, updatedAt = now + 1, checkedAt = now + 2, date = 20_774)

        val detail = db.noteDao().noteDetail(id)

        assertEquals(NoteDetailRow(id, content, 20_774, true, now + 2, now, now + 1), detail)
    }

    @Test
    fun noteDetail_ofAMissingNote_isNull() = runTest(timeout = 10.seconds) {
        val id = rows.note(createdAt = now)

        assertNull(db.noteDao().noteDetail(id + 1))
    }

    @Test
    fun linkLoads_returnOnlyThisNotesLinks() = runTest(timeout = 10.seconds) {
        val note = rows.note(createdAt = now)
        val other = rows.note(createdAt = now)
        val ann = rows.person("Ann", now)
        val twin1 = rows.person("Twin", now)
        val twin2 = rows.person("Twin", now)
        val apollo = rows.project("Apollo", now)
        val gemini = rows.project("Gemini", now)
        listOf(ann, twin1, twin2).forEach { rows.linkPerson(note, it) }
        rows.linkProject(note, gemini)
        rows.linkPerson(other, ann)
        rows.linkProject(other, apollo)

        val persons = db.noteDao().linkedPersons(note)
        val projects = db.noteDao().linkedProjects(note)

        assertEquals(setOf(EntityRef(ann, "Ann"), EntityRef(twin1, "Twin"), EntityRef(twin2, "Twin")), persons.toSet())
        assertEquals(3, persons.size)
        assertEquals(listOf(EntityRef(gemini, "Gemini")), projects)
        assertEquals(emptyList<EntityRef>(), db.noteDao().linkedProjects(note + 100))
    }

    @Test
    fun hasAnyData_isFalseOnlyForAnEmptyDatabase() = runTest(timeout = 10.seconds) {
        assertFalse(db.noteDao().hasAnyData())

        val person = rows.person("Ann", now)
        assertTrue("a person alone", db.noteDao().hasAnyData())
        rows.deletePerson(person)

        val project = rows.project("Apollo", now)
        assertTrue("a project alone", db.noteDao().hasAnyData())
        rows.deleteProject(project)
        assertFalse(db.noteDao().hasAnyData())

        rows.note(createdAt = now)
        assertTrue("a note alone", db.noteDao().hasAnyData())
    }
}

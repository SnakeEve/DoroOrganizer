package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TEST_DAY
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-17: orphan transitions (DEC-27, §6) observed on the Orphan perspective's flow. Orphan is derived from
 * the junction tables, so every write that adds or removes a note's last link moves the note on the next emission.
 */
@RunWith(AndroidJUnit4::class)
class OrphanTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private lateinit var notes: NoteRepository
    private lateinit var entities: EntityRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        rows = TestRows(sql)
        notes = noteRepository(holder, time, applicationScope)
        entities = entityRepository(holder, time, applicationScope)
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun transitions_followTheLinks() = runTest(timeout = 10.seconds) {
        val person = rows.person("P", now)
        val project = rows.project("X", now)
        val neverLinked = rows.note("never linked", createdAt = now)

        notes.observeRows(ORPHANS).test {
            assertOrphans(setOf(neverLinked), "a never-linked note counts")

            val id = notes.save(draft(content = "new")).saved().noteId
            assertOrphans(setOf(neverLinked, id), "a new unlinked note enters")

            notes.save(draft(noteId = id, content = "new", personIds = setOf(person))).saved()
            assertOrphans(setOf(neverLinked), "linking it in the editor removes it")

            notes.save(draft(noteId = id, content = "new")).saved()
            assertOrphans(setOf(neverLinked, id), "unlinking its last link brings it back")

            notes.save(draft(noteId = id, content = "new", personIds = setOf(person))).saved()
            assertOrphans(setOf(neverLinked), "linked again")
            entities.deletePerson(person).ok()
            assertOrphans(setOf(neverLinked, id), "deleting its only person brings it back")

            notes.associate(setOf(id, neverLinked), emptySet(), setOf(project), emptyList(), emptyList()).ok()
            assertOrphans(emptySet(), "batch associate removes both")
        }
    }

    @Test
    fun aNoteWithAnotherLinkLeft_staysOut() = runTest(timeout = 10.seconds) {
        val person = rows.person("P", now)
        val project = rows.project("X", now)
        val id = rows.note("linked twice", createdAt = now)
        rows.linkPerson(id, person)
        rows.linkProject(id, project)

        entities.deletePerson(person).ok()

        assertEquals(emptyList<NoteRow>(), notes.observeRows(ORPHANS).first())
        assertEquals("the project link remains", listOf(project), db.noteDao().projectIdsOf(id))
    }

    @Test
    fun rowsWithoutLinks_carryTheOrphanFlag_inEveryPerspective() = runTest(timeout = 10.seconds) {
        val person = rows.person("P", now)
        val project = rows.project("X", now)
        val orphan = rows.note("orphan", createdAt = now, date = TEST_DAY)
        val withPerson = rows.note("person", createdAt = now, date = TEST_DAY)
        val withProject = rows.note("project", createdAt = now, date = TEST_DAY)
        rows.linkPerson(withPerson, person)
        rows.linkProject(withProject, project)
        val all = setOf(orphan, withPerson, withProject)
        val day = DateFilter.Range(TEST_DAY, TEST_DAY)
        val perspectives = listOf(
            NoteFilter(Perspective.All) to all,
            NoteFilter(Perspective.Individual, primaryId = person) to setOf(withPerson),
            NoteFilter(Perspective.Project, primaryId = project) to setOf(withProject),
            NoteFilter(Perspective.Date, primaryDays = day) to all,
            NoteFilter(Perspective.Orphan) to setOf(orphan),
        )

        perspectives.forEach { (filter, listedIds) ->
            val listed = notes.observeRows(checkNotNull(filter.toQuery())).first()
            assertEquals("${filter.perspective} lists", listedIds, listed.map { it.id }.toSet())
            listed.forEach { row ->
                assertEquals("${filter.perspective} note ${row.id}", row.id == orphan, row.orphan)
            }
        }
    }

    private suspend fun ReceiveTurbine<List<NoteRow>>.assertOrphans(ids: Set<Long>, step: String) {
        val rows = awaitItem()
        assertEquals(step, ids, rows.map { it.id }.toSet())
        assertEquals("$step: every row carries the Orphaned flag", ids.size, rows.count { it.orphan })
    }

    private companion object {
        val ORPHANS = checkNotNull(NoteFilter(Perspective.Orphan).toQuery())
    }
}

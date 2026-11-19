package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.EntityDeleteResult
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.longsOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-18: the delete impact of DEC-34 and the delete itself (data-layer.md § Deletes), for persons and, in
 * mirror, projects. Dataset for person P: n1{P}, n2{P, Q}, n3{P, project X}, n4{Q}; for project X: m1{X}, m2{X, Y},
 * m3{X, person R}, m4{Y}. Setup rows are written at T0; deletes run at T1, so a bump would show.
 */
@RunWith(AndroidJUnit4::class)
class EntityDeleteTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val t0 = time.nowMillis()
    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private lateinit var entities: EntityRepository
    private lateinit var notes: NoteRepository
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    private var p = 0L
    private var q = 0L
    private var x = 0L
    private val n = LongArray(4)

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        rows = TestRows(sql)
        entities = entityRepository(holder, time, applicationScope)
        notes = noteRepository(holder, time, applicationScope)
        p = rows.person("P", t0)
        q = rows.person("Q", t0)
        x = rows.project("X", t0)
        for (i in n.indices) n[i] = rows.note("n${i + 1}", createdAt = t0)
        rows.linkPerson(n[0], p)
        rows.linkPerson(n[1], p)
        rows.linkPerson(n[1], q)
        rows.linkPerson(n[2], p)
        rows.linkProject(n[2], x)
        rows.linkPerson(n[3], q)
        time.advanceBy(Duration.ofMinutes(1))
    }

    @After
    fun invariantsHold() = assertDbInvariants(sql)

    @Test
    fun personImpact_countsLinkedNotes_andTheOnesLeftWithoutLinks() = runTest(timeout = 10.seconds) {
        assertEquals(DeleteImpact(n = 3, m = 1), entities.personDeleteImpact(p).ok())
        assertEquals(DeleteImpact(n = 2, m = 1), entities.personDeleteImpact(q).ok())
    }

    @Test
    fun zeroNoteEntities_haveNoImpact() = runTest(timeout = 10.seconds) {
        val lonePerson = rows.person("Lone", t0)
        val loneProject = rows.project("Lone", t0)

        assertEquals(DeleteImpact(0, 0), entities.personDeleteImpact(lonePerson).ok())
        assertEquals(DeleteImpact(0, 0), entities.projectDeleteImpact(loneProject).ok())
        assertEquals(EntityDeleteResult(true, 0, 0), entities.deletePerson(lonePerson).ok())
        assertEquals(EntityDeleteResult(true, 0, 0), entities.deleteProject(loneProject).ok())
    }

    @Test
    fun deletePerson_removesItsLinksAndRow_bumpsNoNote() = runTest(timeout = 10.seconds) {
        val updatedBefore = updatedAts()

        assertEquals(EntityDeleteResult(deleted = true, n = 3, m = 1), entities.deletePerson(p).ok())

        assertEquals(0L, sql.longOf("SELECT COUNT(*) FROM note_persons WHERE person_id = $p"))
        assertEquals(listOf(q), sql.longsOf("SELECT id FROM persons"))
        assertEquals("other links stay", listOf(n[1], n[3]), sql.longsOf("SELECT note_id FROM note_persons ORDER BY 1"))
        assertEquals("project links stay", listOf(n[2]), sql.longsOf("SELECT note_id FROM note_projects"))
        assertEquals("notes stay, no updated_at bump (DEC-21)", updatedBefore, updatedAts())
    }

    @Test
    fun deleteProject_mirrorsThePersonDelete() = runTest(timeout = 10.seconds) {
        val y = rows.project("Y", t0)
        val r = rows.person("R", t0)
        val m = List(4) { rows.note("m${it + 1}", createdAt = t0) }
        rows.linkProject(m[0], x)
        rows.linkProject(m[1], x)
        rows.linkProject(m[1], y)
        rows.linkProject(m[2], x)
        rows.linkPerson(m[2], r)
        rows.linkProject(m[3], y)
        val updatedBefore = updatedAts()

        // n3{P, X} adds to N but not to M: P stays.
        assertEquals(DeleteImpact(n = 4, m = 1), entities.projectDeleteImpact(x).ok())
        assertEquals(EntityDeleteResult(deleted = true, n = 4, m = 1), entities.deleteProject(x).ok())

        assertEquals(listOf(y), sql.longsOf("SELECT id FROM projects"))
        assertEquals(listOf(m[1], m[3]), sql.longsOf("SELECT note_id FROM note_projects ORDER BY 1"))
        assertEquals(updatedBefore, updatedAts())
    }

    @Test
    fun linkAddedAfterTheDialog_theDeleteUsesFreshCounts() = runTest(timeout = 10.seconds) {
        assertEquals(DeleteImpact(n = 3, m = 1), entities.personDeleteImpact(p).ok())
        rows.linkPerson(n[0], q)

        assertEquals(EntityDeleteResult(deleted = true, n = 3, m = 0), entities.deletePerson(p).ok())
    }

    @Test
    fun goneEntity_isNotDeletedTwice_andWritesNothing() = runTest(timeout = 10.seconds) {
        entities.deletePerson(p).ok()
        entities.deleteProject(x).ok()
        time.advanceBy(Duration.ofMinutes(1))
        val before = canonicalDump(sql)

        assertEquals(DeleteImpact(0, 0), entities.personDeleteImpact(p).ok())
        assertEquals(EntityDeleteResult(deleted = false, n = 0, m = 0), entities.deletePerson(p).ok())
        assertEquals(EntityDeleteResult(deleted = false, n = 0, m = 0), entities.deleteProject(x).ok())
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun deletePerson_orphanListReEmits() = runTest(timeout = 10.seconds) {
        notes.observeRows(ORPHANS).test {
            assertEquals(emptyList<Long>(), awaitItem().map { it.id })

            entities.deletePerson(p).ok()

            assertEquals("M = 1: n1 moves to Orphans", listOf(n[0]), awaitItem().map { it.id })
        }
    }

    private fun updatedAts(): List<Long> = sql.longsOf("SELECT updated_at FROM notes ORDER BY id")

    private companion object {
        val ORPHANS = checkNotNull(NoteFilter(Perspective.Orphan).toQuery())
    }
}

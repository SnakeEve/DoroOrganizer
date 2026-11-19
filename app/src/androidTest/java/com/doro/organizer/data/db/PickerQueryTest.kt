package com.doro.organizer.data.db

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.model.EntityRef
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker queries and existence probes of `PersonDao` and `ProjectDao` (data-layer.md § Editor loads, pickers and
 * existence probes): every entity with its note count and creation time, zero-note entities included (DEC-33), and
 * `coLinked` for the secondary filters of DEC-28. A probe re-emits null on the same subscription once its row is
 * deleted, which clears a stale primary or filter (data-layer.md § Reactivity, DEC-34, DEC-52).
 *
 * Dataset: n1 links Ann and Apollo, n2 links Ann and Bob, n3 links Bob and Gemini; Cid and Zeus have no notes.
 */
@RunWith(AndroidJUnit4::class)
class PickerQueryTest {
    @get:Rule
    val databases = TestDatabases()

    private val time = FakeAppTime()
    private lateinit var db: AppDatabase
    private lateinit var rows: TestRows
    private var ann = 0L
    private var bob = 0L
    private var cid = 0L
    private var apollo = 0L
    private var gemini = 0L
    private var zeus = 0L

    @Before
    fun seed() {
        db = databases.create()
        rows = TestRows(db.openHelper.writableDatabase)
        val now = time.nowMillis()
        ann = rows.person("Ann", now)
        bob = rows.person("Bob", now + 1)
        cid = rows.person("Cid", now + 2)
        apollo = rows.project("Apollo", now + 3)
        gemini = rows.project("Gemini", now + 4)
        zeus = rows.project("Zeus", now + 5)
        val n1 = rows.note("1", createdAt = now)
        val n2 = rows.note("2", createdAt = now)
        val n3 = rows.note("3", createdAt = now)
        rows.linkPerson(n1, ann)
        rows.linkProject(n1, apollo)
        rows.linkPerson(n2, ann)
        rows.linkPerson(n2, bob)
        rows.linkPerson(n3, bob)
        rows.linkProject(n3, gemini)
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun personOptions_listEveryPersonWithCounts() = runTest(timeout = 10.seconds) {
        val start = time.nowMillis()

        val options = db.personDao().observeOptions(coLinkedTo = null).first().sortedBy { it.id }

        val expected = listOf(
            option(ann, "Ann", start, 2),
            option(bob, "Bob", start + 1, 2),
            option(cid, "Cid", start + 2, 0),
        )
        assertEquals(expected, options)
    }

    @Test
    fun projectOptions_listEveryProjectWithCounts() = runTest(timeout = 10.seconds) {
        val start = time.nowMillis()

        val options = db.projectDao().observeOptions(coLinkedTo = null).first().sortedBy { it.id }

        val expected = listOf(
            option(apollo, "Apollo", start + 3, 1),
            option(gemini, "Gemini", start + 4, 1),
            option(zeus, "Zeus", start + 5, 0),
        )
        assertEquals(expected, options)
    }

    @Test
    fun personOptions_markPersonsSharingANoteWithTheProject() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(ann), coLinkedPersons(apollo))
        assertEquals(setOf(bob), coLinkedPersons(gemini))
        assertEquals(emptySet<Long>(), coLinkedPersons(zeus))
    }

    @Test
    fun projectOptions_markProjectsSharingANoteWithThePerson() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(apollo), coLinkedProjects(ann))
        assertEquals(setOf(gemini), coLinkedProjects(bob))
        assertEquals(emptySet<Long>(), coLinkedProjects(cid))
    }

    @Test
    fun personProbe_emitsThePerson_thenNullOnceItIsGone() = runTest(timeout = 10.seconds) {
        db.personDao().observe(bob).test(timeout = 10.seconds) {
            assertEquals(EntityRef(bob, "Bob"), awaitItem())

            db.withTransaction { rows.deletePerson(bob) }

            assertNull(awaitItem())
        }
        assertEquals(EntityRef(ann, "Ann"), db.personDao().observe(ann).first())
    }

    @Test
    fun projectProbe_emitsTheProject_thenNullOnceItIsGone() = runTest(timeout = 10.seconds) {
        db.projectDao().observe(gemini).test(timeout = 10.seconds) {
            assertEquals(EntityRef(gemini, "Gemini"), awaitItem())

            db.withTransaction { rows.deleteProject(gemini) }

            assertNull(awaitItem())
        }
        assertEquals(EntityRef(apollo, "Apollo"), db.projectDao().observe(apollo).first())
    }

    private suspend fun coLinkedPersons(projectId: Long): Set<Long> =
        db.personDao().observeOptions(projectId).first().filter { it.coLinked }.map { it.id }.toSet()

    private suspend fun coLinkedProjects(personId: Long): Set<Long> =
        db.projectDao().observeOptions(personId).first().filter { it.coLinked }.map { it.id }.toSet()

    private fun option(id: Long, name: String, createdAt: Long, notes: Int) =
        EntityOptionRow(id, name, createdAt, noteCount = notes, coLinked = false)
}

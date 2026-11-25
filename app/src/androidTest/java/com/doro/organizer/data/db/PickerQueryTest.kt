package com.doro.organizer.data.db

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.model.EntityRef
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker queries and existence probes of `PersonDao` and `ProjectDao` (data-layer.md § Editor loads, pickers and
 * existence probes; testing.md I-61 (a)): every entity with its note count and creation time, zero-note entities
 * included (DEC-33); `observeOptions()` flags nothing, and `observeCoLinkedOptions` flags the entities linked to a
 * note, other than `exceptNoteId`, that links any of the given ids (DEC-28, DEC-111), bound as a Room collection
 * parameter of up to 900 ids. The option cases run on SQLCipher in `PLAIN` and with a raw key. A probe re-emits null
 * on the same subscription once its row is deleted, which clears a stale primary or filter (data-layer.md
 * § Reactivity, DEC-34, DEC-52).
 *
 * Dataset: n1 {Ann, Apollo}, n2 {Ann, Bob}, n3 {Bob, Gemini}, n4 {Cid, Dee, Apollo}, n5 {Dee, Gemini}; Eve and Zeus
 * have no notes.
 */
@RunWith(AndroidJUnit4::class)
class PickerQueryTest {
    @get:Rule
    val databases = TestDatabases()

    private val time = FakeAppTime()
    private val start = time.nowMillis()

    @Test
    fun personOptions_listEveryPersonWithCounts_andFlagNobody() = onEachKey { s ->
        val options = s.db.personDao().observeOptions().first().sortedBy { it.id }

        val expected = listOf(
            option(s.ann, "Ann", start, 2),
            option(s.bob, "Bob", start + 1, 2),
            option(s.cid, "Cid", start + 2, 1),
            option(s.dee, "Dee", start + 3, 2),
            option(s.eve, "Eve", start + 4, 0),
        )
        assertEquals(expected, options)
    }

    @Test
    fun projectOptions_listEveryProjectWithCounts_andFlagNothing() = onEachKey { s ->
        val options = s.db.projectDao().observeOptions().first().sortedBy { it.id }

        val expected = listOf(
            option(s.apollo, "Apollo", start + 5, 2),
            option(s.gemini, "Gemini", start + 6, 2),
            option(s.zeus, "Zeus", start + 7, 0),
        )
        assertEquals(expected, options)
    }

    @Test
    fun coLinkedPersons_areThePeopleOnANoteOfAnyGivenProject() = onEachKey { s ->
        assertEquals(setOf(s.ann, s.cid, s.dee), s.coLinkedPersons(listOf(s.apollo), null))
        assertEquals(setOf(s.bob, s.dee), s.coLinkedPersons(listOf(s.gemini), null))
        assertEquals("the union", setOf(s.ann, s.bob, s.cid, s.dee), s.coLinkedPersons(listOf(s.apollo, s.gemini), null))
        assertEquals(emptySet<Long>(), s.coLinkedPersons(listOf(s.zeus), null))
    }

    @Test
    fun coLinkedPersons_leaveOutTheGivenNote() = onEachKey { s ->
        // Cid is on n4 only; Dee is on n4 and on n5, which links Gemini.
        assertEquals(setOf(s.ann), s.coLinkedPersons(listOf(s.apollo), s.n4))
        assertEquals(setOf(s.ann, s.bob, s.dee), s.coLinkedPersons(listOf(s.apollo, s.gemini), s.n4))
        assertEquals("another note changes nothing", setOf(s.ann, s.cid, s.dee), s.coLinkedPersons(listOf(s.apollo), s.n3))
    }

    @Test
    fun coLinkedPersons_bind900ProjectIdsAndTheNote() = onEachKey { s ->
        val ids = listOf(s.apollo) + (1L..899L).map { MISSING + it }

        assertEquals(setOf(s.ann, s.cid, s.dee), s.coLinkedPersons(ids, null))
        assertEquals("901 host parameters", setOf(s.ann), s.coLinkedPersons(ids, s.n4))
    }

    @Test
    fun coLinkedProjects_mirrorThePersons() = onEachKey { s ->
        assertEquals(setOf(s.apollo), s.coLinkedProjects(listOf(s.ann), null))
        assertEquals(setOf(s.apollo, s.gemini), s.coLinkedProjects(listOf(s.ann, s.bob), null))
        assertEquals(setOf(s.apollo, s.gemini), s.coLinkedProjects(listOf(s.dee), null))
        assertEquals(setOf(s.gemini), s.coLinkedProjects(listOf(s.dee), s.n4))
        assertEquals(emptySet<Long>(), s.coLinkedProjects(listOf(s.cid), s.n4))
        assertEquals(emptySet<Long>(), s.coLinkedProjects(listOf(s.eve), null))
        val ids = listOf(s.dee) + (1L..899L).map { MISSING + it }
        assertEquals("901 host parameters", setOf(s.gemini), s.coLinkedProjects(ids, s.n4))
    }

    @Test
    fun coLinkedOptions_countLikeTheOptionsWithoutCoLinks() = onEachKey { s ->
        val plain = s.db.personDao().observeOptions().first().map { it.copy(coLinked = false) }.sortedBy { it.id }
        val coLinked = s.db.personDao().observeCoLinkedOptions(listOf(s.apollo), s.n1).first()

        assertEquals(plain, coLinked.map { it.copy(coLinked = false) }.sortedBy { it.id })
    }

    @Test
    fun personProbe_emitsThePerson_thenNullOnceItIsGone() = runTest(timeout = 10.seconds) {
        val s = seeded(TestKey.Plain)
        s.db.personDao().observe(s.bob).test(timeout = 10.seconds) {
            assertEquals(EntityRef(s.bob, "Bob"), awaitItem())

            s.db.withTransaction { s.rows.deletePerson(s.bob) }

            assertNull(awaitItem())
        }
        assertEquals(EntityRef(s.ann, "Ann"), s.db.personDao().observe(s.ann).first())
        assertDbInvariants(s.db.openHelper.writableDatabase)
    }

    @Test
    fun projectProbe_emitsTheProject_thenNullOnceItIsGone() = runTest(timeout = 10.seconds) {
        val s = seeded(TestKey.Plain)
        s.db.projectDao().observe(s.gemini).test(timeout = 10.seconds) {
            assertEquals(EntityRef(s.gemini, "Gemini"), awaitItem())

            s.db.withTransaction { s.rows.deleteProject(s.gemini) }

            assertNull(awaitItem())
        }
        assertEquals(EntityRef(s.apollo, "Apollo"), s.db.projectDao().observe(s.apollo).first())
        assertDbInvariants(s.db.openHelper.writableDatabase)
    }

    /** Runs [check] on the dataset in a `PLAIN` database, then in one keyed with a raw key (testing.md I-61 (a)). */
    private fun onEachKey(check: suspend (Seeded) -> Unit) = runTest(timeout = 30.seconds) {
        listOf(TestKey.Plain, TestKey.Raw(ByteArray(32) { (it * 13 + 5).toByte() })).forEach { key ->
            val s = seeded(key)
            check(s)
            assertDbInvariants(s.db.openHelper.writableDatabase)
        }
    }

    private fun seeded(key: TestKey): Seeded = Seeded(databases.create(key), start)

    /** The dataset of this class in [db]. */
    private class Seeded(val db: AppDatabase, private val start: Long) {
        val rows = TestRows(db.openHelper.writableDatabase)
        val ann = rows.person("Ann", start)
        val bob = rows.person("Bob", start + 1)
        val cid = rows.person("Cid", start + 2)
        val dee = rows.person("Dee", start + 3)
        val eve = rows.person("Eve", start + 4)
        val apollo = rows.project("Apollo", start + 5)
        val gemini = rows.project("Gemini", start + 6)
        val zeus = rows.project("Zeus", start + 7)
        val n1 = note(persons = listOf(ann), projects = listOf(apollo))
        val n2 = note(persons = listOf(ann, bob), projects = emptyList())
        val n3 = note(persons = listOf(bob), projects = listOf(gemini))
        val n4 = note(persons = listOf(cid, dee), projects = listOf(apollo))
        val n5 = note(persons = listOf(dee), projects = listOf(gemini))

        suspend fun coLinkedPersons(projectIds: List<Long>, exceptNoteId: Long?): Set<Long> =
            flagged(db.personDao().observeCoLinkedOptions(projectIds, exceptNoteId).first())

        suspend fun coLinkedProjects(personIds: List<Long>, exceptNoteId: Long?): Set<Long> =
            flagged(db.projectDao().observeCoLinkedOptions(personIds, exceptNoteId).first())

        private fun flagged(options: List<EntityOptionRow>): Set<Long> = options.filter { it.coLinked }.mapTo(HashSet()) { it.id }

        private fun note(persons: List<Long>, projects: List<Long>): Long = rows.note("n", createdAt = start).also { id ->
            persons.forEach { rows.linkPerson(id, it) }
            projects.forEach { rows.linkProject(id, it) }
        }
    }

    private fun option(id: Long, name: String, createdAt: Long, notes: Int) =
        EntityOptionRow(id, name, createdAt, noteCount = notes, coLinked = false)

    private companion object {
        /** Ids that are no entity: they pad a list of bound ids. */
        const val MISSING = 1_000_000L
    }
}

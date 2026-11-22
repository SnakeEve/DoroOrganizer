package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteRow
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.saved
import com.doro.organizer.testing.rowUpdates
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-57 (e), DEC-106 (b): the list rows carry link ids and `NoteRepository.observeRows` names them from one
 * map per kind, read by separate Room flows that re-run in any order after a commit.
 *
 * The first test forces each order: the real DAO flows reach [linkedNoteRows] through a [Valve] each, and after every
 * commit the test lets the rows or a name map re-run first and checks the list after each step. It fails when an
 * unresolved id is dropped instead of holding the list back. The second test runs the whole repository path in
 * whatever order the device gives: every emission must be the list of a committed state, with every chip under its
 * current name, but it can check only the orders that happen to occur.
 */
@RunWith(AndroidJUnit4::class)
class RowNamesTest {
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

    /**
     * After every commit one input re-runs first and the other waits, in both orders, for a save that creates and
     * links a person and for a delete of a linked person, then for a delete of the project every note links. When the
     * rows name an id that the names map lacks (save, rows first) or the map lost an entity the rows still link
     * (delete, names first), nothing is emitted until the other input catches up; otherwise the list of the new state
     * comes at once. Composed as `NoteRepository.observeRows` composes it, on one database instance, without the
     * observe block and the dispatcher, which only route it.
     */
    @Test
    fun eachOrderOfTheRowsAndTheNameMaps_emitsOnlyTheListsOfCommittedStates() = runTest(timeout = 60.seconds) {
        val ann = EntityRef(rows.person("Ann", now), "Ann")
        val apollo = EntityRef(rows.project("Apollo", now), "Apollo")
        val kept = rows.note("kept", createdAt = now)
        rows.linkPerson(kept, ann.id)
        rows.linkProject(kept, apollo.id)
        var state = mapOf(kept to Links(listOf(ann), listOf(apollo)))
        val noteRows = Valve(backgroundScope, db.noteDao().rowUpdates(ALL_NOTES))
        val personNames = Valve(backgroundScope, db.personDao().observeNames())
        val projectNames = Valve(backgroundScope, db.projectDao().observeNames())
        val emitted = mutableListOf<Map<Long, Links>>()
        backgroundScope.launch {
            linkedNoteRows(noteRows.flow, personNames.flow, projectNames.flow).distinctUntilChanged()
                .collect { emitted += it.links() }
        }

        noteRows.passNext()
        personNames.passNext()
        runCurrent()
        assertEquals("the first list waits for every input", emptyList<Map<Long, Links>>(), emitted)
        projectNames.passNext()
        runCurrent()
        assertEquals(listOf(state), emitted)

        for (rowsFirstOnSave in listOf(true, false)) {
            val round = if (rowsFirstOnSave) "rows first on save" else "names first on save"
            val name = "Person $round"
            val saved = notes.save(
                draft(
                    content = round,
                    personIds = setOf(ann.id),
                    projectIds = setOf(apollo.id),
                    newPersons = listOf(NewEntity("k", name)),
                ),
            ).saved()
            val created = EntityRef(checkNotNull(saved.createdPersons["k"]), name)
            val afterSave = state + (saved.noteId to Links(listOf(ann, created), listOf(apollo)))
            if (rowsFirstOnSave) {
                // The rows link the new person before the names map has it: nothing until the map catches up.
                inOrder(noteRows, personNames, emitted, afterFirst = null, end = afterSave, "$round, save")
            } else {
                // The old rows resolve with the new map: the same list, dropped as equal.
                inOrder(personNames, noteRows, emitted, afterFirst = null, end = afterSave, "$round, save")
            }

            entities.deletePerson(created.id).ok()
            val afterDelete = afterSave + (saved.noteId to Links(listOf(ann), listOf(apollo)))
            if (rowsFirstOnSave) {
                // The names map lost the person that the old rows still link: nothing until the rows catch up.
                inOrder(personNames, noteRows, emitted, afterFirst = null, end = afterDelete, "$round, delete")
            } else {
                // The new rows resolve with the old map, which still has the person: the new list at once.
                inOrder(noteRows, personNames, emitted, afterFirst = afterDelete, end = afterDelete, "$round, delete")
            }
            state = afterDelete
        }

        entities.deleteProject(apollo.id).ok()
        val afterProject = state.mapValues { (_, links) -> links.copy(projects = emptyList()) }
        inOrder(projectNames, noteRows, emitted, afterFirst = null, end = afterProject, "project delete, names first")
    }

    /**
     * Lets [first] re-run, then [second], and checks the emissions after each: [afterFirst] is the list expected after
     * [first] alone, null for none; [end] is the only new list once both have re-run.
     */
    private suspend fun TestScope.inOrder(
        first: Valve<*>,
        second: Valve<*>,
        emitted: List<Map<Long, Links>>,
        afterFirst: Map<Long, Links>?,
        end: Map<Long, Links>,
        step: String,
    ) {
        val before = emitted.size
        first.passNext()
        runCurrent()
        if (afterFirst == null) {
            assertEquals("$step: emitted ${emitted.drop(before)} before every input caught up", before, emitted.size)
        } else {
            assertEquals("$step: after the first input", listOf(afterFirst), emitted.drop(before))
        }
        second.passNext()
        runCurrent()
        assertEquals("$step: after both inputs", listOf(end), emitted.drop(before))
    }

    @Test
    fun aSaveThatCreatesAPerson_andADeleteOfALinkedPerson_neverListALinkWithoutItsName() =
        runTest(timeout = 60.seconds) {
            val ann = EntityRef(rows.person("Ann", now), "Ann")
            val apollo = EntityRef(rows.project("Apollo", now), "Apollo")
            val kept = rows.note("kept", createdAt = now)
            rows.linkPerson(kept, ann.id)
            rows.linkProject(kept, apollo.id)
            var state = mapOf(kept to Links(listOf(ann), listOf(apollo)))

            notes.observeRows(ALL_NOTES).test {
                assertEquals(state, awaitItem().links())

                repeat(ROUNDS) { round ->
                    val name = "Person $round"
                    // (1) One save creates a person and the note that links it, together with Ann and Apollo.
                    val saved = notes.save(
                        draft(
                            content = "note $round",
                            personIds = setOf(ann.id),
                            projectIds = setOf(apollo.id),
                            newPersons = listOf(NewEntity("k$round", name)),
                        ),
                    ).saved()
                    val created = EntityRef(checkNotNull(saved.createdPersons["k$round"]), name)
                    val afterSave = state + (saved.noteId to Links(listOf(ann, created), listOf(apollo)))
                    awaitState(from = state, to = afterSave, "round $round, save")

                    // (2) Deleting the linked person removes its chip from that note only.
                    entities.deletePerson(created.id).ok()
                    val afterDelete = afterSave + (saved.noteId to Links(listOf(ann), listOf(apollo)))
                    awaitState(from = afterSave, to = afterDelete, "round $round, delete")
                    state = afterDelete
                }

                // (3) Deleting the project every note links removes its chip everywhere at once.
                entities.deleteProject(apollo.id).ok()
                val afterProject = state.mapValues { (_, links) -> links.copy(projects = emptyList()) }
                awaitState(from = state, to = afterProject, "project delete")
            }
        }

    /**
     * Reads emissions until one equals [to]. Each one must equal [from] (the state before the write, which an input
     * that has not re-run yet may still give) or [to]; any other list has a missing, extra or misnamed chip.
     */
    private suspend fun ReceiveTurbine<List<NoteRow>>.awaitState(
        from: Map<Long, Links>,
        to: Map<Long, Links>,
        step: String,
    ) {
        while (true) {
            val emitted = awaitItem().links()
            assertTrue("$step: an inconsistent list $emitted", emitted == from || emitted == to)
            if (emitted == to) return
        }
    }

    /** The chips of each row, as the list shows them: sorted by name (all names differ here). */
    private fun List<NoteRow>.links(): Map<Long, Links> = associate { it.id to Links(it.persons, it.projects) }

    private data class Links(val persons: List<EntityRef>, val projects: List<EntityRef>)

    /**
     * Holds back one input of [linkedNoteRows]: collects [upstream] in [scope] and lets a value through only on
     * [passNext], so the test decides which input re-runs first after a commit.
     */
    private class Valve<T>(scope: CoroutineScope, upstream: Flow<T>) {
        private val arrived = Channel<T>(Channel.UNLIMITED)
        private val released = MutableSharedFlow<T>(replay = 1)
        private var last: Any? = NOTHING
        val flow: Flow<T> = released

        init {
            scope.launch { upstream.collect { arrived.send(it) } }
        }

        /** Lets through the next value that differs from the last one let through: Room may repeat an equal result. */
        suspend fun passNext() {
            while (true) {
                val value = arrived.receive()
                if (value != last) {
                    last = value
                    released.emit(value)
                    return
                }
            }
        }

        private companion object {
            val NOTHING = Any()
        }
    }

    private companion object {
        const val ROUNDS = 10
    }
}

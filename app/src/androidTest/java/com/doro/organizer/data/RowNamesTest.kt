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
import kotlin.time.Duration.Companion.seconds
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
 * map per kind, read by separate Room flows that re-run in any order after a commit. Every emission is checked: each
 * one is the list of a committed state, with every chip present under its current name, never a link without its
 * chip and never the chip of a deleted entity; the last one is the state after the write. The writes repeat, so both
 * orders of the rows and the name maps are likely to occur on the device (the JVM `LinkedNoteRowsTest` drives each
 * order by hand).
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

    private companion object {
        const val ROUNDS = 10
    }
}

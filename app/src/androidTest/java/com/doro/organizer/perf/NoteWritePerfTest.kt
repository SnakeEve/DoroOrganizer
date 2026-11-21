package com.doro.organizer.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.PerfBudget
import com.doro.organizer.testing.PerfRecorder
import com.doro.organizer.testing.PerfStatistic
import com.doro.organizer.testing.PerfTest
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.SeedModel
import com.doro.organizer.testing.SeedNote
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.seed
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md P-3 and P-7 on the 10,000-note seed, through the production `NoteRepository`: a check until the All list
 * re-emits (recorded only), and the batch actions on 1,000 notes, each one transaction (DEC-36), within 1,000 ms
 * median (architecture.md § Performance budgets).
 */
@PerfTest
@RunWith(AndroidJUnit4::class)
class NoteWritePerfTest {
    @get:Rule(order = -1)
    val perf = PerfRecorder()

    @get:Rule(order = 0)
    val databases = TestDatabases()

    @get:Rule(order = 1)
    val applicationScope = ApplicationScopeRule()

    private lateinit var db: AppDatabase
    private lateinit var model: SeedModel
    private lateinit var notes: NoteRepository

    @Before
    fun seedDatabase() {
        val holder = databases.holder()
        db = holder.openDatabase()
        model = db.seed()
        notes = noteRepository(holder, FakeAppTime(), applicationScope)
    }

    @Test
    fun checkInAll_reemitsTheWholeList() = runTest(timeout = 300.seconds) {
        notes.observeRows(ALL_NOTES).test(timeout = 60.seconds) {
            val rows = awaitItem()
            val target = rows.first().id
            check(!rows.first().checked) { "the top row of All is unchecked" }
            // Every measured run is a check (DEC-22); the uncheck that undoes it is a different write and not measured.
            var checked = false
            suspend fun setAndAwait(value: Boolean) {
                assertEquals(true, notes.setChecked(target, value).ok())
                do {
                    val row = awaitItem().single { it.id == target }
                } while (row.checked != value)
                checked = value
            }
            perf.measure(
                "Check in All until the list re-emits",
                budget = null,
                prepare = { if (checked) setAndAwait(false) },
            ) {
                setAndAwait(true)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun batchActions_onAThousandNotes_stayWithinBudget() = runTest(timeout = 300.seconds) {
        val batch = model.notes.take(BATCH)
        val ids = batch.map { it.id }

        // Each run links new entities and sets a new date, so every run writes every note again. Only the repository
        // call is measured: the entities of a run and the notes they change are worked out before it.
        var run = 0L
        var persons = emptySet<Long>()
        var projects = emptySet<Long>()
        var gaining = 0
        perf.measure(
            "Associate 2 persons and 1 project",
            BATCH_BUDGET,
            prepare = {
                val first = FIRST_FREE_ENTITY + 2 * run++
                persons = setOf(first, first + 1)
                projects = setOf(first)
                gaining = batch.count { !it.personIds.containsAll(persons) || first !in it.projectIds }
            },
        ) {
            assertEquals(gaining, notes.associate(ids, persons, projects, emptyList(), emptyList()).ok().changed)
        }
        run = 0L
        perf.measure("Set date", BATCH_BUDGET) {
            assertEquals(BATCH, notes.setDate(ids, SeedGenerator.BASE_DAY + YEAR_AHEAD + run++).ok().changed)
        }
        var deleted = false
        perf.measure("Delete", BATCH_BUDGET, prepare = { if (deleted) reinsert(batch) }) {
            assertEquals(BATCH, notes.deleteNotes(ids).ok().changed)
            deleted = true
        }
    }

    /** The deleted notes again, with their seeded fields and links; not measured. */
    private fun reinsert(batch: List<SeedNote>) {
        db.openHelper.writableDatabase.seed(SeedModel(batch, persons = emptyList(), projects = emptyList()))
    }

    private companion object {
        const val BATCH = 1_000
        const val YEAR_AHEAD = 400L

        /** Entities above the busy ones, with room for 13 runs of 2 persons (300 persons, 100 projects). */
        const val FIRST_FREE_ENTITY = 50L
        val BATCH_BUDGET = PerfBudget(1_000, PerfStatistic.Median)
    }
}

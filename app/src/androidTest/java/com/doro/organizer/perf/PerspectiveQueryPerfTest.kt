package com.doro.organizer.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.NoteOracle
import com.doro.organizer.testing.PerfBudget
import com.doro.organizer.testing.PerfRecorder
import com.doro.organizer.testing.PerfStatistic
import com.doro.organizer.testing.PerfTest
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.SeedModel
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.markedDays
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.rowUpdates
import com.doro.organizer.testing.rows
import com.doro.organizer.testing.seed
import com.doro.organizer.testing.selectedIds
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md P-1: every perspective query on the 10,000-note seed, from subscribing to the first Flow emission, within
 * 100 ms p90 (architecture.md § Performance budgets). The calendar month marks, the Select-all ids and the
 * delete-impact counts of the 2,000-note person are recorded without a budget.
 */
@PerfTest
@RunWith(AndroidJUnit4::class)
class PerspectiveQueryPerfTest {
    @get:Rule(order = -1)
    val perf = PerfRecorder()

    @get:Rule(order = 0)
    val databases = TestDatabases()

    @get:Rule(order = 1)
    val applicationScope = ApplicationScopeRule()

    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var model: SeedModel
    private lateinit var entities: EntityRepository

    @Before
    fun seedDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        model = db.seed()
        entities = entityRepository(holder, FakeAppTime(), applicationScope)
    }

    @Test
    fun perspectiveQueries_emitFirstWithinBudget() = runTest(timeout = 300.seconds) {
        val busiestDay = model.notes.mapNotNull { it.date }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val lists = listOf(
            "All" to NoteFilter(Perspective.All),
            "Individual: the 2,000-note person" to PERSON_FILTER,
            "Project: the 1,500-note project" to PROJECT_FILTER,
            "Date: the busiest day" to dateFilter(busiestDay, busiestDay),
            "Date: one year" to dateFilter(SeedGenerator.BASE_DAY - 364, SeedGenerator.BASE_DAY),
            "Orphan" to NoteFilter(Perspective.Orphan),
            "Search: a common three-letter term" to NoteFilter(Perspective.All, search = COMMON_TERM),
            "Search: a miss" to NoteFilter(Perspective.All, search = MISSING_TERM),
        )

        // Room installs its table triggers for the first observer of a table and drops them after the last one. An
        // observer of the list tables for the whole test keeps them, so each run times the query and its first
        // emission, not the trigger setup (P-2 times a whole tab switch).
        val watching = CompletableDeferred<Unit>()
        backgroundScope.launch { db.noteDao().rowUpdates().collect { watching.complete(Unit) } }
        watching.await()

        // Never vacuous: the search term hits many notes and the miss none, and each query returns the reference list.
        assertTrue(NoteOracle.listIds(model, NoteFilter(Perspective.All, search = COMMON_TERM)).size > 100)
        assertTrue(NoteOracle.listIds(model, NoteFilter(Perspective.All, search = MISSING_TERM)).isEmpty())
        lists.forEach { (label, filter) ->
            val query = checkNotNull(filter.toQuery())
            assertEquals(label, NoteOracle.listIds(model, filter), db.noteDao().rows(query).map { it.id })
            perf.measure(label, LIST_QUERY_BUDGET) { db.noteDao().rows(query) }
        }
        recordTheRest()
    }

    private suspend fun recordTheRest() {
        val month = LocalDate.ofEpochDay(SeedGenerator.BASE_DAY).withDayOfMonth(1)
        val monthDays = month.toEpochDay() to month.plusMonths(1).toEpochDay() - 1
        val personQuery: NoteQuery = checkNotNull(PERSON_FILTER.toQuery())

        perf.measure("Calendar month marks", budget = null) {
            db.noteDao().markedDays(monthDays.first, monthDays.second, ALL_NOTES)
        }
        perf.measure("Select all ids: All", budget = null) { db.noteDao().selectedIds(ALL_NOTES) }
        perf.measure("Select all ids: the 2,000-note person", budget = null) { db.noteDao().selectedIds(personQuery) }
        perf.measure("Delete impact: the 2,000-note person", budget = null) {
            entities.personDeleteImpact(SeedGenerator.HEAVY_PERSON).ok()
        }
    }

    private fun dateFilter(fromDay: Long, toDay: Long) =
        NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(fromDay, toDay))

    private companion object {
        val PERSON_FILTER = NoteFilter(Perspective.Individual, primaryId = SeedGenerator.HEAVY_PERSON)
        val PROJECT_FILTER = NoteFilter(Perspective.Project, primaryId = SeedGenerator.HEAVY_PROJECT)
        const val COMMON_TERM = "get"
        const val MISSING_TERM = "qxz"
        val LIST_QUERY_BUDGET = PerfBudget(100, PerfStatistic.P90)
    }
}

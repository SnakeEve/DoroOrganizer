package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.listedIds
import com.doro.organizer.testing.markedDays
import com.doro.organizer.testing.selectedIds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Filter semantics of the perspective query (DEC-28, testing.md I-16), Select all (`selectIds`, DEC-36) and the
 * calendar marks (`observeMarkedDays`, DEC-53), on a small dataset whose expected answers are written out:
 *
 * | note | links | date | checked | content |
 * |---|---|---|---|---|
 * | n1 | P, X | D | no | "alpha" |
 * | n2 | P | D + 1 | no | "beta" |
 * | n3 | Q | none | no | "alpha" |
 * | n4 | none | D − 1 | no | "gamma" |
 * | n5 | none | none | yes | "alpha" |
 * | n6 | X | D + 2 | yes | "beta" |
 */
@RunWith(AndroidJUnit4::class)
class NoteQueryTest {
    @get:Rule
    val databases = TestDatabases()

    private val now = FakeAppTime().nowMillis()
    private lateinit var db: AppDatabase
    private val dao get() = db.noteDao()
    private var p = 0L
    private var q = 0L
    private var x = 0L
    private var n1 = 0L
    private var n2 = 0L
    private var n3 = 0L
    private var n4 = 0L
    private var n5 = 0L
    private var n6 = 0L

    @Before
    fun seed() {
        db = databases.create()
        val rows = TestRows(db.openHelper.writableDatabase)
        p = rows.person("P", now)
        q = rows.person("Q", now)
        x = rows.project("X", now)
        // Created oldest first, all unchecked ones at the same instant: the list order is n4, n3, n2, n1, then n5, n6.
        n1 = rows.note("alpha", createdAt = now, date = D)
        n2 = rows.note("beta", createdAt = now, date = D + 1)
        n3 = rows.note("alpha", createdAt = now)
        n4 = rows.note("gamma", createdAt = now, date = D - 1)
        n5 = rows.note("alpha", createdAt = now, checkedAt = now)
        n6 = rows.note("beta", createdAt = now, checkedAt = now + 1, date = D + 2)
        rows.linkPerson(n1, p)
        rows.linkProject(n1, x)
        rows.linkPerson(n2, p)
        rows.linkPerson(n3, q)
        rows.linkProject(n6, x)
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun noFilter_listsEveryNote() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n4, n3, n2, n1, n5, n6), listed(NoteFilter(Perspective.All)))
    }

    @Test
    fun dimensions_combineWithAnd() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n1), listed(NoteFilter(Perspective.All, personId = p, projectId = x)))
        val fromNextDay = DateFilter.Range(D + 1, null)
        assertEquals(listOf(n2), listed(NoteFilter(Perspective.All, personId = p, date = fromNextDay)))
        assertEquals(listOf(n1), listed(NoteFilter(Perspective.Individual, primaryId = p, search = "ALPHA")))
        val days = DateFilter.Range(D - 1, D + 2)
        val dateFilter = NoteFilter(Perspective.Date, primaryDays = days, projectId = x, search = "alp")
        assertEquals(listOf(n1), listed(dateFilter)) // n6 is linked to X in range too, but its "beta" misses
        assertEquals(emptyList<Long>(), listed(NoteFilter(Perspective.Project, primaryId = x, personId = q)))
    }

    @Test
    fun primarySelections() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n2, n1), listed(NoteFilter(Perspective.Individual, primaryId = p)))
        assertEquals(listOf(n1, n6), listed(NoteFilter(Perspective.Project, primaryId = x)))
        assertEquals(listOf(n1), listed(NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(D, D))))
    }

    @Test
    fun orphan_listsNotesWithoutAnyLink() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n4, n5), listed(NoteFilter(Perspective.Orphan)))
        assertEquals(listOf(n5), listed(NoteFilter(Perspective.Orphan, date = DateFilter.Undated)))
    }

    @Test
    fun range_isInclusiveAtBothEnds() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n2, n1), listed(NoteFilter(Perspective.All, date = DateFilter.Range(D, D + 1))))
    }

    @Test
    fun range_eitherEndMayBeOpen() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n4, n1), listed(NoteFilter(Perspective.All, date = DateFilter.Range(null, D))))
        assertEquals(listOf(n2, n1, n6), listed(NoteFilter(Perspective.All, date = DateFilter.Range(D, null))))
    }

    @Test
    fun openRange_excludesUndatedNotes_unlikeAny() = runTest(timeout = 10.seconds) {
        val dated = listOf(n4, n2, n1, n6)

        assertEquals(dated, listed(NoteFilter(Perspective.All, date = DateFilter.Range(null, null))))
        assertEquals(listOf(n4, n3, n2, n1, n5, n6), listed(NoteFilter(Perspective.All, date = DateFilter.Any)))
    }

    @Test
    fun undated_meansDateIsNull() = runTest(timeout = 10.seconds) {
        assertEquals(listOf(n3, n5), listed(NoteFilter(Perspective.All, date = DateFilter.Undated)))
    }

    @Test
    fun selectIds_coversEveryListedRow_checkedIncluded() = runTest(timeout = 10.seconds) {
        val filters = listOf(
            NoteFilter(Perspective.All),
            NoteFilter(Perspective.Individual, primaryId = p),
            NoteFilter(Perspective.Project, primaryId = x),
            NoteFilter(Perspective.Orphan),
            NoteFilter(Perspective.All, date = DateFilter.Undated, search = "alpha"),
            NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(D, D + 2)),
        )

        filters.forEach { filter ->
            val ids = dao.selectedIds(filter.toQuery()!!)
            assertEquals(filter.toString(), listed(filter).sorted(), ids.sorted())
        }
    }

    @Test
    fun markedDays_countCheckedNotes_neverUndated() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(D - 1, D, D + 1, D + 2), marked(NoteFilter(Perspective.Date, primaryDays = DAY)))
    }

    @Test
    fun markedDays_followPersonProjectAndSearch() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(D, D + 1), marked(NoteFilter(Perspective.Date, primaryDays = DAY, personId = p)))
        assertEquals(setOf(D, D + 2), marked(NoteFilter(Perspective.Date, primaryDays = DAY, projectId = x)))
        assertEquals(setOf(D + 1, D + 2), marked(NoteFilter(Perspective.Date, primaryDays = DAY, search = "BETA")))
    }

    @Test
    fun markedDays_stayInsideTheMonthBounds() = runTest(timeout = 10.seconds) {
        val query = NoteFilter(Perspective.Date, primaryDays = DAY).toQuery()!!

        assertEquals(setOf(D, D + 1), dao.markedDays(D, D + 1, query))
    }

    private suspend fun listed(filter: NoteFilter): List<Long> = dao.listedIds(filter.toQuery()!!)

    /** The marks of a month around D, as `NoteRepository.observeMarkedDays` passes the Date perspective's query. */
    private suspend fun marked(filter: NoteFilter): Set<Long> = dao.markedDays(D - 20, D + 20, filter.toQuery()!!)

    private companion object {
        /** 2026-11-17. */
        const val D = 20_774L
        val DAY = DateFilter.Range(D, D)
    }
}

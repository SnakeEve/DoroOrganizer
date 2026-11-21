package com.doro.organizer.data

import app.cash.turbine.test
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `NoteRepository.observeMarkedDays` (DEC-53, data-layer.md § Perspective query item 6, testing.md I-32): the days of
 * the displayed month with a note that matches the Date perspective's person, project and search; the query's own
 * day or range never limits the marks; checked notes count and undated notes never do.
 */
@RunWith(AndroidJUnit4::class)
class MarkedDaysTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private lateinit var holder: DatabaseHolder
    private lateinit var rows: TestRows
    private lateinit var notes: NoteRepository
    private var person = 0L
    private var project = 0L

    @Before
    fun seed() {
        holder = databases.holder()
        rows = TestRows(holder.openDatabase().openHelper.writableDatabase)
        notes = noteRepository(holder, time, applicationScope)
        person = rows.person("P", now)
        project = rows.project("X", now)
        rows.linkPerson(rows.note("alpha", createdAt = now, date = FIRST), person)
        rows.linkProject(rows.note("beta", createdAt = now, checkedAt = now, date = FIRST + 4), project)
        rows.note("alpha", createdAt = now)
        rows.note("gamma", createdAt = now, date = FIRST - 1)
        rows.note("gamma", createdAt = now, date = LAST + 1)
    }

    @Test
    fun marks_coverTheMonth_whateverDayIsSelected() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(FIRST, FIRST + 4), marks(dateQuery()))
        assertEquals(setOf(FIRST - 1), notes.observeMarkedDays(dateQuery(), FIRST - 30, FIRST - 1).first())
    }

    @Test
    fun marks_followThePersonProjectAndSearch() = runTest(timeout = 10.seconds) {
        assertEquals(setOf(FIRST), marks(dateQuery(personId = person)))
        assertEquals(setOf(FIRST + 4), marks(dateQuery(projectId = project)))
        assertEquals(setOf(FIRST), marks(dateQuery(search = "ALPHA")))
        assertEquals(emptySet<Long>(), marks(dateQuery(personId = person, search = "beta")))
    }

    @Test
    fun marks_followWrites() = runTest(timeout = 10.seconds) {
        notes.observeMarkedDays(dateQuery(), FIRST, LAST).test {
            assertEquals(setOf(FIRST, FIRST + 4), awaitItem())

            notes.save(draft(content = "new", date = FIRST + 9)).saved()

            assertEquals(setOf(FIRST, FIRST + 4, FIRST + 9), awaitItem())
        }
    }

    private suspend fun marks(query: NoteQuery): Set<Long> = notes.observeMarkedDays(query, FIRST, LAST).first()

    /** The Date perspective's query on the selected day D + 10, which must not limit the marks. */
    private fun dateQuery(personId: Long? = null, projectId: Long? = null, search: String = ""): NoteQuery =
        checkNotNull(
            NoteFilter(
                Perspective.Date,
                primaryDays = DateFilter.Range(FIRST + 10, FIRST + 10),
                personId = personId,
                projectId = projectId,
                search = search,
            ).toQuery(),
        )

    private companion object {
        /** 2026-11-01. */
        const val FIRST = 20_758L

        /** 2026-11-30. */
        const val LAST = 20_787L
    }
}

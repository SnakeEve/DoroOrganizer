package com.doro.organizer.data

import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import java.util.Locale
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The consistency rule of data-layer.md § Rows (DEC-106) with hand-driven inputs: [linkedNoteRows] emits a list only
 * when every link id resolves in the name maps, waits for a lagging input otherwise, and needs every input for its
 * first list. Room re-runs each flow on its own after a commit, so on the device any of the three can arrive first.
 */
class LinkedNoteRowsTest {
    private lateinit var defaultLocale: Locale

    private val rows = input<List<NoteRowRaw>>()
    private val persons = input<List<EntityRef>>()
    private val projects = input<List<EntityRef>>()
    private val emitted = mutableListOf<List<NoteRow>>()

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun rowsFirst_withANewEntityId_emitNothingUntilTheNamesCatchUp() = runTest {
        collect()
        set(rows = listOf(raw(1, persons = "1")), persons = listOf(ANN), projects = emptyList())
        assertEmitted(listOf(row(1, ANN)))

        // A save created Bob and linked note 1; the rows re-ran first.
        rows.tryEmit(listOf(raw(1, persons = "1,2")))
        runCurrent()
        assertEmitted(listOf(row(1, ANN)))

        persons.tryEmit(listOf(ANN, BOB))
        runCurrent()
        assertEmitted(listOf(row(1, ANN)), listOf(row(1, ANN, BOB)))
    }

    @Test
    fun namesFirst_afterADelete_emitNothingUntilTheRowsCatchUp() = runTest {
        collect()
        set(
            rows = listOf(raw(1, persons = "1,2"), raw(2, projects = "9")),
            persons = listOf(ANN, BOB),
            projects = listOf(APOLLO),
        )
        assertEmitted(listOf(row(1, ANN, BOB), row(2, projects = listOf(APOLLO))))

        // Bob and Apollo were deleted with their links; the name maps re-ran first.
        persons.tryEmit(listOf(ANN))
        projects.tryEmit(emptyList())
        runCurrent()
        assertEquals(1, emitted.size)

        rows.tryEmit(listOf(raw(1, persons = "1"), raw(2)))
        runCurrent()
        assertEquals(2, emitted.size)
        assertEquals(listOf(row(1, ANN), row(2)), emitted.last())
    }

    @Test
    fun rowsFirst_afterADelete_emitTheNewRowsAtOnce() = runTest {
        collect()
        set(rows = listOf(raw(1, persons = "1,2")), persons = listOf(ANN, BOB), projects = emptyList())

        rows.tryEmit(listOf(raw(1, persons = "1")))
        runCurrent()
        // An extra entity in a map changes nothing: the new rows resolve with the old map.
        assertEmitted(listOf(row(1, ANN, BOB)), listOf(row(1, ANN)))

        persons.tryEmit(listOf(ANN))
        runCurrent()
        assertEquals(listOf(row(1, ANN)), emitted.last())
    }

    @Test
    fun aRename_reEmitsWithTheNewName() = runTest {
        collect()
        set(rows = listOf(raw(1, persons = "1,2")), persons = listOf(ANN, BOB), projects = emptyList())

        persons.tryEmit(listOf(EntityRef(1, "Zoe"), BOB))
        runCurrent()

        assertEmitted(listOf(row(1, ANN, BOB)), listOf(row(1, BOB, EntityRef(1, "Zoe"))))
    }

    @Test
    fun theFirstList_needsEveryInput() = runTest {
        collect()
        rows.tryEmit(listOf(raw(1)))
        persons.tryEmit(emptyList())
        runCurrent()
        assertEmitted()

        projects.tryEmit(emptyList())
        runCurrent()
        assertEmitted(listOf(row(1)))
    }

    @Test
    fun theFirstList_needsTheRows() = runTest {
        collect()
        persons.tryEmit(listOf(ANN))
        projects.tryEmit(emptyList())
        runCurrent()
        assertEmitted()

        rows.tryEmit(listOf(raw(1, persons = "1")))
        runCurrent()
        assertEmitted(listOf(row(1, ANN)))
    }

    @Test
    fun anEqualInput_isNotMappedAgain() = runTest {
        collect()
        set(rows = listOf(raw(1, persons = "1")), persons = listOf(ANN), projects = emptyList())

        // Room re-runs a query on every write to a table it reads, with an equal result as a new list.
        rows.tryEmit(listOf(raw(1, persons = "1")))
        runCurrent()
        persons.tryEmit(listOf(EntityRef(1, "Ann")))
        runCurrent()
        projects.tryEmit(emptyList())
        runCurrent()

        assertEmitted(listOf(row(1, ANN)))
    }

    private fun TestScope.collect() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            linkedNoteRows(rows, persons, projects).collect { emitted += it }
        }
    }

    private fun TestScope.set(rows: List<NoteRowRaw>, persons: List<EntityRef>, projects: List<EntityRef>) {
        this@LinkedNoteRowsTest.rows.tryEmit(rows)
        this@LinkedNoteRowsTest.persons.tryEmit(persons)
        this@LinkedNoteRowsTest.projects.tryEmit(projects)
        runCurrent()
    }

    private fun assertEmitted(vararg lists: List<NoteRow>) = assertEquals(lists.toList(), emitted)

    private companion object {
        val ANN = EntityRef(1, "Ann")
        val BOB = EntityRef(2, "Bob")
        val APOLLO = EntityRef(9, "Apollo")

        /** An input that keeps only its latest value, as a Room flow's consumer sees it; equal values still arrive. */
        fun <T> input() = MutableSharedFlow<T>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        fun raw(id: Long, persons: String? = null, projects: String? = null) = NoteRowRaw(
            id = id, preview = "p$id", date = null, checked = false, checkedAt = null, createdAt = 1, updatedAt = 2,
            personIds = persons, projectIds = projects,
        )

        fun row(id: Long, vararg persons: EntityRef, projects: List<EntityRef> = emptyList()) = NoteRow(
            id = id, preview = "p$id", date = null, checked = false, checkedAt = null, createdAt = 1, updatedAt = 2,
            persons = persons.toList(), projects = projects,
        )
    }
}

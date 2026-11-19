package com.doro.organizer.testing

import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The reference of testing.md I-14 itself, on hand-made notes whose answers are written out. */
class NoteOracleTest {
    private val unchecked1 = note(id = 1, updatedAt = 20, createdAt = 10)
    private val unchecked2 = note(id = 2, updatedAt = 20, createdAt = 10)
    private val unchecked3 = note(id = 3, updatedAt = 20, createdAt = 15, date = 7, persons = setOf(1))
    private val newest = note(id = 4, updatedAt = 30, createdAt = 5, date = 9, projects = setOf(1))
    private val checkedLate = note(id = 5, checkedAt = 50, content = "50% Done")
    private val checkedEarly = note(id = 6, checkedAt = 40, date = 8, persons = setOf(2))
    private val checkedTie = note(id = 7, checkedAt = 40)
    private val model = SeedModel(
        listOf(unchecked1, unchecked2, unchecked3, newest, checkedLate, checkedEarly, checkedTie),
        emptyList(),
        emptyList(),
    )

    @Test
    fun canonicalOrder() {
        assertEquals(listOf(4L, 3L, 2L, 1L, 7L, 6L, 5L), NoteOracle.listIds(model, NoteFilter(Perspective.All)))
    }

    @Test
    fun perspectivesAndFilters() {
        assertEquals(listOf(3L), ids(NoteFilter(Perspective.Individual, primaryId = 1, personId = 2)))
        assertEquals(listOf(4L), ids(NoteFilter(Perspective.Project, primaryId = 1)))
        assertEquals(listOf(2L, 1L, 7L, 5L), ids(NoteFilter(Perspective.Orphan, personId = 1, projectId = 1)))
        assertEquals(listOf(4L, 3L, 6L), ids(NoteFilter(Perspective.All, date = DateFilter.Range(null, null))))
        assertEquals(listOf(4L, 6L), ids(NoteFilter(Perspective.All, date = DateFilter.Range(9, 8))))
        assertEquals(listOf(2L, 1L, 7L, 5L), ids(NoteFilter(Perspective.All, date = DateFilter.Undated)))
        assertEquals(
            listOf(3L),
            ids(NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(7, 7), date = DateFilter.Undated)),
        )
        assertEquals(listOf(5L), ids(NoteFilter(Perspective.All, search = "  50% DONE ")))
    }

    @Test
    fun markedDays_includeCheckedNotes() {
        val filter = NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(7, 7))

        assertEquals(setOf(7L, 8L, 9L), NoteOracle.markedDays(model, filter, 1, 31))
        assertEquals(setOf(8L), NoteOracle.markedDays(model, filter.copy(personId = 2), 1, 31))
    }

    @Test
    fun markedDays_rejectAFilterOutsideTheDatePerspective() {
        assertThrows(IllegalArgumentException::class.java) {
            NoteOracle.markedDays(model, NoteFilter(Perspective.All), 1, 31)
        }
    }

    private fun ids(filter: NoteFilter) = NoteOracle.listIds(model, filter)

    private fun note(
        id: Long,
        updatedAt: Long = 1,
        createdAt: Long = 1,
        checkedAt: Long? = null,
        date: Long? = null,
        content: String = "",
        persons: Set<Long> = emptySet(),
        projects: Set<Long> = emptySet(),
    ) = SeedNote(id, date, checkedAt, createdAt, updatedAt, content, persons, projects)
}

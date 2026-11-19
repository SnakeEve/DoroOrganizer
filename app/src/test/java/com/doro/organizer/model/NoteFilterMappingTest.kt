package com.doro.organizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * testing.md I-15: [toQuery] against the table and the contract of data-layer.md § Perspective to NoteQuery mapping.
 * Each perspective test sets every dimension, so one comparison of the whole [NoteQuery] covers each cell of its row.
 */
class NoteFilterMappingTest {
    @Test
    fun all_keepsEveryDimension() {
        val query = everything(Perspective.All).toQuery()

        assertEquals(
            expected(personId = PERSON, projectId = PROJECT, fromDay = 100, toDay = 200, searchPattern = PATTERN),
            query,
        )
    }

    @Test
    fun individual_primaryTakesThePersonSlot() {
        val query = everything(Perspective.Individual).toQuery()

        assertEquals(
            expected(personId = PRIMARY, projectId = PROJECT, fromDay = 100, toDay = 200, searchPattern = PATTERN),
            query,
        )
    }

    @Test
    fun project_primaryTakesTheProjectSlot() {
        val query = everything(Perspective.Project).toQuery()

        assertEquals(
            expected(personId = PERSON, projectId = PRIMARY, fromDay = 100, toDay = 200, searchPattern = PATTERN),
            query,
        )
    }

    @Test
    fun date_primaryDaysReplaceTheDateDimension() {
        val query = everything(Perspective.Date).toQuery()

        assertEquals(
            expected(personId = PERSON, projectId = PROJECT, fromDay = 300, toDay = 310, searchPattern = PATTERN),
            query,
        )
    }

    @Test
    fun date_dropsUndatedAndRangeFilters() {
        val day = DateFilter.Range(300, 300)
        val plain = NoteFilter(Perspective.Date, primaryDays = day).toQuery()

        assertEquals(plain, NoteFilter(Perspective.Date, primaryDays = day, date = DateFilter.Undated).toQuery())
        assertEquals(plain, NoteFilter(Perspective.Date, primaryDays = day, date = DateFilter.Range(1, 2)).toQuery())
        assertEquals(expected(fromDay = 300, toDay = 300), plain)
    }

    @Test
    fun orphan_dropsPersonAndProject() {
        val query = everything(Perspective.Orphan).toQuery()

        assertEquals(
            expected(orphanOnly = true, fromDay = 100, toDay = 200, searchPattern = PATTERN),
            query,
        )
    }

    @Test
    fun individualAndProject_withoutPrimary_giveNoQuery() {
        assertNull(NoteFilter(Perspective.Individual, personId = PERSON).toQuery())
        assertNull(NoteFilter(Perspective.Project, projectId = PROJECT).toQuery())
    }

    @Test
    fun date_withoutPrimaryDays_throws() {
        assertThrows(IllegalArgumentException::class.java) { NoteFilter(Perspective.Date).toQuery() }
    }

    @Test
    fun date_withOpenEnd_throws() {
        listOf(DateFilter.Range(null, 5), DateFilter.Range(5, null), DateFilter.Range(null, null)).forEach { days ->
            assertThrows(days.toString(), IllegalArgumentException::class.java) {
                NoteFilter(Perspective.Date, primaryDays = days).toQuery()
            }
        }
    }

    @Test
    fun openRange_mapsToEpochDayBounds_unlikeAny() {
        val open = NoteFilter(Perspective.All, date = DateFilter.Range(null, null)).toQuery()
        val any = NoteFilter(Perspective.All, date = DateFilter.Any).toQuery()

        assertEquals(expected(fromDay = EpochDays.MIN, toDay = EpochDays.MAX), open)
        assertEquals(expected(), any)
        assertNotEquals(any, open)
    }

    @Test
    fun halfOpenRange_closesTheOpenEndAtTheEpochDayBound() {
        assertEquals(
            expected(fromDay = EpochDays.MIN, toDay = 50),
            NoteFilter(Perspective.All, date = DateFilter.Range(null, 50)).toQuery(),
        )
        assertEquals(
            expected(fromDay = 50, toDay = EpochDays.MAX),
            NoteFilter(Perspective.All, date = DateFilter.Range(50, null)).toQuery(),
        )
    }

    @Test
    fun reversedRange_isSwapped() {
        assertEquals(
            expected(fromDay = 10, toDay = 20),
            NoteFilter(Perspective.All, date = DateFilter.Range(20, 10)).toQuery(),
        )
        assertEquals(
            expected(fromDay = 10, toDay = 20),
            NoteFilter(Perspective.Date, primaryDays = DateFilter.Range(20, 10)).toQuery(),
        )
    }

    @Test
    fun undated_givesUndatedOnlyWithoutBounds() {
        assertEquals(expected(undatedOnly = true), NoteFilter(Perspective.All, date = DateFilter.Undated).toQuery())
    }

    @Test
    fun blankSearch_givesNoPattern() {
        listOf("", "   ", "\n\t").forEach { search ->
            assertNull(NoteFilter(Perspective.All, search = search).toQuery()?.searchPattern)
        }
    }

    @Test
    fun equalFilters_giveEqualQueries() {
        Perspective.entries.forEach { perspective ->
            assertEquals(everything(perspective).toQuery(), everything(perspective).toQuery())
        }
    }

    /** A filter of [perspective] with every dimension set, so a dropped one would show in the query. */
    private fun everything(perspective: Perspective) = NoteFilter(
        perspective = perspective,
        primaryId = PRIMARY,
        primaryDays = DateFilter.Range(300, 310),
        personId = PERSON,
        projectId = PROJECT,
        date = DateFilter.Range(100, 200),
        search = SEARCH,
    )

    private fun expected(
        personId: Long? = null,
        projectId: Long? = null,
        orphanOnly: Boolean = false,
        undatedOnly: Boolean = false,
        fromDay: Long? = null,
        toDay: Long? = null,
        searchPattern: String? = null,
    ) = NoteQuery(personId, projectId, orphanOnly, undatedOnly, fromDay, toDay, searchPattern)

    private companion object {
        const val PRIMARY = 1L
        const val PERSON = 11L
        const val PROJECT = 22L
        const val SEARCH = " Ab_ "
        const val PATTERN = "ab\\_" // trimmed, folded and escaped (SearchText)
    }
}

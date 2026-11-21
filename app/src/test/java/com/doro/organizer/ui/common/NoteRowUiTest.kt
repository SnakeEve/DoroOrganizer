package com.doro.organizer.ui.common

import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.NoteRow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row anatomy of ui.md § 3.7: title and body lines (DEC-39), "(No data)" (DEC-25), Overdue (DEC-26), summaries. */
class NoteRowUiTest {
    private val today = LocalDate.of(2026, 11, 17)

    @Test
    fun title_isTheFirstNonBlankLine_andBodyTheFurtherOnes_eachTrimmed() {
        val ui = row(preview = "Weekly sync \n\n\t  budget\r\n 　 \r hiring  \nlast").toRowUi(today)

        assertEquals("Weekly sync", ui.title)
        assertEquals("budget\nhiring\nlast", ui.body)
    }

    @Test
    fun aSingleLine_hasNoBody() {
        assertNull(row(preview = "only").toRowUi(today).body)
    }

    @Test
    fun blankContent_hasNoTitle_soTheRowShowsNoData() {
        for (blank in listOf("", " ", "\n\t 　")) {
            val ui = row(preview = blank).toRowUi(today)
            assertNull("'$blank'", ui.title)
            assertNull(ui.body)
        }
    }

    @Test
    fun lines_followTheAppsWhitespaceRule_notTheRuntimes() {
        // U+180E is not whitespace by TextWhitespace (DEC-24), though ART's Char.isWhitespace says it is. On the JVM
        // Kotlin's rule agrees with TextWhitespace, so this pins the result only; ArchitectureTest's WhitespaceRule
        // forbids the runtime's rule in production code, and NoteRowUiOnArtTest repeats this case on ART.
        val ui = row(preview = "᠎").toRowUi(today)

        assertEquals("᠎", ui.title)
    }

    @Test
    fun overdue_isUncheckedDatedAndBeforeToday() {
        val yesterday = today.minusDays(1).toEpochDay()

        assertTrue(row(date = yesterday).toRowUi(today).overdue)
        assertFalse(row(date = today.toEpochDay()).toRowUi(today).overdue)
        assertFalse(row(date = yesterday, checked = true).toRowUi(today).overdue)
        assertFalse(row(date = null).toRowUi(today).overdue)
        assertEquals(LocalDate.ofEpochDay(yesterday), row(date = yesterday).toRowUi(today).date)
    }

    @Test
    fun summaries_omitThePrimaryEntity_andKeepTheRowOrder() {
        val persons = listOf(EntityRef(3, "Bob"), EntityRef(1, "Alice"), EntityRef(2, "Alice"))
        val projects = listOf(EntityRef(7, "Alpha"), EntityRef(8, "Beta"))

        val all = row(persons = persons, projects = projects).toRowUi(today)
        val individual = row(persons = persons, projects = projects).toRowUi(today, omitPersonId = 1)
        val project = row(persons = persons, projects = projects).toRowUi(today, omitProjectId = 8)

        assertEquals(listOf("Bob", "Alice", "Alice"), all.people)
        assertEquals(listOf("Bob", "Alice"), individual.people)
        assertEquals(listOf("Alpha", "Beta"), individual.projects)
        assertEquals(listOf("Alpha"), project.projects)
    }

    @Test
    fun orphan_isDerivedFromTheLinks_notFromWhatTheSummaryShows() {
        assertTrue(row().toRowUi(today).orphan)
        val onlyPrimary = row(persons = listOf(EntityRef(1, "Alice"))).toRowUi(today, omitPersonId = 1)
        assertFalse(onlyPrimary.orphan)
        assertTrue(onlyPrimary.people.isEmpty())
    }

    private fun row(
        preview: String = "note",
        date: Long? = null,
        checked: Boolean = false,
        persons: List<EntityRef> = emptyList(),
        projects: List<EntityRef> = emptyList(),
    ) = NoteRow(
        id = 1,
        preview = preview,
        date = date,
        checked = checked,
        checkedAt = if (checked) 1L else null,
        createdAt = 1,
        updatedAt = 1,
        persons = persons,
        projects = projects,
    )
}

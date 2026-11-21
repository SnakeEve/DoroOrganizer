package com.doro.organizer.ui.notes

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-32 (DEC-53, ui.md § 4.4): the Calendar opens on the current month with today selected; a day is marked
 * exactly when a note dated that day matches the search (checked notes count, undated never), and a day tap lists
 * that day's notes. The person and project filters of the marks are asserted on the query (`MarkedDaysTest`).
 */
@RunWith(AndroidJUnit4::class)
class CalendarTest : ListComposeTest() {
    @Test
    fun opensOnTheCurrentMonth_withTodaySelected() {
        startWith()

        openTab(Perspective.Date)

        ui.awaitTag(dayTag(today))
        compose.onNodeWithTag(dayTag(today)).assertIsSelected()
        compose.onNodeWithTag(dayTag(today + 1)).assertIsNotSelected()
        val month = LocalDate.ofEpochDay(today)
        ui.awaitTag(dayTag(month.withDayOfMonth(1).toEpochDay()))
        ui.awaitTag(dayTag(month.withDayOfMonth(month.lengthOfMonth()).toEpochDay()))
    }

    @Test
    fun aDayIsMarked_iffANoteDatedThatDayMatches() {
        val first = LocalDate.ofEpochDay(today).withDayOfMonth(1).toEpochDay()
        startWith {
            note("checked counts", createdAt = now, checkedAt = now, date = first)
            note("alpha", createdAt = now, date = first + 2)
            note("undated never", createdAt = now)
        }
        openTab(Perspective.Date)
        ui.awaitTag(dayTag(first))
        awaitMarks(first, first + 2)

        compose.onNodeWithContentDescription(SEARCH).performClick()
        compose.onNodeWithTag("search_field").performTextInput("alpha")

        awaitMarks(first + 2)
    }

    @Test
    fun aDayTap_listsThatDaysNotes() {
        var tomorrow = 0L
        var todays = 0L
        startWith {
            todays = note("today", createdAt = now, date = today)
            tomorrow = note("tomorrow", createdAt = now, date = today + 1)
        }
        openTab(Perspective.Date)
        awaitRow(todays)
        compose.onNodeWithTag(rowTag(tomorrow)).assertDoesNotExist()

        compose.onNodeWithTag(dayTag(today + 1)).performClick()

        awaitRow(tomorrow)
        awaitNoRow(todays)
        compose.onNodeWithTag(dayTag(today + 1)).assertIsSelected()
    }

    /** Waits until exactly [days] of the displayed month carry "has notes" (ui.md § 4.4.1). */
    private fun awaitMarks(vararg days: Long) {
        val month = LocalDate.ofEpochDay(today)
        val all = (1..month.lengthOfMonth()).map { month.withDayOfMonth(it).toEpochDay() }
        compose.waitUntil(GateUi.TIMEOUT_MS) { all.filter { descriptionOf(dayTag(it)).contains(HAS_NOTES) } == days.toList() }
        assertEquals(days.toList(), all.filter { descriptionOf(dayTag(it)).contains(HAS_NOTES) })
    }
}

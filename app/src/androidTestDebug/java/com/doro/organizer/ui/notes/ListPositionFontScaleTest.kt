package com.doro.organizer.ui.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.ListPositionComposeTest
import com.doro.organizer.testing.atFontScale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-58 (n2) (DEC-110 (2); ui.md § 3.11 rule 2): in the single-column Calendar at its very top and font scale
 * 2, where the first row's place ends below the list's end less the create button's clearance (checked first), a new
 * note of the selected day is revealed at the list's top instead of at the bottom of the screen. Debug test sources: it needs
 * [atFontScale].
 */
@RunWith(AndroidJUnit4::class)
class ListPositionFontScaleTest : ListPositionComposeTest() {
    @Test
    fun n2_calendarAtFontScale2_whoseFirstRowEndsBelowTheClearance_showsANewNoteAsTheTopRow() = atFontScale(LARGE) {
        val ids = openCalendar()
        val firstRow = runCatching { ui.boundsOf(rowTag(ids[0])) }.getOrNull()
        assertTrue(
            "precondition: the first row's place $firstRow ends below ${wholeRowLimit()}",
            firstRow == null || firstRow.bottom > wholeRowLimit(),
        )
        startNewNote()

        tapBack()

        val noteId = awaitReveal()
        val top = topRow()
        assertEquals(noteId, top.id)
        val list = ui.boundsOf(NOTE_LIST).top
        assertTrue("the note's row at ${top.top} is not at the list's top $list", abs(list - top.top) <= 1f)
    }

    /** 30 notes of today in the Calendar's single column, at its very top. */
    private fun openCalendar(): List<Long> {
        lateinit var ids: List<Long>
        startWith { ids = notes(date = today) }
        openTab(Perspective.Date)
        ui.awaitTag(MONTH_GRID)
        compose.waitForIdle()
        return ids
    }

    private companion object {
        const val LARGE = 2f
    }
}

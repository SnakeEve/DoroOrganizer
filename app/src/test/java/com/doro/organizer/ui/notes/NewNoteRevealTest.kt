package com.doro.organizer.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * testing.md I-59 (d): the reveal judges the first rows of its read once and puts the note's row at the top of the
 * screen, or keeps the header items above it at a very top whose first row's place was shown entirely (DEC-110 (2)).
 */
class NewNoteRevealTest {
    private val rows = listOf(rowUi(1), rowUi(9), rowUi(2))
    private val scrolled = layout(index = 4, offset = 20, key = 5)

    @Test
    fun rowsOfAnEarlierRead_keepTheWait_andTheFirstRowsOfItsReadGiveThePositionOnce() {
        val reveal = waitingFor(noteId = 9, read = 2)

        assertNull(reveal.positionFor(rows, rowsRead = 1, scrolled, headerItems = 0))
        assertEquals(ScrollPosition(1, 0), reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
        assertNull("used once", reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
    }

    @Test
    fun rowsOfALaterRead_endTheWaitToo() {
        assertEquals(ScrollPosition(1, 0), waitingFor(9, 2).positionFor(rows, rowsRead = 3, scrolled, headerItems = 0))
    }

    @Test
    fun rowsWithoutTheNote_endTheWaitWithoutAPosition() {
        val reveal = waitingFor(noteId = 9, read = 2)

        assertNull(reveal.positionFor(listOf(rowUi(1)), rowsRead = 2, scrolled, headerItems = 0))
        assertNull(reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
    }

    @Test
    fun aScrollInProgress_endsTheWaitWithoutAPosition() {
        val reveal = waitingFor(noteId = 9, read = 2)
        val dragged = layout(index = 4, offset = 20, key = 5, scrolling = true)

        assertNull(reveal.positionFor(rows, rowsRead = 2, dragged, headerItems = 0))
        assertNull(reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
    }

    @Test
    fun aLaterWait_replacesAnEarlierOne() {
        val reveal = waitingFor(noteId = 9, read = 2).apply { await(NewNoteWait(noteId = 2, read = 3)) }

        assertNull(reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
        assertEquals(ScrollPosition(2, 0), reveal.positionFor(rows, rowsRead = 3, scrolled, headerItems = 0))
    }

    @Test
    fun drop_endsTheWait() {
        val reveal = waitingFor(noteId = 9, read = 2)

        reveal.drop()

        assertNull(reveal.positionFor(rows, rowsRead = 2, scrolled, headerItems = 0))
    }

    // Where the note's row goes, with the single-column Calendar's 2 header items.

    @Test
    fun theFirstRow_atAVeryTopThatShowedItsPlaceEntirely_keepsTheHeaderItemsAboveIt() {
        val wholeFirstRow = layout(index = 0, offset = 0, key = null, lastWholeIndex = HEADER_ITEMS)

        assertEquals(ScrollPosition(0, 0), waitingFor(1, 2).positionFor(rows, 2, wholeFirstRow, HEADER_ITEMS))
    }

    @Test
    fun theFirstRow_atAVeryTopThatCutItsPlace_goesToTheTopOfTheScreen() {
        val cutFirstRow = layout(index = 0, offset = 0, key = null, lastWholeIndex = HEADER_ITEMS - 1)

        assertEquals(ScrollPosition(HEADER_ITEMS, 0), waitingFor(1, 2).positionFor(rows, 2, cutFirstRow, HEADER_ITEMS))
    }

    @Test
    fun theFirstRow_belowTheVeryTop_goesToTheTopOfTheScreen() {
        val belowTop = layout(index = 0, offset = 1, key = null, lastWholeIndex = HEADER_ITEMS + 3)

        assertEquals(ScrollPosition(HEADER_ITEMS, 0), waitingFor(1, 2).positionFor(rows, 2, belowTop, HEADER_ITEMS))
    }

    @Test
    fun aLaterRow_atTheVeryTop_goesToTheTopOfTheScreen() {
        val veryTop = layout(index = 0, offset = 0, key = null, lastWholeIndex = HEADER_ITEMS + 3)

        assertEquals(ScrollPosition(HEADER_ITEMS + 2, 0), waitingFor(2, 2).positionFor(rows, 2, veryTop, HEADER_ITEMS))
    }

    @Test
    fun withoutHeaderItems_theFirstRowAtTheVeryTop_isItemZero() {
        val veryTop = layout(index = 0, offset = 0, key = 1, lastWholeIndex = -1)

        assertEquals(ScrollPosition(0, 0), waitingFor(1, 2).positionFor(rows, 2, veryTop, headerItems = 0))
    }

    private fun waitingFor(noteId: Long, read: Int) = NewNoteReveal().apply { await(NewNoteWait(noteId, read)) }

    private companion object {
        const val HEADER_ITEMS = 2
    }
}

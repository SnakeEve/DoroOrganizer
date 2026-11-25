package com.doro.organizer.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** testing.md I-59 (c): a list at its very top stays there when rows arrive above its first row (DEC-110 (1)). */
class TopAnchorTest {
    private val atTop = layout(index = 0, offset = 0, key = 1)
    private val before = listOf(rowUi(1), rowUi(2))
    private val newFirst = listOf(rowUi(3), rowUi(1), rowUi(2))

    @Test
    fun theFirstRowsItSees_giveNoPosition() {
        assertNull(TopAnchor().positionFor(before, atTop))
    }

    @Test
    fun anotherFirstRow_atTheVeryTop_asksForItemZeroAtOffsetZero() {
        val anchor = TopAnchor().apply { positionFor(before, atTop) }

        assertEquals(ScrollPosition(0, 0), anchor.positionFor(newFirst, atTop))
    }

    @Test
    fun theSameRowsInstance_orTheSameFirstRow_giveNoPosition() {
        val anchor = TopAnchor().apply { positionFor(before, atTop) }

        assertNull(anchor.positionFor(before, atTop))
        assertNull(anchor.positionFor(listOf(rowUi(1, orderedAt = 9)), atTop))
    }

    @Test
    fun aListBelowItsVeryTop_getsNoPosition() {
        val anchor = TopAnchor().apply { positionFor(before, atTop) }

        assertNull("offset", anchor.positionFor(newFirst, layout(index = 0, offset = 12, key = 1)))
        assertNull("index", anchor.positionFor(before, layout(index = 1, offset = 0, key = 2)))
    }

    @Test
    fun aScrollInProgress_getsNoPosition() {
        val anchor = TopAnchor().apply { positionFor(before, atTop) }

        assertNull(anchor.positionFor(newFirst, layout(index = 0, offset = 0, key = 1, scrolling = true)))
    }
}

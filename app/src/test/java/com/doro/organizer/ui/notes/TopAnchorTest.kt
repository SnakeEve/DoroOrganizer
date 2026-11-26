package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.EntityRowUi
import com.doro.organizer.ui.common.PickerItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * testing.md I-59 (c): a list at its very top stays there when rows arrive above its first row (DEC-110 (1)); I-63:
 * the picker body keeps its very top the same way when another first name arrives (DEC-112 (4)).
 */
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

    // The picker body (DEC-112 (4)).

    @Test
    fun pickerBody_anotherFirstKey_atTheVeryTop_asksForItemZeroAtOffsetZero() {
        val anchor = TopAnchor().apply { assertNull("the first items", positionForPicker(albertAlice, atTop = true)) }

        assertNull("the same first key", anchor.positionForPicker(albertAliceBob, atTop = true))
        assertEquals(ScrollPosition(0, 0), anchor.positionForPicker(listOf(name(9, "Aaron")) + albertAliceBob, atTop = true))
    }

    @Test
    fun pickerBody_belowItsTopOrScrolling_getsNoPosition_andTheItemsStillCount() {
        val anchor = TopAnchor().apply { positionForPicker(albertAlice, atTop = true) }
        val aaronFirst = listOf(name(9, "Aaron")) + albertAlice

        assertNull(anchor.positionForPicker(aaronFirst, atTop = false))
        assertNull("Aaron was first before", anchor.positionForPicker(listOf(name(9, "Aaron")) + albertAliceBob, atTop = true))
    }

    private val albertAlice = listOf(name(1, "Albert"), name(2, "Alice"))
    private val albertAliceBob = albertAlice + name(3, "Bob")

    private fun name(id: Long, name: String) =
        PickerItem.Entity(EntityRowUi(id, name, noteCount = 0, added = LocalDate.of(2026, 11, 17)), checked = false)
}

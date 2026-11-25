package com.doro.organizer.ui.notes

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * testing.md I-59 (e): a list below its very top keeps its first visible row in place, also when that row moved, and
 * takes it back after a return at the restored position (DEC-110 (3)); the last layout as the rules read it.
 */
class KeepAnchorTest {
    private val before = listOf(rowUi(1, orderedAt = 50), rowUi(2, orderedAt = 40), rowUi(3, orderedAt = 30))
    private val scrolled = layout(index = 1, offset = 20, key = 2)
    private val restored = ScrollPosition(1, 20)

    // The first visible row in the next rows.

    @Test
    fun itsRowMoved_asksForTheLastLayoutsIndexAndOffset() {
        val edited = listOf(rowUi(2, orderedAt = 60), rowUi(1, orderedAt = 50), rowUi(3, orderedAt = 30))

        assertEquals(ScrollPosition(1, 20), prepared().positionFor(edited, scrolled, headerItems = 0))
    }

    @Test
    fun itsDoneStateChanged_asksForTheLastLayoutsIndexAndOffset() {
        val checked = listOf(rowUi(1, orderedAt = 50), rowUi(3, orderedAt = 30), rowUi(2, checked = true, orderedAt = 40))

        assertEquals(ScrollPosition(1, 20), prepared().positionFor(checked, scrolled, headerItems = 0))
    }

    @Test
    fun itsRowUnmovedOrGone_givesNoPosition() {
        val anchor = prepared()
        val newAbove = listOf(rowUi(9, orderedAt = 70)) + before

        assertNull("unmoved: key anchoring keeps it", anchor.positionFor(newAbove, scrolled, headerItems = 0))
        assertNull("gone", anchor.positionFor(listOf(rowUi(1, orderedAt = 50)), scrolled, headerItems = 0))
    }

    @Test
    fun atTheVeryTop_whileScrolling_orUnderAHeader_givesNoPosition() {
        val moved = listOf(rowUi(2, orderedAt = 60), rowUi(1, orderedAt = 50), rowUi(3, orderedAt = 30))

        assertNull(prepared().positionFor(moved, layout(index = 0, offset = 0, key = 1), headerItems = 0))
        assertNull(prepared().positionFor(moved, layout(index = 1, offset = 20, key = 2, scrolling = true), headerItems = 0))
        val header = layout(index = 1, offset = 0, key = null)
        assertNull(prepared().positionFor(moved, header, headerItems = 2))
    }

    @Test
    fun theSameRowsInstance_givesNoPosition() {
        assertNull(prepared().positionFor(before, scrolled, headerItems = 0))
    }

    // A kept row after a return.

    @Test
    fun aKeptRowThereUnmoved_isAskedForAtTheRestoredOffset_once() {
        val anchor = KeepAnchor(seed = KeptRow(2, checked = false, orderedAt = 40), restored)
        val unlaidOut = layout(index = 1, offset = 20, key = null)
        val newAbove = listOf(rowUi(9, orderedAt = 70)) + before

        assertNull("no rows yet", anchor.positionFor(emptyList(), unlaidOut, headerItems = 2))
        assertEquals(ScrollPosition(2 + 2, 20), anchor.positionFor(newAbove, unlaidOut, headerItems = 2))
        assertNull(anchor.positionFor(listOf(rowUi(8, orderedAt = 80)) + newAbove, unlaidOut, headerItems = 2))
    }

    @Test
    fun aKeptRowThatMovedOrIsGone_asksForTheRestoredPosition() {
        val unlaidOut = layout(index = 1, offset = 20, key = null)
        val moved = listOf(rowUi(2, orderedAt = 60), rowUi(1, orderedAt = 50), rowUi(3, orderedAt = 30))

        val movedSeed = KeepAnchor(KeptRow(2, checked = false, orderedAt = 40), restored)
        val goneSeed = KeepAnchor(KeptRow(7, checked = false, orderedAt = 40), restored)

        assertEquals("moved", restored, movedSeed.positionFor(moved, unlaidOut, headerItems = 0))
        assertEquals("gone", restored, goneSeed.positionFor(before, unlaidOut, headerItems = 0))
    }

    @Test
    fun aKeptRow_afterALayoutThatClampedTheList_usesTheRestoredPosition_notTheLayouts() {
        // The single-column Calendar laid out its grid, header and empty item first: the list stands at item 2.
        val clamped = layout(index = 2, offset = 0, key = null)
        val moved = listOf(rowUi(2, orderedAt = 60), rowUi(1, orderedAt = 50), rowUi(3, orderedAt = 30))
        val seed = KeptRow(2, checked = false, orderedAt = 40)

        assertEquals(ScrollPosition(2 + 1, 20), KeepAnchor(seed, restored).positionFor(before, clamped, headerItems = 2))
        assertEquals(restored, KeepAnchor(seed, restored).positionFor(moved, clamped, headerItems = 2))
        val atTop = layout(index = 0, offset = 0, key = null)
        assertEquals(ScrollPosition(2 + 1, 20), KeepAnchor(seed, restored).positionFor(before, atTop, headerItems = 2))
    }

    @Test
    fun aKeptRow_atARestoredVeryTop_orWhileScrolling_givesNoPosition() {
        val seed = KeptRow(1, checked = false, orderedAt = 50)
        val newAbove = listOf(rowUi(9, orderedAt = 70)) + before

        assertNull(KeepAnchor(seed, ScrollPosition(0, 0)).positionFor(newAbove, scrolled, headerItems = 0))
        val dragged = layout(index = 1, offset = 20, key = null, scrolling = true)
        assertNull(KeepAnchor(seed, restored).positionFor(newAbove, dragged, headerItems = 0))
    }

    @Test
    fun anUnusedSeed_isHandedOn_untilRowsUseIt() {
        val seed = KeptRow(2, checked = false, orderedAt = 40)
        val anchor = KeepAnchor(seed, restored)

        assertEquals("before any rows", seed, anchor.unusedSeed())
        anchor.positionFor(emptyList(), scrolled, headerItems = 0)
        assertEquals("after empty rows", seed, anchor.unusedSeed())
        anchor.positionFor(before, scrolled, headerItems = 0)
        assertNull("used", anchor.unusedSeed())
    }

    @Test
    fun keptRowOf_isTheRowTheLayoutShowsFirst_andNothingForAHeader() {
        assertEquals(KeptRow(2, checked = false, orderedAt = 40), KeptRow.of(scrolled, before))
        assertNull(KeptRow.of(layout(index = 0, offset = 0, key = null), before))
    }

    // The last layout.

    @Test
    fun lastLayout_takesTheFirstVisibleItemsKey_andTheLastItemEndingAboveTheBottomPadding() {
        // Viewport 0..1000 with 88 px of bottom padding: whole items end at or above 912.
        val header = arrayOf(item(0, "grid", 0, 600), item(1, "header", 600, 100))
        val info = layoutInfo(*header, item(2, 7L, 700, 212), item(3, 8L, 912, 100))

        val layout = LastLayout.of(info, index = 2, offset = 0, scrollInProgress = true)

        assertEquals(7L, layout.key)
        assertEquals(2, layout.lastWholeIndex)
        assertEquals(true, layout.scrollInProgress)
        val lower = layoutInfo(*header, item(2, 7L, 700, 213))
        assertEquals("one px lower is cut", 1, LastLayout.of(lower, index = 0, offset = 0, scrollInProgress = false).lastWholeIndex)
    }

    @Test
    fun lastLayout_withoutAWholeItem_orBeforeTheFirstLayout() {
        assertEquals(-1, LastLayout.of(layoutInfo(item(0, "grid", 0, 2000)), 0, 0, false).lastWholeIndex)
        val none = LastLayout.of(layoutInfo(), index = 1, offset = 20, scrollInProgress = false)
        assertNull(none.key)
        assertEquals(-1, none.lastWholeIndex)
        assertEquals(ScrollPosition(1, 20), none.scrollPosition)
    }

    private fun prepared() = KeepAnchor(seed = null, restored).apply { positionFor(before, scrolled, headerItems = 0) }

    private fun item(index: Int, key: Any, offset: Int, size: Int) = object : LazyListItemInfo {
        override val index = index
        override val key = key
        override val offset = offset
        override val size = size
    }

    private fun layoutInfo(vararg items: LazyListItemInfo) = object : LazyListLayoutInfo {
        override val visibleItemsInfo = items.toList()
        override val viewportStartOffset = 0
        override val viewportEndOffset = 1000
        override val totalItemsCount = items.size
        override val afterContentPadding = 88
    }
}

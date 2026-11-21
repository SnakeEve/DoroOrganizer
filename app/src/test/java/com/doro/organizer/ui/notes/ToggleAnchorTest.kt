package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.NoteRowUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scroll anchor of the done toggle (ui.md § 3.9 item 4): restored for the toggle's own emission only. */
class ToggleAnchorTest {
    private val position = ScrollPosition(index = 3, offset = 40)
    private val before = listOf(row(1, checked = false), row(2, checked = false))

    @Test
    fun theRowsCarryingTheToggle_restoreThePosition_once() {
        val anchor = ToggleAnchor().apply { record(1, checked = true, position, before) }

        assertNull("the same rows are not the result", anchor.positionFor(before))
        val after = listOf(row(2, checked = false), row(1, checked = true))
        assertEquals(position, anchor.positionFor(after))
        assertNull(anchor.positionFor(listOf(row(1, checked = true))))
    }

    @Test
    fun aWriteThatChangedNothing_dropsTheAnchor_soALaterChangeDoesNotJump() {
        val anchor = ToggleAnchor().apply { record(1, checked = true, position, before) }

        anchor.drop(1)

        assertNull(anchor.positionFor(listOf(row(2, checked = false), row(1, checked = true))))
    }

    @Test
    fun dropOfAnotherRow_keepsTheAnchor() {
        val anchor = ToggleAnchor().apply { record(1, checked = true, position, before) }

        anchor.drop(2)

        assertEquals(position, anchor.positionFor(listOf(row(2, checked = false), row(1, checked = true))))
    }

    @Test
    fun anUnrelatedChangeFirst_waitsForTheToggledState() {
        val anchor = ToggleAnchor().apply { record(1, checked = true, position, before) }

        assertNull(anchor.positionFor(listOf(row(1, checked = false))))
        assertEquals(position, anchor.positionFor(listOf(row(1, checked = true))))
    }

    @Test
    fun rowsWithoutTheToggledRow_endTheAnchor() {
        val anchor = ToggleAnchor().apply { record(1, checked = true, position, before) }

        assertNull(anchor.positionFor(listOf(row(2, checked = false))))
        assertNull(anchor.positionFor(listOf(row(1, checked = true))))
    }

    private fun row(id: Long, checked: Boolean) =
        NoteRowUi(id, "t", null, checked, null, overdue = false, emptyList(), emptyList(), orphan = true)
}

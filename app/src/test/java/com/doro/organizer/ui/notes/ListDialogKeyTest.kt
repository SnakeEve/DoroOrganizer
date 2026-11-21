package com.doro.organizer.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved form of the list's sheets and dialogs (ui.md § 3.10, key `listDialog`; testing.md I-38). */
class ListDialogKeyTest {
    @Test
    fun everyDialog_roundTripsThroughItsKey() {
        val dialogs = listOf(
            ListDialog.DateFilterSheet,
            ListDialog.DateEndPicker(RangeEnd.From),
            ListDialog.DateEndPicker(RangeEnd.Until),
            ListDialog.RangePicker,
        )

        assertEquals(listOf("DateFilterSheet", "DateEndPicker:From", "DateEndPicker:Until", "RangePicker"), dialogs.map { it.toKey() })
        assertEquals(dialogs, dialogs.map { listDialogOf(it.toKey()) })
    }

    @Test
    fun noKeyOrAnUnknownKey_isNoDialog() {
        assertNull(listDialogOf(null))
        assertNull(listDialogOf("SetDate"))
        assertNull(listDialogOf("DateEndPicker:Middle"))
    }
}

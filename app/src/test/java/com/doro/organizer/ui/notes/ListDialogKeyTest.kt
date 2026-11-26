package com.doro.organizer.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved form of the list's sheets and dialogs (ui.md § 3.10, key `listDialog`; testing.md I-38, I-65). */
class ListDialogKeyTest {
    @Test
    fun everyDialog_roundTripsThroughItsKey() {
        val dialogs = listOf(
            ListDialog.DateFilterSheet,
            ListDialog.DateEndPicker(RangeEnd.From),
            ListDialog.DateEndPicker(RangeEnd.Until),
            ListDialog.RangePicker,
            ListDialog.SetDate,
            ListDialog.BatchDelete,
            ListDialog.Rename(12),
        )

        assertEquals(
            listOf(
                "DateFilterSheet", "DateEndPicker:From", "DateEndPicker:Until", "RangePicker", "SetDate", "BatchDelete",
                "Rename:12",
            ),
            dialogs.map { it.toKey() },
        )
        assertEquals(dialogs.map(SavedListDialog::Shown), dialogs.map { savedListDialogOf(it.toKey()) })
    }

    @Test
    fun aDeleteEntityDialog_isSavedByItsId_andRestoresAsAReReadOfThatId() {
        val key = ListDialog.DeleteEntity(id = 8, n = 3, m = 1).toKey()

        assertEquals("DeleteEntity:8", key)
        assertEquals(SavedListDialog.DeleteEntity(8), savedListDialogOf(key))
    }

    @Test
    fun noKeyOrAnUnknownKey_isNoDialog() {
        assertNull(savedListDialogOf(null))
        assertNull(savedListDialogOf("Associate"))
        assertNull(savedListDialogOf("DateEndPicker:Middle"))
    }

    @Test
    fun aMalformedEntityKey_isNoDialog() {
        val malformed = listOf(
            "Rename", "Rename:", "Rename:x", "Rename:1:2", "DeleteEntity", "DeleteEntity:", "DeleteEntity:1.5",
            "DeleteEntity:3:1:0", "BatchDelete:4",
        )

        assertEquals(malformed.map { null }, malformed.map(::savedListDialogOf))
    }
}

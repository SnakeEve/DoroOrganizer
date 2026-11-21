package com.doro.organizer.ui.notes

import com.doro.organizer.ui.common.toRowUi
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The initial day of the batch date picker (ui.md § 5.3): the date every selected note shares, else none. */
class BatchDialogsTest {
    private val day = LocalDate.of(2026, 11, 17)
    private val rows = listOf(
        noteRow(1, date = day.toEpochDay()),
        noteRow(2, date = day.toEpochDay()),
        noteRow(3, date = day.plusDays(1).toEpochDay()),
        noteRow(4),
    ).map { it.toRowUi(day, null, null) }

    @Test
    fun aDateAllSelectedRowsShare_isTheInitialDay() {
        assertEquals(day, sharedDate(rows, setOf(1, 2)))
        assertEquals(day, sharedDate(rows, setOf(2)))
    }

    @Test
    fun differentDates_anUndatedRow_orNoRow_giveNoInitialDay() {
        assertNull(sharedDate(rows, setOf(1, 3)))
        assertNull(sharedDate(rows, setOf(1, 4)))
        assertNull(sharedDate(rows, setOf(4)))
        assertNull(sharedDate(rows, emptySet()))
    }
}

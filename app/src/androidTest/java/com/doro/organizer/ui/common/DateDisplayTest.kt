package com.doro.organizer.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.ListComposeTest
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-31 (DEC-26): the MEDIUM note date in the phone's own locale data (asserted on the device, because the
 * JDK's differs), and "Overdue" as words in the row's semantics, never color alone.
 */
@RunWith(AndroidJUnit4::class)
class DateDisplayTest : ListComposeTest() {
    @Test
    fun mediumDates_followTheLocale() {
        val day = LocalDate.of(2026, 11, 17)

        assertEquals("17 Nov 2026", DateFormats(Locale.UK).medium(day))
        assertEquals("Nov 17, 2026", DateFormats(Locale.US).medium(day))
    }

    @Test
    fun overdue_isText_inTheRowSemantics() {
        var overdue = 0L
        var dueToday = 0L
        var doneLate = 0L
        startWith {
            overdue = note("overdue", createdAt = now, date = today - 1)
            dueToday = note("due today", createdAt = now, date = today)
            doneLate = note("done late", createdAt = now, checkedAt = now, date = today - 1)
        }
        awaitRow(doneLate)

        assertTrue(rowDescription(overdue).contains(OVERDUE))
        assertFalse(rowDescription(dueToday).contains(OVERDUE))
        assertFalse(rowDescription(doneLate).contains(OVERDUE))
    }
}

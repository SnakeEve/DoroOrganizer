package com.doro.organizer.ui.common

import com.doro.organizer.model.EpochDays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `MonthGrid`'s pages and cells (ui.md § 4.4.1) and the first day of the week. */
class MonthPagesTest {
    @Test
    fun pages_coverEveryMonthOfTheNoteDateRange_inOrder() {
        assertEquals(2412, MonthPages.COUNT)
        assertEquals(YearMonth.from(LocalDate.ofEpochDay(EpochDays.MIN)), MonthPages.monthOf(0))
        assertEquals(YearMonth.from(LocalDate.ofEpochDay(EpochDays.MAX)), MonthPages.monthOf(MonthPages.COUNT - 1))
        for (page in 0 until MonthPages.COUNT) {
            assertEquals(page, MonthPages.pageOf(MonthPages.monthOf(page)))
            if (page > 0) assertEquals(MonthPages.monthOf(page - 1).plusMonths(1), MonthPages.monthOf(page))
        }
    }

    @Test
    fun leadingBlanks_placeDayOneUnderItsWeekday() {
        val november = YearMonth.of(2026, 11) // 1 November 2026 is a Sunday

        assertEquals(6, MonthPages.leadingBlanks(november, DayOfWeek.MONDAY))
        assertEquals(0, MonthPages.leadingBlanks(november, DayOfWeek.SUNDAY))
        assertEquals(1, MonthPages.leadingBlanks(november, DayOfWeek.SATURDAY))
    }

    @Test
    fun sixRows_holdEveryMonth_whateverTheFirstDayOfTheWeek() {
        for (page in 0 until MonthPages.COUNT) {
            val month = MonthPages.monthOf(page)
            for (first in DayOfWeek.entries) {
                val lastCell = MonthPages.leadingBlanks(month, first) + month.lengthOfMonth() - 1
                assertTrue("$month from $first", lastCell < MonthPages.ROWS * MonthPages.COLUMNS)
            }
        }
    }

    @Test
    fun firstDayOfWeek_mapsEveryLocalePreferencesValue() {
        val values = mapOf(
            "sun" to DayOfWeek.SUNDAY,
            "mon" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY,
        )

        values.forEach { (value, day) -> assertEquals(value, day, firstDayOfWeekOf(value)) }
        assertNull("no preference falls back to the locale", firstDayOfWeekOf(""))
    }
}

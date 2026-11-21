package com.doro.organizer.ui.common

import java.time.DayOfWeek
import java.time.YearMonth

/**
 * The pages of `MonthGrid`'s pager (ui.md § 4.4.1): one per month from January 1900 to December 2100, the year range
 * of every date picker (`DatePickerDefaults.YearRange`, `EpochDays`).
 */
object MonthPages {
    const val FIRST_YEAR = 1900
    const val LAST_YEAR = 2100

    /** 201 years of 12 months. */
    const val COUNT = (LAST_YEAR - FIRST_YEAR + 1) * 12

    /** The page that shows [month]. */
    fun pageOf(month: YearMonth): Int = (month.year - FIRST_YEAR) * 12 + month.monthValue - 1

    /** The month shown on [page]. */
    fun monthOf(page: Int): YearMonth = YearMonth.of(FIRST_YEAR + page / 12, page % 12 + 1)

    /**
     * The empty cells before day 1 of [month] in a grid whose first column is [firstDayOfWeek]: day `d` sits in the
     * row-major cell `leadingBlanks + d - 1` of the 6 × 7 grid.
     */
    fun leadingBlanks(month: YearMonth, firstDayOfWeek: DayOfWeek): Int =
        Math.floorMod(month.atDay(1).dayOfWeek.value - firstDayOfWeek.value, COLUMNS)

    /** Rows of the grid: always 6, so its height never changes between months. */
    const val ROWS = 6

    /** Columns of the grid. */
    const val COLUMNS = 7
}

package com.doro.organizer.model

/**
 * The range of a note `date`, an epoch day (`LocalDate.toEpochDay()`): 1900-01-01..2100-12-31, the year range of
 * every date picker and of `MonthGrid` (DEC-26, data-layer.md INV-5).
 */
object EpochDays {
    /** 1900-01-01. */
    const val MIN = -25_567L

    /** 2100-12-31. */
    const val MAX = 47_846L
}

package com.doro.organizer.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Note-date math (DEC-26). A note date is the epoch day of the calendar day the user picked: zone-free, it never
 * passes through the device zone. "Today" comes from `AppTime`.
 */
object DateRules {
    /**
     * The epoch day of a Material 3 date-picker selection. The picker reports a day as UTC midnight of that day, so
     * the conversion uses UTC, never the device zone.
     */
    fun epochDayOfPickerMillis(pickerMillis: Long): Long =
        Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    /** The Material 3 date-picker millis that select [epochDay]: UTC midnight of that day. */
    fun pickerMillisOf(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Overdue: unchecked, dated, and the date is before [today] (DEC-26, Q5). */
    fun isOverdue(checked: Boolean, date: Long?, today: LocalDate): Boolean =
        !checked && date != null && date < today.toEpochDay()
}

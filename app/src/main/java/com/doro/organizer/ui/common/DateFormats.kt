package com.doro.organizer.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.text.util.LocalePreferences
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The date formats of ui.md § 11.14 for one locale. None carries a zone: a note date is a zone-free day (DEC-26).
 * Built once per configuration locale by [rememberDateFormats].
 */
@Immutable
class DateFormats(locale: Locale) {
    private val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val monthTitle = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMy"), locale)
    private val dayNumber = DateTimeFormatter.ofPattern("d", locale)

    /**
     * The first column of `MonthGrid` (ui.md § 4.4.1): the user's regional preference (the `fw` extension of the
     * configuration locale on Android 14+), else the locale's convention.
     */
    val firstDayOfWeek: DayOfWeek =
        firstDayOfWeekOf(LocalePreferences.getFirstDayOfWeek(locale, false)) ?: WeekFields.of(locale).firstDayOfWeek

    /** Rows, chips, the editor and picker meta: "17 Nov 2026" / "Nov 17, 2026". */
    fun medium(date: LocalDate): String = medium.format(date)

    /** [medium] of an epoch day. */
    fun medium(epochDay: Long): String = medium(LocalDate.ofEpochDay(epochDay))

    /** The Calendar header and the day-cell descriptions: "Tuesday, 17 November 2026". */
    fun full(date: LocalDate): String = full.format(date)

    /** The `MonthGrid` title: "November 2026". */
    fun month(month: YearMonth): String = monthTitle.format(month)

    /** The number of a day cell. */
    fun dayNumber(date: LocalDate): String = dayNumber.format(date)
}

/** The [DateFormats] of the current configuration locale, rebuilt when the configuration changes it. */
@Composable
fun rememberDateFormats(): DateFormats {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateFormats(locale) }
}

/**
 * The day named by a `LocalePreferences.getFirstDayOfWeek` value ("sun", "mon", …); null for the empty value of a
 * locale without a preference, or for any other value.
 */
internal fun firstDayOfWeekOf(preference: String): DayOfWeek? = when (preference) {
    LocalePreferences.FirstDayOfWeek.SUNDAY -> DayOfWeek.SUNDAY
    LocalePreferences.FirstDayOfWeek.MONDAY -> DayOfWeek.MONDAY
    LocalePreferences.FirstDayOfWeek.TUESDAY -> DayOfWeek.TUESDAY
    LocalePreferences.FirstDayOfWeek.WEDNESDAY -> DayOfWeek.WEDNESDAY
    LocalePreferences.FirstDayOfWeek.THURSDAY -> DayOfWeek.THURSDAY
    LocalePreferences.FirstDayOfWeek.FRIDAY -> DayOfWeek.FRIDAY
    LocalePreferences.FirstDayOfWeek.SATURDAY -> DayOfWeek.SATURDAY
    else -> null
}

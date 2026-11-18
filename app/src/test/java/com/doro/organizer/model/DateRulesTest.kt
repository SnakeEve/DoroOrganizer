package com.doro.organizer.model

import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.MidnightGap
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** testing.md I-28 (pickerUtc) and I-29 (overdue); DEC-26. */
class DateRulesTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaultZone() = TimeZone.setDefault(originalZone)

    @Test
    fun epochDays_areThePickerYearRange() {
        assertEquals(LocalDate.of(1900, 1, 1).toEpochDay(), EpochDays.MIN)
        assertEquals(LocalDate.of(2100, 12, 31).toEpochDay(), EpochDays.MAX)
    }

    @Test
    fun pickerUtc_everyDayOf2026_roundTripsWhateverTheDefaultZone() {
        val zones = listOf("Pacific/Honolulu", "Pacific/Kiritimati", "America/New_York", "Europe/Berlin")
            .map(ZoneId::of) + MidnightGap.in2026().zone
        zones.forEach { zone ->
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            var day = LocalDate.of(2026, 1, 1)
            while (day.year == 2026) {
                // What the Material 3 picker reports for a tapped day: that day's UTC midnight.
                val pickerMillis = day.toEpochDay() * MILLIS_PER_DAY
                val stored = DateRules.epochDayOfPickerMillis(pickerMillis)

                assertEquals("$zone $day", day.toEpochDay(), stored)
                assertEquals("$zone $day", pickerMillis, DateRules.pickerMillisOf(stored))
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun pickerUtc_tappedDayIsStored() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        val pickerMillis = Instant.parse("2026-11-17T00:00:00Z").toEpochMilli()

        assertEquals(20_774L, DateRules.epochDayOfPickerMillis(pickerMillis))
    }

    @Test
    fun pickerUtc_pickerRangeEnds_roundTrip() {
        listOf(EpochDays.MIN, EpochDays.MAX).forEach {
            assertEquals(it, DateRules.epochDayOfPickerMillis(DateRules.pickerMillisOf(it)))
        }
    }

    @Test
    fun overdue_startsAtLocalMidnightAfterTheDate() {
        val berlin = ZoneId.of("Europe/Berlin")
        val day = LocalDate.of(2026, 11, 17)
        val time = FakeAppTime(start = day.plusDays(1).atStartOfDay(berlin).toInstant().minusMillis(1), zone = berlin)

        assertFalse(DateRules.isOverdue(checked = false, date = day.toEpochDay(), today = time.today()))
        time.advanceBy(Duration.ofMillis(1))
        assertTrue(DateRules.isOverdue(checked = false, date = day.toEpochDay(), today = time.today()))
    }

    @Test
    fun overdue_neverForCheckedOrUndatedNotes() {
        val today = LocalDate.of(2026, 11, 17)
        val yesterday = today.minusDays(1).toEpochDay()

        assertFalse(DateRules.isOverdue(checked = true, date = yesterday, today = today))
        assertFalse(DateRules.isOverdue(checked = false, date = null, today = today))
        assertFalse(DateRules.isOverdue(checked = true, date = null, today = today))
        assertFalse(DateRules.isOverdue(checked = false, date = today.toEpochDay(), today = today))
        assertFalse(DateRules.isOverdue(checked = false, date = today.plusDays(1).toEpochDay(), today = today))
        assertTrue(DateRules.isOverdue(checked = false, date = yesterday, today = today))
    }

    @Test
    fun overdue_zoneSwitchReEvaluatesTheSameStoredDate() {
        val noteDate = LocalDate.of(2026, 11, 17).toEpochDay()
        val time = FakeAppTime(start = Instant.parse("2026-11-17T12:00:00Z"), zone = ZoneId.of("Pacific/Kiritimati"))

        assertEquals(LocalDate.of(2026, 11, 18), time.today())
        assertTrue(DateRules.isOverdue(checked = false, date = noteDate, today = time.today()))
        time.setZone(ZoneId.of("Pacific/Honolulu"))
        assertEquals(LocalDate.of(2026, 11, 17), time.today())
        assertFalse(DateRules.isOverdue(checked = false, date = noteDate, today = time.today()))
        time.setZone(ZoneId.of("Pacific/Kiritimati"))
        assertTrue(DateRules.isOverdue(checked = false, date = noteDate, today = time.today()))
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

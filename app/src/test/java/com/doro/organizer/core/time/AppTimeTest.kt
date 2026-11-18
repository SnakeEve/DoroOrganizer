package com.doro.organizer.core.time

import com.doro.organizer.testing.MidnightGap
import java.time.Duration as JavaDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * testing.md I-30: [DefaultAppTime.todayFlow] in virtual time. The wall clock is `start + virtual time + offset`, so
 * the midnight timer's delay and the clock move together; [zone] and [offset] simulate the user changing the zone
 * or the time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private var zone: ZoneId = berlin
    private var offset: JavaDuration = JavaDuration.ZERO

    @Test
    fun clockReadsUseTheInjectedInstantAndTheCurrentZone() = runTest(timeout = 10.seconds) {
        val start = Instant.parse("2026-11-17T22:30:00Z")
        val time = appTime(start)

        assertEquals(start.toEpochMilli(), time.nowMillis())
        assertEquals(LocalDate.of(2026, 11, 17), time.dateOf(start.toEpochMilli()))
        zone = ZoneId.of("Pacific/Kiritimati")
        assertEquals(LocalDate.of(2026, 11, 18), time.today())
        assertEquals(LocalDate.of(2026, 11, 18), time.dateOf(start.toEpochMilli()))
    }

    @Test
    fun whileStarted_emitsAtEachLocalMidnight() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T22:30:00Z")) // 23:30 in Berlin
        val days = emissions(time)

        time.onStart()
        advance(30.minutes - 1.milliseconds)
        assertEquals(listOf(day(17)), days)
        advance(1.milliseconds)
        assertEquals(listOf(day(17), day(18)), days)
        advance(24.hours)
        assertEquals(listOf(day(17), day(18), day(19)), days)
    }

    @Test
    fun springForwardDay_has23Hours() = runTest(timeout = 10.seconds) {
        zone = newYork
        val time = appTime(LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().minusSeconds(3_600))
        val days = emissions(time)

        time.onStart()
        advance(1.hours)
        advance(23.hours - 1.milliseconds)
        assertEquals(listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8)), days)
        advance(1.milliseconds)
        assertEquals(LocalDate.of(2026, 3, 9), days.last())
    }

    @Test
    fun fallBackDay_has25Hours() = runTest(timeout = 10.seconds) {
        zone = newYork
        val time = appTime(LocalDate.of(2026, 11, 1).atStartOfDay(newYork).toInstant().minusSeconds(3_600))
        val days = emissions(time)

        time.onStart()
        advance(1.hours)
        advance(25.hours - 1.milliseconds)
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)), days)
        advance(1.milliseconds)
        assertEquals(LocalDate.of(2026, 11, 2), days.last())
    }

    @Test
    fun midnightGap_newDayEmittedAtItsFirstExistingInstant() = runTest(timeout = 10.seconds) {
        val gap = MidnightGap.in2026()
        zone = gap.zone
        val gapDay = gap.transition.dateTimeAfter.toLocalDate()
        val time = appTime(gap.transition.instant.minusSeconds(7_200))
        val days = emissions(time)

        time.onStart()
        advance(2.hours - 1.milliseconds)
        assertEquals(listOf(gapDay.minusDays(1)), days)
        advance(1.milliseconds)
        assertEquals(listOf(gapDay.minusDays(1), gapDay), days)
        val emittedAt = Instant.ofEpochMilli(time.nowMillis()).atZone(gap.zone).toLocalDateTime()
        assertEquals(gap.transition.dateTimeAfter, emittedAt) // 01:00 local: 00:00 does not exist that day
    }

    @Test
    fun zoneChange_toNextDay_emitsIt() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T12:00:00Z"))
        val days = emissions(time)

        zone = ZoneId.of("Pacific/Kiritimati")
        time.onTimeChanged()

        assertEquals(listOf(day(17), day(18)), days)
    }

    @Test
    fun zoneChange_sameDate_emitsNothing() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T12:00:00Z"))
        val days = emissions(time)

        zone = ZoneId.of("Europe/Paris")
        time.onTimeChanged()

        assertEquals(listOf(day(17)), days)
    }

    @Test
    fun zoneChange_whileStarted_reArmsTimerForTheNewMidnight() = runTest(timeout = 10.seconds) {
        zone = ZoneId.of("Pacific/Honolulu")
        val time = appTime(Instant.parse("2026-11-17T12:00:00Z")) // 02:00 in Honolulu, 13:00 in Berlin
        val days = emissions(time)
        time.onStart()
        runCurrent() // the timer is now waiting for the old midnight

        zone = berlin
        time.onTimeChanged()
        advance(11.hours - 1.milliseconds)
        assertEquals(listOf(day(17)), days)
        advance(1.milliseconds) // Berlin midnight, 13 hours before Honolulu's
        assertEquals(listOf(day(17), day(18)), days)
    }

    @Test
    fun wallClockChange_whileStarted_reArmsTimerForTheNewMidnight() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T12:00:00Z")) // 13:00 in Berlin
        val days = emissions(time)
        time.onStart()
        runCurrent() // the timer is now waiting for the old midnight

        offset = JavaDuration.ofHours(10) // the user sets the clock to 23:00
        time.onTimeChanged()
        advance(1.hours - 1.milliseconds)
        assertEquals(listOf(day(17)), days)
        advance(1.milliseconds)
        assertEquals(listOf(day(17), day(18)), days)
    }

    @Test
    fun onStop_cancelsTimer_onStartRefreshesAndReArms() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T22:30:00Z"))
        val days = emissions(time)
        time.onStart()

        time.onStop()
        advance(2.hours)
        assertEquals(listOf(day(17)), days)
        time.onStart()
        assertEquals(listOf(day(17), day(18)), days)
        advance(24.hours)
        assertEquals(listOf(day(17), day(18), day(19)), days)
    }

    @Test
    fun onResume_refreshesWithoutArmingTimer() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T22:30:00Z"))
        val days = emissions(time)

        advance(2.hours)
        time.onResume()
        assertEquals(listOf(day(17), day(18)), days)
        advance(24.hours)
        assertEquals(listOf(day(17), day(18)), days)
    }

    @Test
    fun onTimeChanged_whileStopped_doesNotArmTimer() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T22:30:00Z"))
        val days = emissions(time)

        time.onTimeChanged()
        advance(24.hours)

        assertEquals(listOf(day(17)), days)
    }

    @Test
    fun repeatedRefreshesOnTheSameDate_emitNothing() = runTest(timeout = 10.seconds) {
        val time = appTime(Instant.parse("2026-11-17T12:00:00Z"))
        val days = emissions(time)

        time.onStart()
        time.onResume()
        time.onTimeChanged()
        time.onStart()
        advance(1.hours)

        assertEquals(listOf(day(17)), days)
    }

    private fun TestScope.appTime(start: Instant) = DefaultAppTime(
        instant = { start.plusMillis(testScheduler.currentTime).plus(offset) },
        zone = { zone },
        scope = backgroundScope,
    )

    /** Every value of [AppTime.todayFlow], starting with the current one, collected without delay. */
    private fun TestScope.emissions(time: AppTime): List<LocalDate> {
        val values = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { time.todayFlow.collect(values::add) }
        return values
    }

    /** Moves virtual time by [duration] and runs everything due at the new time, the midnight timer included. */
    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun day(dayOfMonth: Int): LocalDate = LocalDate.of(2026, 11, dayOfMonth)
}

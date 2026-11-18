package com.doro.organizer.testing

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeAppTimeTest {
    private val start = Instant.parse("2026-11-17T22:30:00Z") // 23:30 in Berlin, 30 minutes before local midnight
    private val time = FakeAppTime(start = start, zone = ZoneId.of("Europe/Berlin"))

    @Test
    fun clocksStandStillUntilMoved() {
        assertEquals(start.toEpochMilli(), time.nowMillis())
        assertEquals(start.toEpochMilli(), time.nowMillis())
        assertEquals(10_000_000L, time.elapsedMillis())
        assertEquals(LocalDate.of(2026, 11, 17), time.today())
    }

    @Test
    fun tickPerNowCall_returnsCurrentInstantThenMovesBothClocks() {
        time.tickPerNowCall = Duration.ofMillis(5)

        assertEquals(start.toEpochMilli(), time.nowMillis())
        assertEquals(start.toEpochMilli() + 5, time.nowMillis())
        assertEquals(10_000_010L, time.elapsedMillis())
    }

    @Test
    fun tickPerNowCall_acrossLocalMidnight_movesTodayFlow() {
        time.tickPerNowCall = Duration.ofHours(1)

        time.nowMillis()

        assertEquals(LocalDate.of(2026, 11, 18), time.today())
        assertEquals(LocalDate.of(2026, 11, 18), time.todayFlow.value)
    }

    @Test
    fun tickPerNowCall_concurrentCallers_getDistinctValues() {
        time.tickPerNowCall = Duration.ofMillis(1)
        val perThread = 1_000
        val threads = 4
        val values = ConcurrentLinkedQueue<Long>()
        val workers = List(threads) { thread { repeat(perThread) { values += time.nowMillis() } } }
        workers.forEach(Thread::join)

        assertEquals(perThread * threads, values.toSet().size)
        assertEquals(start.toEpochMilli() + perThread * threads, time.instant.toEpochMilli())
    }

    @Test
    fun advanceBy_movesWallAndMonotonicClocksAndToday() {
        time.advanceBy(Duration.ofHours(1))

        assertEquals(start.plusSeconds(3_600).toEpochMilli(), time.nowMillis())
        assertEquals(10_000_000L + 3_600_000L, time.elapsedMillis())
        assertEquals(LocalDate.of(2026, 11, 18), time.today())
        assertEquals(LocalDate.of(2026, 11, 18), time.todayFlow.value)
    }

    @Test
    fun advanceBy_negative_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { time.advanceBy(Duration.ofMillis(-1)) }
    }

    @Test
    fun setWallClock_backwards_leavesMonotonicClock() {
        time.setWallClock(Instant.parse("2026-11-01T12:00:00Z"))

        assertEquals(Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(), time.nowMillis())
        assertEquals(10_000_000L, time.elapsedMillis())
        assertEquals(LocalDate.of(2026, 11, 1), time.todayFlow.value)
    }

    @Test
    fun setElapsed_smallerValueSimulatesReboot() {
        time.setElapsed(42L)

        assertEquals(42L, time.elapsedMillis())
        assertEquals(start.toEpochMilli(), time.nowMillis())
    }

    @Test
    fun setZone_changesTodayAndDateOf() {
        time.setZone(ZoneId.of("America/New_York"))

        assertEquals(LocalDate.of(2026, 11, 17), time.today())
        time.setZone(ZoneId.of("Pacific/Kiritimati")) // UTC+14
        assertEquals(LocalDate.of(2026, 11, 18), time.today())
        assertEquals(LocalDate.of(2026, 11, 18), time.todayFlow.value)
        assertEquals(LocalDate.of(2026, 11, 18), time.dateOf(start.toEpochMilli()))
    }
}

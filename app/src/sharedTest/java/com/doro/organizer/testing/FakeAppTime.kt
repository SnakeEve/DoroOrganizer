package com.doro.organizer.testing

import com.doro.organizer.core.time.AppTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [AppTime] whose clocks stand still until a test moves them, so ties are always deliberate (testing.md § 7).
 * [advanceBy] moves both clocks, [setWallClock] only the wall clock (the user changed the time), [setElapsed] only
 * the monotonic clock (a smaller value simulates a reboot). Thread-safe: instrumented tests read it on Room and
 * dispatcher threads while the test thread moves it, and every move happens under one lock.
 */
class FakeAppTime(
    start: Instant = Instant.parse("2026-11-17T09:00:00Z"),
    zone: ZoneId = ZoneId.of("Europe/Berlin"),
) : AppTime {
    @Volatile
    var instant: Instant = start
        private set

    @Volatile
    var zone: ZoneId = zone
        private set

    @Volatile
    var elapsed: Long = 10_000_000L
        private set

    /**
     * When non-zero, every [nowMillis] call returns the current instant and then moves both clocks by this much, so
     * concurrent callers still get distinct values (testing.md I-06).
     */
    @Volatile
    var tickPerNowCall: Duration = Duration.ZERO

    private val today = MutableStateFlow(localDate())

    override val todayFlow: StateFlow<LocalDate> = today

    @Synchronized
    override fun nowMillis(): Long {
        val now = instant.toEpochMilli()
        val tick = tickPerNowCall
        if (!tick.isZero) {
            instant = instant.plus(tick)
            elapsed += tick.toMillis()
            today.value = localDate()
        }
        return now
    }

    override fun today(): LocalDate = localDate()

    override fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    override fun elapsedMillis(): Long = elapsed

    @Synchronized
    fun advanceBy(duration: Duration) {
        require(!duration.isNegative) { "time only moves forward; use setWallClock to set it back" }
        instant = instant.plus(duration)
        elapsed += duration.toMillis()
        today.value = localDate()
    }

    @Synchronized
    fun setWallClock(newInstant: Instant) {
        instant = newInstant
        today.value = localDate()
    }

    @Synchronized
    fun setElapsed(ms: Long) {
        elapsed = ms
    }

    @Synchronized
    fun setZone(newZone: ZoneId) {
        zone = newZone
        today.value = localDate()
    }

    // Not LocalDate.ofInstant: it is API 34 and minSdk is 28 (lint NewApi).
    private fun localDate(): LocalDate = instant.atZone(zone).toLocalDate()
}

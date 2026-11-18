package com.doro.organizer.core.time

import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The production [AppTime] (architecture.md § Time). The wall clock, the zone and the scope come from outside, so
 * `AppTimeTest` drives midnight, DST and zone changes in virtual time (testing.md I-30). The system clocks are read
 * only by [system] and [elapsedMillis]; `core.time` is the only package the boundary test lets read a clock.
 *
 * [todayFlow] is refreshed by the entry points: [onStart] and [onResume] (process lifecycle), [onTimeChanged] (time,
 * date and zone broadcasts) and a timer that runs from [onStart] to [onStop] and wakes at every local midnight.
 */
class DefaultAppTime(
    private val instant: () -> Instant,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
) : AppTime {
    private val lock = Any()
    private val current = MutableStateFlow(today())

    /** The midnight timer; non-null exactly between [onStart] and [onStop]. Guarded by [lock]. */
    private var timer: Job? = null

    override val todayFlow: StateFlow<LocalDate> = current.asStateFlow()

    override fun nowMillis(): Long = instant().toEpochMilli()

    // Not LocalDate.ofInstant: it is API 34 and minSdk is 28 (lint NewApi).
    override fun today(): LocalDate = instant().atZone(zone()).toLocalDate()

    override fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    /** Process ON_START: refresh and arm the midnight timer. */
    fun onStart() = synchronized(lock) {
        refresh()
        arm()
    }

    /** Process ON_STOP: cancel the midnight timer. */
    fun onStop() = synchronized(lock) {
        timer?.cancel()
        timer = null
    }

    /** Process ON_RESUME: refresh. */
    fun onResume() = refresh()

    /** The wall clock or the zone changed: refresh, and re-arm the timer while it runs, because midnight moved. */
    fun onTimeChanged() = synchronized(lock) {
        refresh()
        if (timer != null) arm()
    }

    // StateFlow drops a value equal to the current one, so only a different LocalDate is emitted.
    private fun refresh() = synchronized(lock) { current.value = today() }

    private fun arm() {
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                delay(millisUntilNextMidnight())
                refresh()
            }
        }
    }

    /**
     * At least 1 ms: midnight is a whole second strictly after now, and now's sub-millisecond part is truncated. A
     * wake-up that comes early refreshes nothing and waits again for the same midnight.
     */
    private fun millisUntilNextMidnight(): Long {
        val now = instant()
        val zone = zone()
        // atStartOfDay resolves a DST gap at midnight to the first instant of the new day (01:00 local).
        val nextMidnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return nextMidnight.toEpochMilli() - now.toEpochMilli()
    }

    companion object {
        /** The device clock in the current default zone, read on every call (DEC-26). */
        fun system(scope: CoroutineScope): DefaultAppTime =
            DefaultAppTime(instant = Instant::now, zone = ZoneId::systemDefault, scope = scope)
    }
}

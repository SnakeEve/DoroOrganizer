package com.doro.organizer.core.time

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.testing.DoroAppRule
import com.doro.organizer.testing.TestProcessLifecycle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `installProcessObservers` forwards the process lifecycle to [DefaultAppTime] (architecture.md § Time, triggers 1
 * and 2). The wall clock is `start + virtual time + offset`, so the midnight timer runs in the scope's virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppTimeWiringTest {
    private val start = Instant.parse("2026-11-17T12:00:00Z")
    private val scope = TestScope()

    @Volatile
    private var offset: Duration = Duration.ZERO
    private val time = DefaultAppTime(
        instant = { start.plusMillis(scope.testScheduler.currentTime).plus(offset) },
        zone = { ZoneOffset.UTC },
        scope = scope,
    )
    private val process = TestProcessLifecycle()

    @get:Rule
    val rule = DoroAppRule {
        applicationScope = scope
        processLifecycle = process
        appTime = time
    }

    @Test
    fun processStart_refreshesToday() {
        offset = Duration.ofDays(1)

        moveProcessTo(Lifecycle.State.STARTED)

        assertEquals(LocalDate.of(2026, 11, 18), time.todayFlow.value)
    }

    @Test
    fun processResume_refreshesToday() {
        moveProcessTo(Lifecycle.State.STARTED)
        offset = Duration.ofDays(2)

        moveProcessTo(Lifecycle.State.RESUMED)

        assertEquals(LocalDate.of(2026, 11, 19), time.todayFlow.value)
    }

    @Test
    fun processStarted_midnightTimerAdvancesToday() {
        moveProcessTo(Lifecycle.State.STARTED)

        scope.advanceTimeBy(12.hours)
        scope.runCurrent()

        assertEquals(LocalDate.of(2026, 11, 18), time.todayFlow.value)
    }

    @Test
    fun processStop_cancelsMidnightTimer() {
        moveProcessTo(Lifecycle.State.STARTED)
        scope.runCurrent() // the timer is now waiting for midnight
        moveProcessTo(Lifecycle.State.CREATED)

        scope.advanceTimeBy(24.hours)
        scope.runCurrent()

        assertEquals(LocalDate.of(2026, 11, 17), time.todayFlow.value)
    }

    private fun moveProcessTo(state: Lifecycle.State) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync { process.moveTo(state) }
}

package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.LockFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A failed settings read (DataStore I/O) in the controller's background work is logged by class name and skips that
 * run instead of crashing the app; the next tap or ON_START tries again. Instr because the log line is `android.util.Log`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsReadFailureTest {
    @Test
    fun settingsReadFailure_skipsTheUnlock_andTheNextTapPrompts() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
        f.settings.failReads = true
        f.controller.prompt()
        assertEquals(emptyList<String>(), f.events.calls)
        assertEquals(0, f.gateway.prompts.size)
        assertFalse("not a live slot when unreadable", f.controller.reportPasswordKekFailure())
        f.settings.failReads = false
        f.gateway.succeed()
        f.controller.prompt()
        assertEquals(listOf("Unlocked"), f.events.calls)
    }
}

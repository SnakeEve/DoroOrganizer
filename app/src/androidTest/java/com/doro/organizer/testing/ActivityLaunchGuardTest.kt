package com.doro.organizer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.session.GateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * The scenes refuse at once to launch or recreate `MainActivity` while the gate is `Starting`, where its splash
 * screen keeps the activity from drawing and `ActivityScenario` would wait forever ([checkNotStarting]). Without the
 * guard each of these tests would hang there, so each runs under a JUnit timeout ([GUARD_TIMEOUT_MS]): its body runs on
 * a thread of its own, which `ActivityScenario` and the Compose rule accept, and a missing guard fails the test with a
 * `TestTimedOutException` instead of stalling the run.
 */
@RunWith(Enclosed::class)
class ActivityLaunchGuardTest {
    @RunWith(AndroidJUnit4::class)
    class Scripted : ScriptedGateComposeTest() {
        @Test(timeout = GUARD_TIMEOUT_MS)
        fun aLaunchInStarting_isRefused_beforeTheGateMovesOrAnActivityStarts() {
            scene.gate.moveTo(needsPassword)

            assertRefused { scene.launch(GateState.Starting(authenticated = false)) }

            assertEquals(needsPassword, scene.gate.state.value)
            assertThrows(IllegalStateException::class.java) { scene.onActivity {} } // not launched
        }

        @Test(timeout = GUARD_TIMEOUT_MS)
        fun aRecreateInStarting_isRefused_andTheActivityStaysUp() {
            scene.launch(needsPassword)
            ui.awaitPasswordScreen()
            scene.gate.moveTo(GateState.Starting(authenticated = false))
            compose.waitForIdle()

            assertRefused { scene.recreateActivity() }
            assertRefused { scene.recreateAfterProcessDeath() }

            scene.gate.moveTo(needsPassword)
            ui.awaitPasswordScreen()
        }
    }

    @RunWith(AndroidJUnit4::class)
    class Real : GateTest() {
        @Test(timeout = GUARD_TIMEOUT_MS)
        fun launchActivity_beforeTheGateLeftStarting_isRefused() {
            assertTrue("the gate was never started", scene.gate.state.value is GateState.Starting)

            assertRefused { scene.launchActivity() }

            assertThrows(IllegalStateException::class.java) { scene.onActivity {} } // not started
        }
    }

    private companion object {
        /** Well above a launch or a recreate that is not held back, far below a stalled run. */
        const val GUARD_TIMEOUT_MS = 60_000L

        val needsPassword = GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false)

        fun assertRefused(step: () -> Unit) {
            val refusal = assertThrows(IllegalStateException::class.java) { step() }
            assertTrue(refusal.message, refusal.message.orEmpty().contains("while the gate is Starting"))
        }
    }
}

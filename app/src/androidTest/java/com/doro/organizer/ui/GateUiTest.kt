package com.doro.organizer.ui

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.PromptResult
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.ScriptedGateComposeTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * L-21, Locked UI (DEC-66, architecture.md § Rendering): outside `Ready` and a `Maintenance` from `Ready`, no
 * `NavDisplay` is composed and no note or entity text shows. [States] drives `DoroRoot` from a scripted gate;
 * [TaskSwitch] runs the real gate with the real `ProcessLifecycleOwner` over planted data.
 */
@RunWith(Enclosed::class)
class GateUiTest {
    /** Every gate state from a scripted gate (no database): which of them compose the content. */
    @RunWith(AndroidJUnit4::class)
    class States : ScriptedGateComposeTest() {
        private val messages get() = app.container.appMessages

        /**
         * Whether the content is composed, also behind the overlay, which hides it from the semantics tree: the
         * shell's snackbar effect consumes an `AppMessages` message only while it is composed (ui.md § 2.4).
         */
        private fun contentConsumesAMessage(): Boolean {
            val message = UserMessage.error(AppError.StorageFull)
            messages.post(message)
            compose.mainClock.advanceTimeBy(SNACKBAR_LONG_MS)
            compose.waitForIdle()
            val consumed = messages.pending.value.none { it.id == message.id }
            messages.consume(message.id)
            return consumed
        }

        @Test
        fun everyStateButReadyAndAMaintenanceFromReady_composesNoContent() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            assertTrue("the probe sees the content of Ready", contentConsumesAMessage())

            for (state in withoutContent) {
                scene.gate.moveTo(state)
                compose.waitForIdle()
                compose.onNodeWithTag(GateUi.TAB_ALL).assertDoesNotExist()
                compose.onNodeWithText(ALL_NOTES).assertDoesNotExist()
                assertEquals("content composed in $state", false, contentConsumesAMessage())
            }
        }

        @Test
        fun maintenanceFromCorruptKeyUnavailableOrNeedsPassword_showsTheProgressScreen() {
            scene.launch(GateState.Corrupt)
            for (origin in listOf(Origin.Corrupt, Origin.KeyUnavailable, Origin.NeedsPassword)) {
                scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.Restore, origin))
                ui.awaitTag(GateUi.MAINTENANCE)
                compose.onNodeWithText(RESTORING).assertExists()
                ui.assertNoShell()
            }
        }

        @Test
        fun lockedMaintenance_composesNoContent() {
            scene.launch(GateState.Locked(Resume.Maintenance(GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready))))
            ui.awaitLockScreen()
            assertEquals(false, contentConsumesAMessage())

            val reset = GateState.Maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry())
            scene.gate.moveTo(GateState.Locked(Resume.Maintenance(reset)))
            ui.awaitTag(GateUi.MAINTENANCE)
            ui.assertNoShell()
            assertEquals(false, contentConsumesAMessage())
        }

        @Test
        fun maintenanceFromReady_keepsTheContentUnderAnOverlay_thatConsumesPointerKeysAndBack() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            compose.onNodeWithTag(TAB_PROJECT).performClick()
            compose.onNodeWithTag(TAB_PROJECT).assertIsSelected()
            val allTab = ui.boundsOf(GateUi.TAB_ALL).center

            scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready))
            ui.awaitTag(GateUi.MAINTENANCE)
            compose.onNodeWithText(CREATING_BACKUP).assertExists()
            ui.assertNoShell() // hidden from accessibility services (ui.md § 10.6)
            assertTrue("the content stays composed under the overlay", contentConsumesAMessage())

            // Keys that would move the focus or activate a control, a tap where the All tab is, and Back.
            compose.onNodeWithTag(GateUi.MAINTENANCE).assertIsFocused()
            listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER).forEach {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(it)
            }
            compose.onNodeWithTag(GateUi.MAINTENANCE).assertIsFocused()
            compose.onRoot().performTouchInput { click(Offset(allTab.x, allTab.y)) }
            ui.pressBack() // NavDisplay would return from the Project root to All (DEC-50)

            scene.gate.moveTo(GateState.Ready)
            ui.awaitShell()
            compose.onNodeWithTag(GateUi.MAINTENANCE).assertDoesNotExist()
            compose.onNodeWithTag(TAB_PROJECT).assertIsSelected()
        }

        @Test
        fun anEraseFromReady_showsTheProgressScreenWithoutContent() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.Erase, Origin.Ready))
            ui.awaitTag(GateUi.MAINTENANCE)
            compose.onNodeWithText(ERASING).assertExists()
            ui.assertNoShell()
            assertEquals(false, contentConsumesAMessage())
        }

        private companion object {
            /** `SnackbarDuration.Long` plus the snackbar's animations (ui.md § 2.4). */
            const val SNACKBAR_LONG_MS = 11_000L
            const val TAB_PROJECT = "tab_Project"

            // Literals copied from ui.md § 11.
            const val ALL_NOTES = "All notes"
            const val CREATING_BACKUP = "Creating backup…"
            const val RESTORING = "Restoring backup…"
            const val ERASING = "Erasing all data…"

            fun entry() = PasswordEntry(PasswordReason.None, fingerprintUsable = false, failures = 0, retryAt = 0)

            /** Every gate state that shows no content (architecture.md § Rendering, `showsContent()` false). */
            val withoutContent: List<GateState> = listOf(
                GateState.Starting(authenticated = false),
                GateState.Starting(authenticated = true),
                GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false),
                GateState.Locked(Resume.Open),
                GateState.Locked(Resume.Ready),
                GateState.Locked(Resume.Ready, password = entry()),
                GateState.Locked(Resume.Corrupt),
                GateState.Locked(Resume.Corrupt, password = entry()),
                GateState.Locked(Resume.KeyUnavailable),
                GateState.Opening(viaPassword = false, lockPending = false, transientError = false),
                GateState.Opening(viaPassword = true, lockPending = false, transientError = false),
                GateState.Opening(viaPassword = false, lockPending = false, transientError = true),
                GateState.Maintenance(MaintenanceOp.Erase, Origin.Ready),
                GateState.Maintenance(MaintenanceOp.Restore, Origin.Corrupt),
                GateState.Maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable),
                GateState.Maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry()),
                GateState.Corrupt,
                GateState.KeyUnavailable,
            )
        }
    }

    /**
     * The real gate, controller and `ProcessLifecycleOwner` over a database that holds the sentinel: Home locks the
     * app, and the relaunched task shows the lock screen without any note or entity text.
     */
    @RunWith(AndroidJUnit4::class)
    class TaskSwitch : GateComposeTest(realProcessLifecycle = true) {
        @Test
        fun home_locks_andTheRelaunchedTaskShowsTheLockScreenWithoutNoteText() = runBlocking<Unit> {
            scene.plant(KeyMode.DEVICE, lock = true, sentinel = SENTINEL)
            scene.coldStart()
            awaitPrompts(1)
            ui.awaitLockScreen()
            assertEquals(0, ui.countText(SENTINEL, substring = true))
            GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }
            scene.awaitGate(GateState.Ready)
            ui.awaitShell()

            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
            scene.awaitGate(GateState.Locked(Resume.Ready)) // the process ON_STOP, 700 ms after the last activity
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            awaitPrompts(2)
            ui.awaitLockScreen()
            assertEquals("note or entity text on the lock screen", 0, ui.countText(SENTINEL, substring = true))
        }
    }
}

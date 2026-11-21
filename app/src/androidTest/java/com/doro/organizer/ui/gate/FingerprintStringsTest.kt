package com.doro.organizer.ui.gate

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.LockError
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.assertTouchTarget
import com.doro.organizer.testing.textLayout
import com.doro.organizer.testing.themeErrorColor
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-51, the gate screens' part (DEC-63, DEC-65, DEC-86; literals copied from ui.md § 11), from a scripted gate state:
 * the slot failure and the unreadable key-state (DEC-96) of the lock screen (T44), the check failure (T50) and the
 * uncounted attempt (DEC-75) of the password screen, and the password screen over a running operation. The § 8.16 dialog and the `RestoreKey` prompt are `SettingsLockUiTest`, the kekBroken
 * notice `ShellDialogsTest`.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintStringsTest : ScriptedGateComposeTest() {
    @Test
    fun slotFailed_onTheLockScreen() {
        scene.lock.lockRoute.value = UnlockRoute.Slot
        scene.launch(GateState.Locked(Resume.Open, authError = LockError(LockError.SLOT_FAILED, null)))
        ui.awaitLockScreen()
        compose.onNodeWithTag(GateUi.SLOT_FAILED_LINE).assertTextEquals(SLOT_FAILED)
        ui.awaitTag(GateUi.USE_PASSWORD)
        compose.onNodeWithTag(GateUi.UNLOCK).assertTouchTarget()
        compose.onNodeWithTag(GateUi.USE_PASSWORD).assertTouchTarget()
    }

    @Test
    fun anUnreadableKeyState_onTheLockScreen_saysSo_andHowToTryAgain() {
        scene.launch(GateState.Locked(Resume.Ready, authError = LockError(LockError.KEY_STATE_UNREADABLE, null)))
        ui.awaitLockScreen()
        val line = compose.onNodeWithTag(KEY_STATE_LINE)
        line.assertTextEquals(KEY_STATE_UNREADABLE)
        line.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag(GateUi.UNLOCK).assertTouchTarget()
    }

    @Test
    fun anUncountedAttempt_onThePasswordScreen_saysStorageIsFull_notWrongPassword() {
        val entry = PasswordEntry(PasswordReason.None, fingerprintUsable = false, failures = 3, retryAt = 0, storageFull = true)
        scene.launch(GateState.Locked(Resume.Ready, password = entry))
        ui.awaitPasswordScreen()
        ui.awaitText(STORAGE_FULL)
        compose.onNodeWithText(WRONG_PASSWORD, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun checkFailed_onThePasswordScreen_isAPoliteLiveRegion_notErrorStyled() {
        val entry = PasswordEntry(PasswordReason.None, fingerprintUsable = false, failures = 0, retryAt = 0, checkFailed = true)
        scene.launch(GateState.Locked(Resume.Ready, password = entry))
        ui.awaitPasswordScreen()
        val line = compose.onNodeWithTag(GateUi.CHECK_FAILED_LINE)
        line.assertTextEquals(CHECK_FAILED)
        line.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertNotEquals(themeErrorColor(), line.textLayout().layoutInput.style.color)
    }

    @Test
    fun overARunningOperation_theFieldAndUnlockAreDisabled_theOperationIsTheReason_andForgotIsHidden() {
        val entry = PasswordEntry(PasswordReason.Reminder, fingerprintUsable = false, failures = 0, retryAt = 0)
        scene.launch(GateState.Locked(Resume.Ready, password = entry))
        for ((op, text) in operations) {
            scene.gate.moveTo(GateState.Locked(Resume.Maintenance(GateState.Maintenance(op, Origin.Ready)), password = entry))
            ui.awaitPasswordScreen()
            ui.assertReasonLine(text)
            compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assertIsNotEnabled()
            compose.onNodeWithTag(GateUi.UNLOCK).assertIsNotEnabled()
            compose.onNodeWithTag(GateUi.FORGOT).assertDoesNotExist()
            compose.onNodeWithTag(GateUi.UNLOCK).assertTouchTarget()
        }
    }

    private companion object {
        const val SLOT_FAILED = "Fingerprint unlock did not work. Try again or use your password."
        const val CHECK_FAILED = "The password could not be checked. Try again."
        const val KEY_STATE_LINE = "gate_key_state_unreadable_text"
        const val KEY_STATE_UNREADABLE = "The app's key file could not be read. Tap \"Unlock\" to try again."
        const val STORAGE_FULL = "Not enough free storage, so the password was not checked. Free up some space and try again."
        const val WRONG_PASSWORD = "Wrong password."

        /** Each operation that can run behind a locked password screen, with its text (ui.md § 10.6). */
        val operations = listOf(
            MaintenanceOp.Backup to "Creating backup…",
            MaintenanceOp.Restore to "Restoring backup…",
            MaintenanceOp.KeyChange to "Updating encryption…",
            MaintenanceOp.StartFresh to "Starting fresh…",
        )
    }
}

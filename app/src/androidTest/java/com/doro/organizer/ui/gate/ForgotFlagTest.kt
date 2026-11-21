package com.doro.organizer.ui.gate

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.FakeKeyVault
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-52 (DEC-59, DEC-63, ui.md § 10.3): the flag that selects the Forgot password screen, driven by a scripted gate state
 * and a scripted `DbMaintenance.status`, with the failure dialogs of a failed Erase and of a failed fingerprint reset
 * that the maintenance status host shows there (ui.md § 8.12); the screen restored after Android closed the app
 * (DEC-59) and its text for a fingerprint slot that could not be looked up (DEC-77).
 */
@RunWith(AndroidJUnit4::class)
class ForgotFlagTest : ScriptedGateComposeTest() {
    @Test
    fun a_aFailedErase_returnsToTheForgotScreen() = failedEraseRoundTrip(rotateAtEachStep = false)

    @Test
    fun a_aFailedErase_returnsToTheForgotScreen_withARotationAtEachStep() = failedEraseRoundTrip(rotateAtEachStep = true)

    @Test
    fun b_keptByAnErase() = keptBy(GateState.Maintenance(MaintenanceOp.Erase, Origin.NeedsPassword))

    @Test
    fun b_keptByStarting() = keptBy(GateState.Starting(authenticated = false))

    @Test
    fun c_clearedByOpening() =
        clearedBy(GateState.Opening(viaPassword = false, lockPending = false, transientError = false))

    @Test
    fun c_clearedByReady() = clearedBy(GateState.Ready)

    @Test
    fun c_clearedByCorrupt() = clearedBy(GateState.Corrupt)

    @Test
    fun c_clearedByKeyUnavailable() = clearedBy(GateState.KeyUnavailable)

    @Test
    fun c_clearedByTheLockScreen() = clearedBy(GateState.Locked(Resume.Ready), over = lockedReadyEntry)

    @Test
    fun c_clearedByARunningFingerprintReset() {
        val reset = GateState.Maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)
        clearedBy(GateState.Locked(Resume.Maintenance(reset)), over = lockedReadyEntry)
    }

    @Test
    fun c_clearedByAKeyChange() =
        clearedBy(GateState.Maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry))

    @Test
    fun d_aStartedFingerprintReset_neverReturnsToTheForgotScreen() {
        scene.lock.fingerprintAvailable = FingerprintAvailability.Available
        scene.launch(needsPassword)
        openTheForgotScreen()
        ui.awaitTag(GateUi.FORGOT_RESET)
        compose.onNodeWithTag(GateUi.FORGOT_RESET).performClick()
        ui.awaitText(RESET_DIALOG_TITLE)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(NEW_PASSWORD)
        fields[1].performTextInput(NEW_PASSWORD)
        compose.onNodeWithText(ACKNOWLEDGE).performClick()
        scene.lock.promptResult = AppResult.Ok(FakeKeyVault().newSlotCipher())
        compose.onNodeWithText(SET_PASSWORD).performClick()

        // The reset started (the cipher went to DbMaintenance): the password screen, with the gate still NeedsPassword.
        ui.awaitPasswordScreen()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.resets.isNotEmpty() }
        assertEquals(listOf(MaintenanceOrigin.NeedsPassword), scene.maintenance.resets)
        scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry))
        ui.awaitTag(GateUi.MAINTENANCE)
        scene.lock.fingerprintAvailable = FingerprintAvailability.Unavailable // the slot became unusable meanwhile
        val failed = AppResult.Err(AppError.KeyUnavailable)
        scene.maintenance.finishReset(failed)
        scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.KeyChange, failed)
        // The opening's question, then the ViewModel's own after the failed reset (ui.md § 10.3).
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.lock.availabilityCalls.get() == 2 }
        scene.gate.moveTo(needsPassword)
        ui.awaitPasswordScreen()
        ui.awaitText(KEY_CHANGE_FAILED)
        compose.onNodeWithText(KEY_UNAVAILABLE_TEXT).assertExists()
        compose.onNodeWithText(OK).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }

        openTheForgotScreen()
        compose.onNodeWithTag(GateUi.FORGOT_RESET).assertDoesNotExist()
        compose.onNodeWithText(NOT_AVAILABLE_BODY).assertExists()
        assertEquals("a new opening asks again", 3, scene.lock.availabilityCalls.get())
    }

    @Test
    fun e_back_returnsToThePasswordScreen_andClearsTheFlag() {
        scene.launch(needsPassword)
        openTheForgotScreen()
        ui.pressBack()
        assertTheFlagIsCleared(needsPassword)
    }

    @Test
    fun e_theNavigationIcon_returnsToThePasswordScreen_andClearsTheFlag() {
        scene.launch(needsPassword)
        openTheForgotScreen()
        compose.onNodeWithContentDescription(BACK).performClick()
        assertTheFlagIsCleared(needsPassword)
    }

    @Test
    fun f_overAMaintenanceResume_thereIsNoForgotPassword() {
        val running = GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready)
        scene.launch(GateState.Locked(Resume.Maintenance(running), password = entry))
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.FORGOT).assertDoesNotExist()
    }

    @Test
    fun g_aForgotScreenRestoredAfterAndroidClosedTheApp_asksForTheAnswerAgain() {
        scene.lock.fingerprintAvailable = FingerprintAvailability.Available
        scene.launch(needsPassword)
        openTheForgotScreen()
        ui.awaitTag(GateUi.FORGOT_RESET)
        assertEquals(1, scene.lock.availabilityCalls.get())

        scene.recreateAfterProcessDeath()

        // Without an answer the screen would stay blank below its top app bar until reopened.
        ui.awaitTag(GateUi.FORGOT_RESET)
        assertEquals(2, scene.lock.availabilityCalls.get())
    }

    @Test
    fun h_aSlotThatCannotBeLookedUp_saysToTryAgainLater_insteadOfUnrecoverable() {
        scene.lock.fingerprintAvailable = FingerprintAvailability.Unknown
        scene.launch(needsPassword)
        openTheForgotScreen()

        ui.awaitText(UNKNOWN_BODY)
        compose.onNodeWithText(NOT_AVAILABLE_BODY).assertDoesNotExist()
        compose.onNodeWithTag(GateUi.FORGOT_RESET).assertDoesNotExist()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertExists()
    }

    /** (a): NeedsPassword → Maintenance(Erase, NeedsPassword) → Starting → NeedsPassword, the Erase having failed. */
    private fun failedEraseRoundTrip(rotateAtEachStep: Boolean) {
        val rotate = { if (rotateAtEachStep) scene.recreateActivity() }
        scene.launch(needsPassword)
        openTheForgotScreen()
        rotate()
        ui.awaitForgotScreen()

        confirmTheErase()
        assertEquals(listOf(MaintenanceOrigin.NeedsPassword), scene.maintenance.erases)
        scene.gate.moveTo(GateState.Maintenance(MaintenanceOp.Erase, Origin.NeedsPassword))
        ui.awaitTag(GateUi.MAINTENANCE)
        rotate()
        ui.awaitTag(GateUi.MAINTENANCE)

        scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Erase, scene.maintenance.eraseResult)
        scene.gate.moveTo(GateState.Starting(authenticated = false))
        compose.waitForIdle()
        compose.onNodeWithTag(GateUi.MAINTENANCE).assertDoesNotExist()
        // The recreated activity keeps its splash screen while the gate is Starting, so nothing waits for idle until
        // the gate has moved on.
        if (rotateAtEachStep) scene.recreateWhileStarting()

        scene.gate.moveTo(needsPassword)
        ui.awaitForgotScreen()
        ui.awaitText(ERASE_FAILED)
        rotate()
        ui.awaitForgotScreen()
        ui.awaitText(ERASE_FAILED)
        compose.onNodeWithText(UNAVAILABLE).assertExists()
        compose.onNodeWithText(OK).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        ui.awaitForgotScreen()
    }

    /** (b): the Forgot password screen over NeedsPassword survives [state] and shows again afterwards. */
    private fun keptBy(state: GateState) {
        scene.launch(needsPassword)
        openTheForgotScreen()
        scene.gate.moveTo(state)
        compose.waitForIdle()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertDoesNotExist()
        scene.gate.moveTo(needsPassword)
        ui.awaitForgotScreen()
    }

    /** (c): [state] clears the flag, so the return to the password state [over] shows the password screen. */
    private fun clearedBy(state: GateState, over: GateState = needsPassword) {
        scene.launch(over)
        openTheForgotScreen()
        scene.gate.moveTo(state)
        compose.waitForIdle()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertDoesNotExist()
        scene.gate.moveTo(over)
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertDoesNotExist()
    }

    /** On the password screen; a state that keeps the flag and the return to [over] still show the password screen. */
    private fun assertTheFlagIsCleared(over: GateState) {
        ui.awaitPasswordScreen()
        scene.gate.moveTo(GateState.Starting(authenticated = false))
        compose.waitForIdle()
        scene.gate.moveTo(over)
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertDoesNotExist()
    }

    private fun openTheForgotScreen() {
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.FORGOT).performClick()
        ui.awaitForgotScreen()
    }

    /** "Erase all data and start over", then the typed word and "Erase all data" (ui.md § 8.10). */
    private fun confirmTheErase() {
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).performClick()
        ui.awaitTag(CONFIRMATION)
        compose.onNodeWithTag(CONFIRMATION).performTextInput(ERASE_WORD)
        compose.onNodeWithText(ERASE).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.erases.isNotEmpty() }
    }

    private companion object {
        val entry = PasswordEntry(PasswordReason.None, fingerprintUsable = false, failures = 0, retryAt = 0)
        val needsPassword = GateState.NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = false)
        val lockedReadyEntry = GateState.Locked(Resume.Ready, password = entry)

        const val CONFIRMATION = "erase_confirmation"
        const val NEW_PASSWORD = "a new password"

        // Literals copied from ui.md § 11.
        const val ERASE_WORD = "ERASE"
        const val ERASE = "Erase all data"
        const val BACK = "Back"
        const val RESET_DIALOG_TITLE = "Set a new password"
        const val ACKNOWLEDGE = "I understand this cannot be recovered"
        const val SET_PASSWORD = "Set password"
        const val ERASE_FAILED = "Erase failed"
        const val UNAVAILABLE = "The operation could not run right now. Nothing was changed. Try again."
        const val KEY_CHANGE_FAILED = "Encryption was not changed"
        const val KEY_UNAVAILABLE_TEXT = "The encryption key could not be accessed, so nothing was changed. Try again later."
        const val OK = "OK"
        const val NOT_AVAILABLE_BODY = "The password cannot be recovered or reset, and without it the data in this " +
            "app cannot be opened.\n\nYou can erase all data and start over. Afterwards you can restore a backup " +
            "from Settings; an encrypted backup needs the password it was made with."
        const val UNKNOWN_BODY = "Your fingerprint could not be checked right now, so it cannot set a new password at " +
            "the moment. Try again later.\n\nIf you cannot wait, you can erase all data and start over. Afterwards you " +
            "can restore a backup from Settings; an encrypted backup needs the password it was made with."
    }
}

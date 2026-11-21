package com.doro.organizer.ui.settings

import android.app.Activity
import android.app.Instrumentation
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.security.PromptResult
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.TestDocument
import com.doro.organizer.testing.assertTouchTarget
import com.doro.organizer.testing.resetRecordingProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The biometric lock in Settings on the real gate, controller and vault with `FakeBiometricGateway` (DEC-65): L-40,
 * the Compose half (the password dialogs of ui.md § 8.16 before any prompt, Settings "Set up", a real `Busy` from a
 * restore waiting at its confirmation), the enrollment button of ui.md § 9.4, the I-50 offer and turn-on cases, and the
 * I-51 texts of the § 8.16 dialog in both variants and of the `RestoreKey` prompt.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLockUiTest : GateComposeTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearDocuments() = resetRecordingProvider(context)

    @After
    fun clearDocumentsAfter() = resetRecordingProvider(context)

    @Test
    fun passwordMode_turningTheLockOn_asksThePasswordBeforeAnyPrompt_thenArmsTheSlot() = runBlocking<Unit> {
        readyInPasswordMode(lock = false)
        openSettings()
        compose.onNodeWithTag(BIOMETRIC_ROW).performClick()

        ui.awaitTag(LOCK_PASSWORD_DIALOG)
        compose.onNodeWithText(LOCK_ON_TITLE).assertExists()
        compose.onNodeWithText(LOCK_ON_BODY).assertExists()
        assertEquals("no prompt before the password (DEC-65)", emptyList<FakeBiometricGateway.Prompt>(), scene.gateway.prompts)

        enterAndContinue(PASSWORD)
        scene.awaitPrompts(1)
        assertEquals(FakeBiometricGateway.Prompt(ENABLE_TITLE, false, CANCEL, withCrypto = true), scene.gateway.prompts.single())
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }

        // The dialog closes once the controller has written the setting and committed the slot (ui.md § 8.16).
        compose.waitUntil(GateUi.TIMEOUT_MS) { ui.countText(LOCK_ON_TITLE) == 0 }
        assertTrue(scene.settings.snapshot().biometricLock)
        assertEquals(BioSlotStatus.Live, app.container.keyVault.biometricSlotStatus())
    }

    @Test
    fun aWrongPassword_staysInTheDialog_andCountsTowardTheBackoff() = runBlocking<Unit> {
        readyInPasswordMode(lock = false)
        openSettings()
        compose.onNodeWithTag(BIOMETRIC_ROW).performClick()
        ui.awaitTag(LOCK_PASSWORD_DIALOG)
        enterAndContinue("not the password")

        ui.awaitText(WRONG_PASSWORD)
        compose.onNodeWithTag(LOCK_PASSWORD_DIALOG).assertExists()
        assertEquals(1, scene.settings.snapshot().unlockFailures)
        assertTrue(scene.gateway.prompts.isEmpty())
    }

    @Test
    fun theOffer_notNow_keepsReady_thenSettingsSetUp_asksThePasswordFirst_thenOnePrompt() = runBlocking<Unit> {
        readyInPasswordMode(lock = true)
        // A password unlock with the lock on and no slot: the set-up offer follows in the shell (ui.md § 8.13).
        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(NOT_NOW).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { ui.countText(OFFER_TITLE) == 0 }
        assertEquals(GateState.Ready, scene.gate.state.value)

        openSettings()
        ui.awaitText(SLOT_MISSING)
        compose.onNodeWithTag(SLOT_SET_UP).assertTouchTarget().performClick()
        ui.awaitTag(LOCK_PASSWORD_DIALOG)
        compose.onNodeWithText(SLOT_SETUP_TITLE).assertExists()
        compose.onNodeWithText(SLOT_SETUP_BODY).assertExists()
        assertTrue(scene.gateway.prompts.isEmpty())

        enterAndContinue(PASSWORD)
        scene.awaitPrompts(1)
        assertEquals(FakeBiometricGateway.Prompt(SLOT_TITLE, false, CANCEL, withCrypto = true), scene.gateway.prompts.single())
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }

        ui.awaitText(SLOT_CREATED)
        assertEquals(BioSlotStatus.Live, app.container.keyVault.biometricSlotStatus())
        assertTrue("the setting stays as it was", scene.settings.snapshot().biometricLock)
    }

    @Test
    fun aRestoreWaitingAtItsConfirmation_makesTheSlotBusy_andTheLockStaysOff() = runBlocking<Unit> {
        readyInPasswordMode(lock = false)
        val maintenance = app.container.dbMaintenance
        val backup = TestDocument.empty(context, "busy-restore.db")
        assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), maintenance.backupToDocument(backup.uri, BACKUP_PASSWORD.toCharArray()))
        GateScene.onMain { maintenance.acknowledge() }
        openSettings()
        compose.onNodeWithTag(BIOMETRIC_ROW).performClick()
        ui.awaitTag(LOCK_PASSWORD_DIALOG)
        enterAndContinue(PASSWORD)
        scene.awaitPrompts(1) // left open: the restore starts while it is shown

        val restore = async(start = CoroutineStart.UNDISPATCHED) { maintenance.restore(backup.uri, MaintenanceOrigin.Ready) }
        withTimeout(GateScene.TIMEOUT_MS) { maintenance.status.first { it is MaintenanceStatus.NeedsBackupPassword } }
        maintenance.answerPassword(BACKUP_PASSWORD.toCharArray())
        withTimeout(GateScene.TIMEOUT_MS) { maintenance.status.first { it is MaintenanceStatus.NeedsConfirmation } }
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }

        // The controller returns once it has written the setting back to off (L-40 (c), (e)).
        assertEquals(listOf(AppResult.Err(AppError.Busy)), scene.awaitPasswordConfirmations(1))
        assertFalse(scene.settings.snapshot().biometricLock)
        assertEquals(BioSlotStatus.None, app.container.keyVault.biometricSlotStatus())

        // The restore's confirmation covered the dialog; once it is answered, neither the dialog nor a message is left.
        ui.awaitText(RESTORE_CONFIRM_TITLE)
        compose.onNodeWithText(CANCEL).performClick()
        assertEquals(AppResult.Ok(MaintenanceResult.RestoreCancelled), restore.await())
        compose.waitUntil(GateUi.TIMEOUT_MS) { maintenance.status.value == MaintenanceStatus.Idle }
        compose.waitForIdle()
        compose.onNodeWithText(LOCK_ON_TITLE).assertDoesNotExist()
        compose.onNodeWithText(BUSY, substring = true).assertDoesNotExist()
        assertFalse(scene.settings.snapshot().biometricLock)
    }

    @Test
    fun noFingerprintEnrolled_theSwitchIsDisabled_andSetUpOpensTheEnrollment() = runBlocking<Unit> {
        scene.gateway.availability = BiometricAvailability.NoneEnrolled
        readyInPasswordMode(lock = false)
        Intents.init()
        try {
            intending(hasAction(FakeBiometricGateway.ENROLL_ACTION))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            openSettings()
            compose.onNodeWithTag(BIOMETRIC_ROW).performScrollTo().assertIsNotEnabled().assert(hasText(NONE_ENROLLED))
            compose.onNodeWithTag(ENROLL).performScrollTo().assertTouchTarget().performClick()

            // DefaultAppLockController.enroll(): the gateway's enrollment intent through ExternalActivities.
            compose.waitUntil(GateUi.TIMEOUT_MS) { Intents.getIntents().isNotEmpty() }
            intended(hasAction(FakeBiometricGateway.ENROLL_ACTION))
            assertEquals(1, Intents.getIntents().size)
            assertEquals(1, scene.gateway.enrollLaunches)
        } finally {
            Intents.release()
        }
    }

    @Test
    fun theRestoreKeyPrompt_isTheUnlockPrompt_withUsePassword() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()

        val prompt = async(start = CoroutineStart.UNDISPATCHED) {
            app.container.appLockController.slotPrompt(AuthPurpose.RestoreKey)
        }
        scene.awaitPrompts(2)
        assertEquals(FakeBiometricGateway.Prompt(UNLOCK_TITLE, false, USE_PASSWORD, withCrypto = true), scene.gateway.prompts.last())
        GateScene.onMain { scene.gateway.answer(PromptResult.Error(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "")) }
        assertEquals(AppResult.Ok(null), prompt.await())
    }

    /** `PASSWORD` mode with the biometric lock [lock] and no slot: the cold start asks the password, then Ready. */
    private suspend fun readyInPasswordMode(lock: Boolean) {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = lock)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
    }

    private fun openSettings() {
        compose.onNodeWithTag(SETTINGS_ACTION).performClick()
        ui.awaitTag(SETTINGS_LIST)
        ui.awaitTag(BIOMETRIC_ROW)
    }

    private fun enterAndContinue(password: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(password)
        compose.onNodeWithText(CONTINUE).performClick()
    }

    private companion object {
        const val PASSWORD = "correct horse"
        const val BACKUP_PASSWORD = "a separate backup passphrase"
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val BIOMETRIC_ROW = "settings_biometric"
        const val SLOT_SET_UP = "settings_slot_setup"
        const val ENROLL = "settings_enroll"
        const val LOCK_PASSWORD_DIALOG = "lock_password_dialog"

        // Literals copied from ui.md § 11.
        const val LOCK_ON_TITLE = "Turn on biometric lock?"
        const val LOCK_ON_BODY =
            "Enter your startup password. Then touch the fingerprint sensor once to open the app with your fingerprint."
        const val SLOT_SETUP_TITLE = "Set up fingerprint unlock?"
        const val SLOT_SETUP_BODY = "Enter your startup password. Then touch the fingerprint sensor once."
        const val SLOT_MISSING = "Fingerprint unlock is not set up, so the app asks for your password."
        const val ENABLE_TITLE = "Turn on biometric lock"
        const val SLOT_TITLE = "Set up fingerprint unlock"
        const val UNLOCK_TITLE = "Unlock Doro Organizer"
        const val USE_PASSWORD = "Use password"
        const val CANCEL = "Cancel"
        const val CONTINUE = "Continue"
        const val WRONG_PASSWORD = "Wrong password."
        const val OFFER_TITLE = "Set up fingerprint unlock again?"
        const val NOT_NOW = "Not now"
        const val SLOT_CREATED = "Fingerprint unlock is on"
        const val BUSY = "Another operation is still running"
        const val RESTORE_CONFIRM_TITLE = "Restore this backup?"
        const val NONE_ENROLLED = "No fingerprint or face is set up for unlocking."
    }
}

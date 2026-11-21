package com.doro.organizer.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.appIntents
import com.doro.organizer.testing.assertTouchTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's dialogs (ui.md § 2.4, § 8.13, § 9.4), from scripted controller state: the kekBroken notice and its
 * "Back up now" (I-51), the auto-off notice, the fingerprint set-up offer and its outcomes (I-50), and their priority
 * below a pending maintenance outcome and among each other.
 */
@RunWith(AndroidJUnit4::class)
class ShellDialogsTest : ScriptedGateComposeTest() {
    @Before
    fun recordIntents() = Intents.init()

    @After
    fun releaseIntents() = Intents.release()

    @Test
    fun theKekNotice_showsItsTexts_andOkDismissesIt() {
        scene.lock.kekNotice.value = true
        inReady()
        ui.awaitText(KEK_TITLE)
        compose.onNodeWithText(KEK_BODY).assertExists()
        compose.onNodeWithText(BACK_UP_NOW).assertTouchTarget()
        compose.onNodeWithText(OK).assertTouchTarget().performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { !scene.lock.kekNotice.value }
        assertEquals(listOf("dismissKekNotice"), scene.lock.calls)
    }

    @Test
    fun theKekNotice_backUpNow_dismissesIt_andStartsTheBackupInSettings() {
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        scene.lock.kekNotice.value = true
        inReady()
        ui.awaitText(KEK_TITLE)
        compose.onNodeWithText(BACK_UP_NOW).performClick()

        ui.awaitTag(SETTINGS_LIST)
        compose.waitUntil(GateUi.TIMEOUT_MS) { appIntents().isNotEmpty() }
        intended(hasAction(Intent.ACTION_CREATE_DOCUMENT))
        assertEquals(listOf("dismissKekNotice"), scene.lock.calls)
        compose.onNodeWithText(KEK_TITLE).assertDoesNotExist()
    }

    @Test
    fun theKekNotice_backUpNow_overAPlaintextBackup_warnsBeforeAnyDocument() {
        // The notice outlives a Start fresh into PLAIN: "Back up now" is the row's flow, warning included (DEC-83).
        scene.maintenance.keyMode.value = KeyMode.PLAIN
        scene.maintenance.requiresPassword = false
        scene.lock.kekNotice.value = true
        inReady()
        ui.awaitText(KEK_TITLE)
        compose.onNodeWithText(BACK_UP_NOW).performClick()

        ui.awaitText(PLAIN_TITLE)
        assertEquals("no picker before the warning", emptyList<Intent>(), appIntents())
        assertEquals(listOf("dismissKekNotice"), scene.lock.calls)
    }

    @Test
    fun theKekNotice_backUpNow_aPickerThatCannotOpen_isAFileError() {
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        scene.lock.kekNotice.value = true
        inReady()
        ui.awaitText(KEK_TITLE)
        compose.onNodeWithText(BACK_UP_NOW).performClick()
        ui.awaitTag(SETTINGS_LIST)
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.launchFailures.subscriptionCount.value > 0 }
        scene.launchFailures.tryEmit(ExternalKind.Backup)
        ui.awaitText(FILE_ERROR)
    }

    @Test
    fun aKeyChangesInlineWrongPassword_leftUnacknowledged_holdsNoNoticeBack() {
        scene.lock.slotOffer.value = SlotOffer.Missing
        scene.maintenance.status.value =
            MaintenanceStatus.Finished(MaintenanceOp.KeyChange, AppResult.Err(AppError.WrongPassword))
        inReady()
        ui.awaitText(OFFER_TITLE)
    }

    @Test
    fun aFingerprintResetsSnackbar_cutShortByARotation_keepsItsText() {
        app.container.appMessages.markFingerprintReset()
        scene.maintenance.status.value = MaintenanceStatus.Finished(
            MaintenanceOp.KeyChange,
            AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.PASSWORD)),
        )
        inReady()
        ui.awaitText(RESET_DONE)
        scene.recreateActivity()
        ui.awaitText(RESET_DONE)
        compose.onNodeWithText(PASSWORD_CHANGED).assertDoesNotExist()
        compose.waitUntil(SNACKBAR_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        assertFalse("consumed once seen", app.container.appMessages.consumeFingerprintReset())
    }

    @Test
    fun theOffer_namesWhyTheSlotIsMissing_andNotNowDeclinesIt() {
        scene.lock.slotOffer.value = SlotOffer.Missing
        inReady()
        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(OFFER_MISSING).assertExists()
        compose.onNodeWithTag(OFFER_SET_UP).assertTouchTarget().assertTextEquals(SET_UP)
        compose.onNodeWithTag(OFFER_DISMISS).assertTouchTarget().assertTextEquals(NOT_NOW).performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.lock.slotOffer.value == null }
        assertEquals(listOf("dismissSlotOffer"), scene.lock.calls)
        assertNoMessage()
    }

    @Test
    fun theOffer_afterChangedFingerprints_andACancelledPrompt_leavesNoMessage() {
        scene.lock.slotOffer.value = SlotOffer.Invalidated
        scene.lock.createSlotResult = AppResult.Ok(false)
        inReady()
        ui.awaitText(OFFER_INVALIDATED)
        compose.onNodeWithText(SET_UP).performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.lock.slotOffer.value == null }
        assertEquals(listOf("createSlot"), scene.lock.calls)
        assertNoMessage()
    }

    @Test
    fun theOffer_setUp_saysFingerprintUnlockIsOn() {
        scene.lock.slotOffer.value = SlotOffer.Missing
        scene.lock.createSlotResult = AppResult.Ok(true)
        inReady()
        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(SET_UP).performClick()
        ui.awaitText(SLOT_CREATED)
    }

    @Test
    fun theOffer_anUnknownOutcome_hasItsOwnText() {
        scene.lock.slotOffer.value = SlotOffer.Missing
        scene.lock.createSlotResult = AppResult.Err(AppError.OutcomeUnknown)
        inReady()
        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(SET_UP).performClick()
        ui.awaitText(SLOT_UNKNOWN)
    }

    @Test
    fun aPendingOutcome_comesFirst_thenTheAutoOffNotice_thenTheKekNotice_thenTheOffer() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Backup, AppResult.Err(AppError.FileAccess))
        scene.settings.setLockAutoOff()
        scene.lock.kekNotice.value = true
        scene.lock.slotOffer.value = SlotOffer.Missing
        inReady()

        ui.awaitText(BACKUP_FAILED)
        assertFalse(shown(AUTO_OFF_TITLE) || shown(KEK_TITLE) || shown(OFFER_TITLE))
        compose.onNodeWithText(OK).performClick()

        ui.awaitText(AUTO_OFF_TITLE)
        compose.onNodeWithText(AUTO_OFF_BODY).assertExists()
        assertFalse(shown(KEK_TITLE) || shown(OFFER_TITLE))
        compose.onNodeWithText(OK).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.settings.settings.value?.lockAutoOffNotice == false }

        ui.awaitText(KEK_TITLE)
        assertFalse(shown(OFFER_TITLE))
        compose.onNodeWithText(OK).performClick()

        ui.awaitText(OFFER_TITLE)
    }

    /** DEC-101 as the owner signed it off: the auto-off notice cannot be dismissed unread. */
    @Test
    fun theAutoOffNotice_closesOnlyWithOk_neverWithBackOrATapOutside() {
        runBlocking { scene.settings.setLockAutoOff() }
        inReady()
        ui.awaitText(AUTO_OFF_TITLE)

        ui.pressBack()
        tapOutside()
        compose.waitForIdle()

        compose.onNodeWithText(AUTO_OFF_TITLE).assertExists()
        assertTrue(checkNotNull(scene.settings.settings.value).lockAutoOffNotice)
        compose.onNodeWithText(OK).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.settings.settings.value?.lockAutoOffNotice == false }
        compose.onNodeWithText(AUTO_OFF_TITLE).assertDoesNotExist()
    }

    @Test
    fun aKeyChange_showsItsSnackbar_beforeTheOffer() {
        scene.lock.slotOffer.value = SlotOffer.Missing
        scene.maintenance.status.value = MaintenanceStatus.Finished(
            MaintenanceOp.KeyChange,
            AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.DEVICE, KeyMode.PASSWORD)),
        )
        inReady()
        ui.awaitText(PASSWORD_SET)
        assertFalse("the offer waits for the snackbar", shown(OFFER_TITLE))
        compose.waitUntil(SNACKBAR_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        ui.awaitText(OFFER_TITLE)
    }

    private fun inReady() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
    }

    private fun shown(text: String) = ui.countText(text) > 0

    /** A tap near the top of the screen, below the status bar: on the scrim, outside the centered dialog. */
    private fun tapOutside() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.click(device.displayWidth / 2, device.displayHeight / 10)
    }

    private fun assertNoMessage() {
        compose.waitForIdle()
        assertTrue(listOf(SLOT_CREATED, SLOT_UNKNOWN).none { shown(it) })
    }

    private companion object {
        const val SETTINGS_LIST = "settings_list"
        const val OFFER_SET_UP = "slot_offer_setup"
        const val OFFER_DISMISS = "slot_offer_dismiss"
        const val SNACKBAR_MS = 15_000L

        // Literals copied from ui.md § 11.
        const val KEK_TITLE = "Your startup password needs renewing"
        const val KEK_BODY = "This phone's secure storage lost part of the key that your startup password unlocks. " +
            "Fingerprint unlock still works. Make a backup now. Then, the next time the password is asked while the " +
            "app is open, choose \"Forgot password?\" and set a new password with your fingerprint."
        const val BACK_UP_NOW = "Back up now"
        const val OK = "OK"
        const val OFFER_TITLE = "Set up fingerprint unlock again?"
        const val OFFER_MISSING = "Fingerprint unlock is not set up for your current password. Touch the sensor once " +
            "to set it up, so the app opens with your fingerprint again."
        const val OFFER_INVALIDATED = "The fingerprints on this phone changed, so fingerprint unlock was switched off " +
            "to keep others out. Touch the sensor once to switch it on again."
        const val NOT_NOW = "Not now"
        const val SET_UP = "Set up"
        const val SLOT_CREATED = "Fingerprint unlock is on"
        const val SLOT_UNKNOWN = "Fingerprint unlock could not be confirmed, so it was left off. Your data was not changed."
        const val AUTO_OFF_TITLE = "Biometric lock turned off"
        const val AUTO_OFF_BODY = "This device no longer has a screen lock, so the biometric lock was turned off. Set " +
            "a screen lock in system settings to use it again."
        const val BACKUP_FAILED = "Backup failed"
        const val PASSWORD_SET = "Startup password set"
        const val PASSWORD_CHANGED = "Startup password changed"
        const val RESET_DONE = "New startup password set"
        const val PLAIN_TITLE = "Back up without encryption?"
        const val FILE_ERROR = "The file could not be read or written. Try again, or choose another location."
    }
}

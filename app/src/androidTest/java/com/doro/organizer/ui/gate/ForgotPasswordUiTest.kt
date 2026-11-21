package com.doro.organizer.ui.gate

import androidx.biometric.BiometricPrompt
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.PromptResult
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.captureSavedState
import com.doro.organizer.testing.corruptPage
import com.doro.organizer.testing.holdsText
import com.doro.organizer.testing.marshalled
import com.doro.organizer.ui.contentStateKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-24, Compose half (DEC-63, ui.md § 8.10, § 10.3): Erase from the Forgot password screen needs the typed word; an
 * Erase from `Locked(Ready)` never composes the content while it runs (`Maintenance(Erase, Ready)` is not
 * `showsContent()`); afterwards the gate restarts into the first-run state on the All tab, with nothing of the old
 * data epoch's content state left in the saved-instance Bundle (architecture.md § Data reset). The DbMaintenance end
 * state is asserted by the instrumented `ForgotPasswordTest`.
 *
 * I-55, Compose part (ui.md § 8.15, § 10.3): the screen asks `fingerprintUnlockAvailable()` once per opening, not
 * again after a rotation, and shows nothing below its top app bar until the answer is in; the fingerprint variant over
 * `NeedsPassword` and over `Locked(Ready)` with a password entry, the "Not available" variant without a usable slot and
 * over `Locked(Corrupt)`; both actions are disabled while the reset's prompt runs, and the "Set a new password" dialog
 * closes when the prompt ends without a cipher.
 */
@RunWith(AndroidJUnit4::class)
class ForgotPasswordUiTest : GateComposeTest() {
    @Test
    fun erase_fromThePasswordScreen_needsTheTypedWord_thenStartsOverOnAll() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD) // lock off: no fingerprint reset is offered
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()

        openTheEraseDialog()
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertDoesNotExist()
        confirmTheErase()

        scene.awaitGate(GateState.Ready)
        assertTheFirstRunStateOnAll()
    }

    @Test
    fun erase_fromTheLockedPasswordScreen_composesNoContentWhileItRuns_thenStartsOverOnAll() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true) // no slot: every unlock asks the password
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.enterPassword(PASSWORD)
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag("tab_Project").performClick()
        compose.onNodeWithTag("tab_Project").assertIsSelected()
        val oldEpoch = scene.settings.snapshot().dataEpoch
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password != null }
        ui.awaitPasswordScreen()
        scene.faultHook.blockAt(MaintenanceOp.Erase, PipelineStep.ERASE_FILES)
        scene.gateway.succeed() // the prompt without a key of the fresh Locked(Maintenance(Erase, Ready)) (T12)

        openTheEraseDialog()
        confirmTheErase()

        withTimeout(GateUi.TIMEOUT_MS) { scene.faultHook.blocked.await() }
        scene.awaitGate { it is GateState.Maintenance && it.op == MaintenanceOp.Erase && it.origin == Origin.Ready }
        ui.awaitTag(MAINTENANCE)
        compose.onNodeWithText("Erasing all data…").assertExists()
        ui.assertNoShell()
        scene.faultHook.release()

        scene.awaitGate(GateState.Ready)
        assertTheFirstRunStateOnAll()
        val epoch = scene.settings.snapshot().dataEpoch
        assertNotEquals(oldEpoch, epoch)
        val saved = captureSavedState { scene.moveActivityTo(Lifecycle.State.CREATED) }.marshalled()
        assertTrue("the content state of the new epoch is saved", saved.holdsText(contentStateKey(epoch)))
        assertFalse("the old epoch's content state is gone", saved.holdsText(contentStateKey(oldEpoch)))
    }

    @Test
    fun theFingerprintVariant_isAskedOncePerOpening_notAgainAfterARotation() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()

        openTheForgotScreen()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        assertEquals(1, scene.availabilityCalls)

        scene.recreateActivity()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        assertEquals("the activity-scoped ViewModel keeps its answer", 1, scene.availabilityCalls)

        compose.onNodeWithContentDescription("Back").performClick()
        ui.awaitPasswordScreen()
        openTheForgotScreen()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        assertEquals("a new opening asks again", 2, scene.availabilityCalls)
    }

    @Test
    fun theResetDialog_closes_whenThePromptEndsWithoutACipher() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        openTheForgotScreen()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithText(RESET_DIALOG_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(NEW_PASSWORD)
        fields[1].performTextInput(NEW_PASSWORD)
        compose.onNodeWithText("I understand this cannot be recovered").performClick()
        scene.gateway.fail(BiometricPrompt.ERROR_USER_CANCELED) // slotPrompt returns Ok(null) at once
        compose.onNodeWithText("Set password").assertIsEnabled().performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithText(RESET_DIALOG_TITLE).fetchSemanticsNodes().isEmpty()
        }
        ui.awaitTag(RESET_WITH_FINGERPRINT) // asked again: the slot is still usable
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertIsEnabled()
        assertEquals(2, scene.availabilityCalls)
        assertEquals(2, scene.gateway.prompts.size) // the cold start's and the reset's
        assertTrue(scene.gate.state.value is GateState.NeedsPassword)
    }

    @Test
    fun whileTheAnswerIsOut_nothingShowsBelowTheTopAppBar() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        scene.holdAvailability()
        compose.onNodeWithTag(GateUi.FORGOT).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.availabilityCalls == 1 }
        compose.waitForIdle()

        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertDoesNotExist()
        compose.onNodeWithTag(ERASE_START_OVER).assertDoesNotExist()
        compose.onNodeWithText(FINGERPRINT_BODY).assertDoesNotExist()
        compose.onNodeWithText(NOT_AVAILABLE_BODY).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)

        scene.releaseAvailability()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        compose.onNodeWithText(FINGERPRINT_BODY).assertExists()
    }

    @Test
    fun overTheLockedReadyPasswordEntry_theFingerprintResetComesFirst() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON) // "Use password" at the return's prompt
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.resume == Resume.Ready && it.password != null }
        ui.awaitPasswordScreen()

        openTheForgotScreen()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        compose.onNodeWithText(FINGERPRINT_BODY).assertExists()
        assertTrue(ui.boundsOf(RESET_WITH_FINGERPRINT).bottom <= ui.boundsOf(ERASE_START_OVER).top)
    }

    @Test
    fun overTheLockedCorruptPasswordEntry_onlyEraseIsOffered() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        corruptPage(scene.liveFile(), 2)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Corrupt)
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON) // "Use password" at the return's prompt
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.resume == Resume.Corrupt && it.password != null }
        ui.awaitPasswordScreen()

        openTheForgotScreen()
        compose.onNodeWithText(NOT_AVAILABLE_BODY).assertExists()
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertDoesNotExist()
        assertTrue("the slot itself is usable", app.container.appLockController.fingerprintUnlockAvailable())
    }

    @Test
    fun whileTheResetsPromptRuns_bothActionsAreDisabled() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        openTheForgotScreen()
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).performClick()
        ui.awaitText(RESET_DIALOG_TITLE)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(NEW_PASSWORD)
        fields[1].performTextInput(NEW_PASSWORD)
        compose.onNodeWithText("I understand this cannot be recovered").performClick()
        compose.onNodeWithText("Set password").performClick() // no outcome scripted: the prompt stays open

        scene.awaitPrompts(2)
        compose.waitForIdle()
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertIsNotEnabled()
        compose.onNodeWithTag(ERASE_START_OVER).assertIsNotEnabled()

        GateScene.onMain { scene.gateway.answer(PromptResult.Error(BiometricPrompt.ERROR_USER_CANCELED, "")) }
        ui.awaitTag(RESET_WITH_FINGERPRINT)
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithText(RESET_DIALOG_TITLE).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(RESET_WITH_FINGERPRINT).assertIsEnabled()
        compose.onNodeWithTag(ERASE_START_OVER).assertIsEnabled()
    }

    /** `PASSWORD` mode with the lock and a live slot; "Use password" at the cold start's prompt. */
    private suspend fun coldStartOnThePasswordScreenWithASlot() {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
    }

    private fun openTheForgotScreen() {
        compose.onNodeWithTag(GateUi.FORGOT).performClick()
        ui.awaitTag(ERASE_START_OVER)
    }

    private fun openTheEraseDialog() {
        compose.onNodeWithTag("gate_forgot").performClick()
        ui.awaitTag(ERASE_START_OVER)
        compose.onNodeWithTag(ERASE_START_OVER).performClick()
        ui.awaitTag(CONFIRMATION)
    }

    /** "Erase all data" stays disabled until the field holds the word (ui.md § 8.10), then erases. */
    private fun confirmTheErase() {
        val erase: SemanticsNodeInteraction = compose.onNodeWithText("Erase all data")
        erase.assertIsNotEnabled()
        compose.onNodeWithTag(CONFIRMATION).performTextInput("eras")
        erase.assertIsNotEnabled()
        compose.onNodeWithTag(CONFIRMATION).performTextInput("e")
        erase.assertIsEnabled().performClick()
    }

    private fun assertTheFirstRunStateOnAll() {
        ui.awaitShell()
        compose.onNodeWithTag(GateUi.TAB_ALL).assertIsSelected()
        compose.onNodeWithText("All notes").assertExists()
        assertEquals(KeyMode.PLAIN, app.container.dbMaintenance.keyMode.value)
    }

    private companion object {
        const val PASSWORD = "correct horse"
        const val ERASE_START_OVER = "forgot_erase"
        const val RESET_WITH_FINGERPRINT = "forgot_reset_fingerprint"
        const val CONFIRMATION = "erase_confirmation"
        const val MAINTENANCE = "maintenance_overlay"
        const val NEW_PASSWORD = "a new password"

        // Literals copied from ui.md § 11.
        const val RESET_DIALOG_TITLE = "Set a new password"
        const val FINGERPRINT_BODY =
            "You can set a new startup password with your fingerprint. Your notes stay as they are."
        const val NOT_AVAILABLE_BODY = "The password cannot be recovered or reset, and without it the data in this " +
            "app cannot be opened.\n\nYou can erase all data and start over. Afterwards you can restore a backup " +
            "from Settings; an encrypted backup needs the password it was made with."
    }
}

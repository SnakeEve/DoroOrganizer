package com.doro.organizer.ui.gate

import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.PromptResult
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.assertTouchTarget
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-50, the gate screens' part (DEC-63, DEC-65, DEC-74; ui.md § 10.2–10.4), on the real gate and controller with
 * `FakeBiometricGateway`: the unlock prompts of each mode, the Forgot password screen's offer, the reason line, one
 * prompt across a rotation, Back from the Forgot password screen, and the 48 dp targets of the gate buttons (I-35).
 * The set-up offer (ui.md § 8.13) and the password-first dialog (§ 8.16) are Settings and shell cases (SPEC T19).
 */
@RunWith(AndroidJUnit4::class)
class FingerprintUiTest : GateComposeTest() {
    @Test
    fun passwordMode_everyUnlockPrompt_carriesACryptoObject_andUsePassword() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        unlockAtColdStartAndOnReturn()
        assertEquals(listOf(SLOT_PROMPT, SLOT_PROMPT), scene.gateway.prompts)
    }

    @Test
    fun plainMode_everyUnlockPrompt_carriesNoCryptoObject_andCancel() = runBlocking<Unit> {
        scene.plant(KeyMode.PLAIN, lock = true)
        unlockAtColdStartAndOnReturn()
        assertEquals(listOf(STRONG_PROMPT, STRONG_PROMPT), scene.gateway.prompts)
    }

    @Test
    fun deviceMode_everyUnlockPrompt_carriesNoCryptoObject_andCancel() = runBlocking<Unit> {
        scene.plant(KeyMode.DEVICE, lock = true)
        unlockAtColdStartAndOnReturn()
        assertEquals(listOf(STRONG_PROMPT, STRONG_PROMPT), scene.gateway.prompts)
    }

    @Test
    fun theLockScreenOfTheSlotRoute_hasUnlockAndUsePassword_atLeast48dp() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.coldStart()
        scene.awaitPrompts(1)
        ui.awaitLockScreen()
        withTimeout(GateUi.TIMEOUT_MS) { app.container.appLockController.lockRoute.first { it == UnlockRoute.Slot } }
        ui.awaitTag(GateUi.USE_PASSWORD)
        compose.onNodeWithTag(GateUi.UNLOCK).assertTouchTarget()
        compose.onNodeWithTag(GateUi.USE_PASSWORD).assertTouchTarget()
    }

    @Test
    fun forgotScreen_overNeedsPassword_offersTheFingerprintResetAboveErase() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertTouchTarget()
        compose.onNodeWithTag(GateUi.UNLOCK).assertTouchTarget()
        compose.onNodeWithTag(GateUi.FORGOT).assertTouchTarget()

        openTheForgotScreen()
        ui.awaitTag(GateUi.FORGOT_RESET)
        assertTrue(ui.boundsOf(GateUi.FORGOT_RESET).bottom <= ui.boundsOf(GateUi.FORGOT_ERASE).top)
        compose.onNodeWithTag(GateUi.FORGOT_RESET).assertTouchTarget()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertTouchTarget()
    }

    @Test
    fun forgotScreen_overTheReminder_stillOffersTheFingerprintReset() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.time.advanceBy(Duration.ofDays(15)) // the reminder is due (DEC-74)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.assertReasonLine(GateUi.REMINDER_LINE)

        openTheForgotScreen()
        ui.awaitTag(GateUi.FORGOT_RESET)
    }

    @Test
    fun forgotScreen_withoutTheLock_offersOnlyErase() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()

        openTheForgotScreen() // waits for the answer: Erase shows only once it is in
        compose.onNodeWithTag(GateUi.FORGOT_RESET).assertDoesNotExist()
    }

    @Test
    fun reasonLine_noneOnAColdStartWithTheLockOff() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.assertReasonLine(null)
    }

    @Test
    fun reasonLine_noneAfterUsePassword_whileTheReminderIsNotDue() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        ui.assertReasonLine(null)
    }

    @Test
    fun reasonLine_fingerprintChanged_whenTheSlotIsDead() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.deleteBioAliases()
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.assertReasonLine(GateUi.FINGERPRINT_CHANGED_LINE)
    }

    @Test
    fun aRotationDuringACryptoObjectPrompt_showsOnePrompt() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.coldStart()
        scene.awaitPrompts(1) // no outcome scripted: the prompt stays open
        ui.awaitLockScreen()

        scene.recreateActivity()
        ui.awaitLockScreen()
        assertEquals(1, scene.gateway.prompts.size)

        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }
        scene.awaitGate(GateState.Ready)
        assertEquals(listOf(SLOT_PROMPT), scene.gateway.prompts)
    }

    @Test
    fun back_onTheForgotScreen_returnsToThePasswordScreen() = runBlocking<Unit> {
        coldStartOnThePasswordScreenWithASlot()
        openTheForgotScreen()
        ui.pressBack()
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.FORGOT_ERASE).assertDoesNotExist()
    }

    /** Lock on: the cold start's prompt opens the data, and the return from the background prompts again. */
    private suspend fun unlockAtColdStartAndOnReturn() {
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        scene.gateway.succeed()
        scene.backgroundTrip()
        scene.awaitPrompts(2)
        scene.awaitGate(GateState.Ready)
    }

    /** `PASSWORD` mode with the lock and a live slot; "Use password" at the cold start's prompt. */
    private suspend fun coldStartOnThePasswordScreenWithASlot() {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.awaitTag(GateUi.USE_FINGERPRINT)
    }

    private fun openTheForgotScreen() {
        compose.onNodeWithTag(GateUi.FORGOT).performClick()
        ui.awaitForgotScreen()
    }

    private companion object {
        const val PASSWORD = "correct horse"

        // Literals copied from ui.md § 11: `biometric_unlock_title`, `action_use_password`, `action_cancel`.
        val SLOT_PROMPT = FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Use password", withCrypto = true)
        val STRONG_PROMPT = FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Cancel", withCrypto = false)
    }
}

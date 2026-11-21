package com.doro.organizer.session

import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateUi
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-32: "Use password" at cold start and on return from background (DEC-62, DEC-65), through the password screen's
 * own controls; no `NavDisplay` until Ready.
 */
@RunWith(AndroidJUnit4::class)
class UsePasswordTest : GateComposeTest() {
    @Before
    fun passwordModeWithFingerprint() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
    }

    @Test
    fun coldStart_usePassword_opensWithThePassword_andRestartsTheReminder() = runBlocking {
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate(GateState.NeedsPassword(0, 0, false, PasswordReason.None, fingerprintUsable = true))
        ui.awaitPasswordScreen()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertExists()
        compose.onNodeWithTag(GateUi.FORGOT).assertExists()
        assertEquals("no prompt starts again on its own", 1, scene.gateway.prompts.size)
        scene.time.advanceBy(Duration.ofHours(1))
        ui.enterPassword(PASSWORD)
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(scene.time.nowMillis(), scene.settings.snapshot().passwordVerifiedAt)
        assertEquals(1, scene.gateway.prompts.size)
    }

    @Test
    fun coldStart_wrongPassword_countsTowardTheBackoff() = runBlocking {
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.enterPassword("wrong password")
        val state = scene.awaitGate { it is GateState.NeedsPassword && it.failures == 1 } as GateState.NeedsPassword
        assertEquals("the screen variant is kept", true, state.fingerprintUsable)
        assertEquals(1, scene.settings.snapshot().unlockFailures)
    }

    @Test
    fun coldStart_useFingerprint_returnsToThePrompt() = runBlocking { // T51
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        scene.gateway.succeed()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).performClick()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(2, scene.gateway.prompts.size)
        assertEquals(true, scene.gateway.prompts.last().withCrypto)
    }

    @Test
    fun returnFromBackground_usePassword_checksThePasswordOverTheOpenDatabase() = runBlocking {
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        val open = scene.holderState()
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password?.fingerprintUsable == true }
        ui.awaitPasswordScreen() // the database stays open behind it, the content is not composed (DEC-66)
        ui.enterPassword("wrong password")
        scene.awaitGate { it is GateState.Locked && it.password?.failures == 1 && it.password?.checking == false }
        scene.time.advanceBy(Duration.ofHours(1))
        ui.enterPassword(PASSWORD)
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertSame("the same Room instance: no close and reopen", open, scene.holderState())
        assertEquals(scene.time.nowMillis(), scene.settings.snapshot().passwordVerifiedAt)
    }

    @Test
    fun returnFromBackground_useFingerprint_reArmsThePrompt() = runBlocking { // T52
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password != null }
        ui.awaitPasswordScreen()
        scene.gateway.succeed()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).performClick()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(3, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

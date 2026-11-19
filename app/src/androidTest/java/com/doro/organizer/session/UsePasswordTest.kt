package com.doro.organizer.session

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.testing.GateTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** L-32 (gate half): "Use password" at cold start and on return from background (DEC-62, DEC-65). */
@RunWith(AndroidJUnit4::class)
class UsePasswordTest : GateTest() {
    @Before
    fun passwordModeWithFingerprint() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
    }

    @Test
    fun coldStart_usePassword_opensWithThePassword_andRestartsTheReminder() = runBlocking {
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate(GateState.NeedsPassword(0, 0, false, PasswordReason.None, fingerprintUsable = true))
        assertEquals("no prompt starts again on its own", 1, scene.gateway.prompts.size)
        scene.time.advanceBy(Duration.ofHours(1))
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        assertEquals(scene.time.nowMillis(), scene.settings.snapshot().passwordVerifiedAt)
        assertEquals(1, scene.gateway.prompts.size)
    }

    @Test
    fun coldStart_wrongPassword_countsTowardTheBackoff() = runBlocking {
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit("wrong password")
        val state = scene.awaitGate { it is GateState.NeedsPassword && it.failures == 1 } as GateState.NeedsPassword
        assertEquals("the screen variant is kept", true, state.fingerprintUsable)
        assertEquals(1, scene.settings.snapshot().unlockFailures)
    }

    @Test
    fun coldStart_useFingerprint_returnsToThePrompt() = runBlocking { // T51
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.gateway.succeed()
        scene.dispatch(GateEvent.UseFingerprintChosen)
        scene.awaitGate(GateState.Ready)
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
        scene.submit("wrong password")
        scene.awaitGate { it is GateState.Locked && it.password?.failures == 1 && it.password?.checking == false }
        scene.time.advanceBy(Duration.ofHours(1))
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
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
        scene.gateway.succeed()
        scene.dispatch(GateEvent.UseFingerprintChosen)
        scene.awaitGate(GateState.Ready)
        assertEquals(3, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

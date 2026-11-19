package com.doro.organizer.session

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** L-38: `PASSWORD`-mode return from background (DEC-62, DEC-65), the database open behind the lock throughout. */
@RunWith(AndroidJUnit4::class)
class PasswordModeReturnTest : GateTest() {
    private suspend fun readyThroughTheFingerprint() {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
    }

    @Test
    fun a_usableFingerprint_onePromptWithCryptoObject_backToReady() = runBlocking {
        readyThroughTheFingerprint()
        val open = scene.holderState()
        scene.gateway.succeed()
        scene.backgroundTrip()
        scene.awaitPrompts(2)
        scene.awaitGate(GateState.Ready)
        assertEquals(FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Use password", withCrypto = true), scene.gateway.prompts.last())
        assertSame(open, scene.holderState())
    }

    @Test
    fun b_noUsableFingerprint_asksThePassword_neverTheCredential() = runBlocking {
        readyThroughTheFingerprint()
        val open = scene.holderState()
        scene.gateway.availability = BiometricAvailability.NoneEnrolled
        scene.backgroundTrip()
        scene.awaitGate(GateState.Locked(Resume.Ready, password = PasswordEntry(PasswordReason.None, false, 0, 0)))
        assertEquals(0, scene.gateway.credentialPromptCount)
        assertEquals(0, scene.gateway.credentialIntentCalls)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        assertSame(open, scene.holderState())
    }

    @Test
    fun b_permanentLockout_asksThePassword() = runBlocking {
        readyThroughTheFingerprint()
        scene.gateway.fail(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password?.fingerprintUsable == false }
        assertEquals(0, scene.gateway.credentialPromptCount)
    }

    @Test
    fun b_wrongPassword_countsAndThrottles() = runBlocking {
        readyThroughTheFingerprint()
        scene.gateway.availability = BiometricAvailability.Unavailable
        scene.backgroundTrip()
        repeat(5) { attempt ->
            scene.awaitGate { it is GateState.Locked && it.password?.failures == attempt && it.password?.checking == false }
            scene.submit("wrong password")
        }
        val throttled = scene.awaitGate { it is GateState.Locked && it.password?.failures == 5 && it.password?.checking == false }
        assertEquals(scene.time.elapsedMillis() + 30_000, (throttled as GateState.Locked).password?.retryAt)
    }

    @Test
    fun c_reminderDue_asksThePasswordWithTheReason() = runBlocking {
        readyThroughTheFingerprint()
        scene.time.advanceBy(Duration.ofDays(15))
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password?.reason == PasswordReason.Reminder }
        assertEquals("no prompt for the reminder", 1, scene.gateway.prompts.size)
    }

    @Test
    fun d_lockOff_asksNothing() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = false)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.backgroundTrip()
        assertEquals(GateState.Ready, scene.gate.state.value)
        assertEquals(0, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

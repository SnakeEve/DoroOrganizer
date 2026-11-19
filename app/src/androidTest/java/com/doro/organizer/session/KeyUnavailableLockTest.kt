package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate half of L-44 (DEC-65, DEC-86): with the lock on, `Locked(KeyUnavailable)` uses the slot-less route in every
 * mode, `PASSWORD` included, because no key-state can check a key there: the strong prompt without a `CryptoObject`
 * and without "Use password", the device credential (Q7), or the auto-off; `PasswordRequired` is never raised.
 */
@RunWith(AndroidJUnit4::class)
class KeyUnavailableLockTest : GateTest() {
    @Test
    fun lockOn_invalidKeyState_locksFirst_withTheSlotlessStrongPrompt_alsoInPasswordMode() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.stateFile.writeText("{") // unreadable: the mode is unknown, so every mode behaves alike
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable)
        // Locked(KeyUnavailable) (T5) prompted once, without a CryptoObject and without "Use password" (L-44).
        assertEquals(listOf(FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Cancel", withCrypto = false)), scene.gateway.prompts)
    }

    @Test
    fun lockOn_invalidKeyState_noneEnrolled_usesTheCredential() = runBlocking {
        scene.plant(KeyMode.DEVICE, lock = true)
        vaults.stateFile.writeText("{")
        scene.gateway.availability = BiometricAvailability.NoneEnrolled
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable)
        assertEquals(1, scene.gateway.credentialPromptCount)
    }

    @Test
    fun lockOn_invalidKeyState_noSecureLockScreen_turnsTheLockOff() = runBlocking {
        scene.plant(KeyMode.PLAIN, lock = true)
        vaults.stateFile.writeText("{")
        scene.gateway.deviceSecure = false
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable)
        val settings = scene.settings.snapshot()
        assertEquals(false, settings.biometricLock)
        assertEquals(true, settings.lockAutoOffNotice)
        assertEquals(0, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

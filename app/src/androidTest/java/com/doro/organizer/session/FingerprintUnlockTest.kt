package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.PromptResult
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.openDescriptors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-31: the fingerprint alone opens at cold start, in every key mode with the lock on (DEC-65, Q8); no password screen
 * and no `NavDisplay` until Ready.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintUnlockTest : GateComposeTest() {
    @Test
    fun plainMode_oneStrongPrompt_withoutCryptoObject() = runBlocking {
        scene.plant(KeyMode.PLAIN, lock = true)
        scene.coldStart()
        scene.awaitPrompts(1)
        ui.awaitLockScreen() // neither the password screen nor NavDisplay before the prompt succeeded
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(listOf(FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Cancel", withCrypto = false)), scene.gateway.prompts)
    }

    @Test
    fun deviceMode_oneStrongPrompt_withoutCryptoObject() = runBlocking {
        scene.plant(KeyMode.DEVICE, lock = true)
        scene.coldStart()
        scene.awaitPrompts(1)
        ui.awaitLockScreen() // neither the password screen nor NavDisplay before the prompt succeeded
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(listOf(FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Cancel", withCrypto = false)), scene.gateway.prompts)
    }

    @Test
    fun passwordMode_oneSlotPrompt_opensWithoutPasswordOrDerivation() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        val verifiedAt = scene.settings.snapshot().passwordVerifiedAt
        scene.secrets.clear() // only the cold start's secrets
        val states = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            scene.gate.state.takeWhile { it != GateState.Ready }.toList()
        }
        scene.coldStart()
        scene.awaitPrompts(1)
        // L-17: nothing is open before the prompt succeeds.
        assertEquals(HolderState.Closed, scene.holderState())
        assertEquals(0, openDescriptors(scene.liveFile()))
        ui.awaitLockScreen()
        GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertTrue("never the password screen", states.await().none { it is GateState.NeedsPassword })
        assertEquals(listOf(FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Use password", withCrypto = true)), scene.gateway.prompts)
        // L-03: the DEK unwrapped from the slot is zeroed once the raw key exists; the holder keeps only the raw key.
        val deks = scene.secrets.arrays().filter { it.size == DEK_BYTES }
        assertTrue("the slot unwrapped a DEK", deks.isNotEmpty())
        deks.forEach { dek -> assertTrue("a DEK was not zeroed", dek.all { it == 0.toByte() }) }
        // The CryptoObject prompt at cold start is the PromptToOpen screen of Locked(Open) (T40, T42).
        assertTrue(scene.kdf.derivations().isEmpty())
        assertEquals("a fingerprint unlock is not a password entry (L-37)", verifiedAt, scene.settings.snapshot().passwordVerifiedAt)
    }

    @Test
    fun passwordMode_lockOff_showsThePasswordScreen_withoutAPrompt() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = false, slot = false)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        ui.assertReasonLine(null)
        assertEquals(0, scene.gateway.prompts.size)
        ui.enterPassword(PASSWORD)
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(0, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
        const val DEK_BYTES = 32
    }
}

package com.doro.organizer.session

import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.GateUi.Companion.FINGERPRINT_AVAILABLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-53 (c) (DEC-86, architecture.md T61, T62, ui.md § 12.3): the key-store password screen after a KEK failure with a
 * live slot (the setup of L-50 (e) and L-52 (c)) shows "Use fingerprint" only once the ON_START re-check finds the
 * slot route usable again, announces it once per appearance, and the button leads through the slot prompt.
 */
@RunWith(AndroidJUnit4::class)
class KeyStoreRecheckTest : GateComposeTest() {
    @Test
    fun coldStart_theRecheckOffersTheFingerprint_whichOpensTheData() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.deleteKekAlias()
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON) // "Use password" at the cold start's slot prompt
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        ui.awaitPasswordScreen()
        val states = recordUntilReady()
        val keyState = vaults.stateFile.readBytes()
        val bioKeys = vaults.bioAliases()

        meetTheKekFailure { coldStartUsable(it) == false }
        assertTheRecheck(::coldStartUsable, promptsBefore = 1)

        // T51: Locked(Open), one slot prompt with a CryptoObject, whose success opens the data with the notice.
        scene.gateway.succeed()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).performClick()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(SLOT_PROMPT, scene.gateway.prompts.last())
        assertEquals(2, scene.gateway.prompts.size)
        assertTrue("the kekBroken notice", app.container.appLockController.kekNotice.value)
        assertUnchanged(keyState, bioKeys, states.await())
    }

    @Test
    fun lockedReady_theRecheckOffersTheFingerprint_whichUnlocksTheSameDatabase() = runBlocking<Unit> {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        val open = scene.holderState()
        vaults.deleteKekAlias()
        scene.gateway.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON) // "Use password" at the return's slot prompt
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password != null }
        ui.awaitPasswordScreen()
        val states = recordUntilReady()
        val keyState = vaults.stateFile.readBytes()
        val bioKeys = vaults.bioAliases()

        meetTheKekFailure { lockedReadyUsable(it) == false }
        assertTheRecheck(::lockedReadyUsable, promptsBefore = 2)

        // T52: Locked(Ready) without the entry, one slot prompt, verifyBiometric unlocks the same Room instance.
        scene.gateway.succeed()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).performClick()
        scene.awaitGate(GateState.Ready)
        ui.awaitShell()
        assertEquals(SLOT_PROMPT, scene.gateway.prompts.last())
        assertEquals(3, scene.gateway.prompts.size)
        assertSame(open, scene.holderState())
        assertTrue("the kekBroken notice", app.container.appLockController.kekNotice.value)
        assertUnchanged(keyState, bioKeys, states.await())
    }

    /** With no usable strong biometric, the correct password meets the KEK failure (L-50 (e)). */
    private suspend fun meetTheKekFailure(keyStoreScreen: (GateState) -> Boolean) {
        scene.gateway.availability = BiometricAvailability.Unavailable
        ui.enterPassword(PASSWORD)
        scene.awaitGate(keyStoreScreen)
        ui.awaitTag(GateUi.KEK_LINE)
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertDoesNotExist()
    }

    /**
     * The re-check at each return to the foreground: still unavailable changes nothing and announces nothing; usable
     * shows the button below "Unlock" with one announcement (none after a rotation) and starts no prompt; unavailable
     * again removes it silently. [usable] reads `fingerprintUsable` of the key-store screen, null on any other state.
     */
    private suspend fun assertTheRecheck(usable: (GateState) -> Boolean?, promptsBefore: Int) {
        scene.backgroundTrip() // HW_UNAVAILABLE still
        scene.gateway.availability = BiometricAvailability.Success
        scene.backgroundTrip()
        // The re-checks run in order, so the first one's answer is in once the second one's shows.
        scene.awaitGate { usable(it) == true }
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertTrue(ui.boundsOf(GateUi.UNLOCK).bottom <= ui.boundsOf(GateUi.USE_FINGERPRINT).top)
        compose.onNodeWithTag(GateUi.KEK_LINE).assertExists()
        assertEquals("the first re-check announced nothing", 1, scene.announcements.count(FINGERPRINT_AVAILABLE))
        scene.recreateActivity()
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals(1, scene.announcements.count(FINGERPRINT_AVAILABLE))
        assertEquals("no prompt starts by itself", promptsBefore, scene.gateway.prompts.size)

        scene.gateway.availability = BiometricAvailability.Unavailable
        scene.backgroundTrip()
        scene.awaitGate { usable(it) == false }
        compose.waitForIdle()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertDoesNotExist()
        assertEquals(1, scene.announcements.count(FINGERPRINT_AVAILABLE))

        scene.gateway.availability = BiometricAvailability.Success
        scene.backgroundTrip()
        scene.awaitGate { usable(it) == true }
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals("one more for the new appearance", 2, scene.announcements.count(FINGERPRINT_AVAILABLE))

        // With the button shown, the Forgot password screen offers the fingerprint reset first.
        compose.onNodeWithTag(GateUi.FORGOT).performClick()
        ui.awaitTag(GateUi.FORGOT_RESET)
        assertTrue(ui.boundsOf(GateUi.FORGOT_RESET).bottom <= ui.boundsOf(GateUi.FORGOT_ERASE).top)
        ui.pressBack()
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals(promptsBefore, scene.gateway.prompts.size)
    }

    /** `fingerprintUsable` of `NeedsPassword(reason = KeyStoreFailed)`; null on any other state. */
    private fun coldStartUsable(state: GateState): Boolean? =
        (state as? GateState.NeedsPassword)?.takeIf { it.reason == PasswordReason.KeyStoreFailed }?.fingerprintUsable

    /** `fingerprintUsable` of the `KeyStoreFailed` password entry of `Locked(Ready)`; null on any other state. */
    private fun lockedReadyUsable(state: GateState): Boolean? = (state as? GateState.Locked)
        ?.takeIf { it.resume == Resume.Ready }?.password
        ?.takeIf { it.reason == PasswordReason.KeyStoreFailed }?.fingerprintUsable

    private fun CoroutineScope.recordUntilReady() = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        scene.gate.state.takeWhile { it != GateState.Ready }.toList()
    }

    /** `unlock_failures`, the key-state and the biometric key are untouched, and `KeyUnavailable` never showed. */
    private suspend fun assertUnchanged(keyState: ByteArray, bioKeys: Set<String>, states: List<GateState>) {
        assertEquals(0, scene.settings.snapshot().unlockFailures)
        assertArrayEquals(keyState, vaults.stateFile.readBytes())
        assertEquals(bioKeys, vaults.bioAliases())
        assertTrue(states.none { it == GateState.KeyUnavailable || it == GateState.Locked(Resume.KeyUnavailable) })
    }

    private companion object {
        const val PASSWORD = "correct horse"
        val SLOT_PROMPT = FakeBiometricGateway.Prompt("Unlock Doro Organizer", false, "Use password", withCrypto = true)
    }
}

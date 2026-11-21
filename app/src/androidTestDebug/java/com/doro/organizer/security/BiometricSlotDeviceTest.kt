package com.doro.organizer.security

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.RealBiometric
import com.doro.organizer.testing.bioKeyAliases
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `@RealBiometric` halves of L-30 and L-31 (testing.md § 2.1, § 4; security.md § 3.7, § 7): the app runs as in
 * the gate tests, but with the production `BiometricGateway` and the production key spec, so every slot prompt needs
 * an operator's touch on an unlocked phone with a PIN and exactly one enrolled fingerprint. Each prompt waits up to
 * 60 s on the wall clock; a timeout fails the test. The Orchestrator's `pm clear` deletes the Keystore entries, so
 * every test arms its own slot.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
@RealBiometric
@RunWith(AndroidJUnit4::class)
class BiometricSlotDeviceTest : GateComposeTest(realBiometrics = true) {
    @Before
    fun requireAnEnrolledStrongBiometric() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
    }

    @Test
    fun enableLock_withATouch_commitsASlotOnItsOwnAuthBoundKey() = runBlocking {
        val before = openInPasswordMode()

        armWithATouch()

        val after = state()
        assertEquals(before.generation, after.generation)
        assertTrue("the password slot is unchanged", after.slot.sameAs(before.slot))
        val alias = checkNotNull(after.bioSlot).keyAlias
        assertTrue(isSlotKeyAlias(alias))
        assertEquals("only the slot's own key (DEC-98)", setOf(alias), bioKeyAliases())
        assertTrue(scene.settings.snapshot().biometricLock)
        val info = keyInfo(alias)
        assertTrue(info.isUserAuthenticationRequired)
        assertTrue(info.isInvalidatedByBiometricEnrollment)
        assertTrue(info.isUserAuthenticationRequirementEnforcedBySecureHardware)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        }
    }

    @Test
    fun doFinal_outsideAnAuthenticatedPrompt_isRefused_asAKeptSlotFailure() = runBlocking {
        openInPasswordMode()
        armWithATouch()
        val armed = state()
        val failures = scene.settings.snapshot().unlockFailures
        val returned = scene.bio.doFinalOutputs().size
        val vault = app.container.keyVault
        val cipher = (vault.biometricUnlockCipher() as SlotCipherResult.Ready).slotCipher

        val result = vault.unlockWithBiometric(cipher)

        // Authorized, the same call would release the key: only the Keystore's refusal makes it fail.
        assertEquals("never WrongPassword, KeyFailed or a dead slot (security.md § 3.7.4)", UnlockResult.SlotFailed(invalidated = false), result)
        assertEquals("no doFinal returned anything", returned, scene.bio.doFinalOutputs().size)
        val kept = checkNotNull(state().bioSlot)
        assertTrue("the file is not touched", kept.sameAs(checkNotNull(armed.bioSlot)))
        assertEquals("the slot's key is kept", setOf(kept.keyAlias), bioKeyAliases())
        assertEquals("no backoff count", failures, scene.settings.snapshot().unlockFailures)
    }

    @Test
    fun coldStart_inPasswordMode_opensThroughTheSlot_withATouch() = runBlocking {
        openInPasswordMode()
        armWithATouch()
        val live = state()
        scene.restart()
        val derivations = scene.kdf.derivations().size
        val states = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            scene.gate.state.takeWhile { it != GateState.Ready }.toList()
        }

        scene.coldStart()
        compose.waitUntil(OPERATOR_TIMEOUT_MS) { scene.gate.state.value == GateState.Ready }

        ui.awaitShell()
        assertTrue("never the password screen", states.await().none { it is GateState.NeedsPassword })
        assertEquals("no PBKDF2 derivation", derivations, scene.kdf.derivations().size)
        assertEquals(live.generation, (scene.holderState() as HolderState.Open).generation)
        assertTrue("the slot outlives its use", checkNotNull(state().bioSlot).sameAs(checkNotNull(live.bioSlot)))
    }

    /** A `PASSWORD`-mode database without a slot, the lock off, opened with the password at a cold start. */
    private suspend fun openInPasswordMode(): KeyState {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        return state()
    }

    /**
     * Turns the lock on as Settings does in `PASSWORD` mode (DEC-65, security.md § 7.1): the password first, then one
     * strong prompt with an ENCRYPT `CryptoObject` that the operator answers.
     */
    private fun armWithATouch() {
        val lock = app.container.appLockController
        val enabled = scope.scope.async { lock.confirmWithPassword(AuthPurpose.EnableLock, PASSWORD.toCharArray()) }
        compose.waitUntil(OPERATOR_TIMEOUT_MS) { enabled.isCompleted }
        assertEquals(AppResult.Ok(ToggleOutcome.Done), enabled.getCompleted())
    }

    private suspend fun state(): KeyState = checkNotNull(app.container.keyVault.readState())

    private fun keyInfo(alias: String): KeyInfo {
        val key = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.getKey(alias, null) as SecretKey
        return SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    private companion object {
        const val PASSWORD = "correct horse"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /** testing.md § 2.1: the wall-clock wait for the operator's touch. */
        const val OPERATOR_TIMEOUT_MS = 60_000L
    }
}

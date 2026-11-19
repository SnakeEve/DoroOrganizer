package com.doro.organizer.security

import android.app.Activity
import android.provider.Settings
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.LockFixture
import com.doro.organizer.testing.lockState
import java.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-19 and L-39 (JVM halves): the re-lock rules, the exemption, the auto-prompt, the error table of security.md § 7.4,
 * the Q7 fallback by mode and the API-level branches, on the real controller over fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRulesTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.withMain() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    // Re-lock rules (§ 7.7)

    @Test
    fun onStop_withTheLockOn_raisesLockNow_withoutGracePeriod_andNeverWithTheLockOff() = runTest {
        val on = LockFixture(this)
        on.mode(KeyMode.PLAIN, lock = true)
        on.process.moveTo(Lifecycle.State.CREATED)
        assertEquals(listOf("LockNow"), on.events.calls)
        val off = LockFixture(this)
        off.mode(KeyMode.PLAIN, lock = false)
        off.backgroundTrip()
        assertTrue(off.events.calls.isEmpty())
    }

    @Test
    fun exemption_preventsTheLockOnce_withinTenMinutes() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.DEVICE, lock = true)
        for (kind in ExternalKind.entries) {
            f.controller.beforeLaunch(kind)
            f.backgroundTrip()
            assertTrue("$kind", f.events.calls.isEmpty())
            f.controller.afterResult(kind)
        }
        f.backgroundTrip() // the exemption was used up
        assertEquals(listOf("LockNow"), f.events.calls)
    }

    @Test
    fun exemption_expiredAtOnStart_locks() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.controller.beforeLaunch(ExternalKind.Restore)
        f.process.moveTo(Lifecycle.State.CREATED)
        f.time.advanceBy(Duration.ofMinutes(10).plusMillis(1))
        f.process.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("LockNow"), f.events.calls)
        f.backgroundTrip()
        assertEquals("and only once: the next trip locks as usual", listOf("LockNow", "LockNow"), f.events.calls)
    }

    @Test
    fun aLaunchThatFailed_leavesNoExemption() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.controller.beforeLaunch(ExternalKind.Enroll)
        f.controller.afterResult(ExternalKind.Enroll) // what ExternalActivities does on ActivityNotFoundException
        f.process.moveTo(Lifecycle.State.CREATED)
        assertEquals(listOf("LockNow"), f.events.calls)
    }

    // Auto-prompt (§ 7.3)

    @Test
    fun coldStart_promptsExactlyOnce_inBothOrders() = runTest {
        withMain()
        val lockedFirst = LockFixture(this)
        lockedFirst.mode(KeyMode.PLAIN, lock = true)
        lockedFirst.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = false)
        lockedFirst.attachActivity()
        assertEquals(1, lockedFirst.gateway.biometricPromptCount)
        val resumedFirst = LockFixture(this)
        resumedFirst.mode(KeyMode.PLAIN, lock = true)
        resumedFirst.attachActivity()
        resumedFirst.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = false)
        assertEquals(1, resumedFirst.gateway.biometricPromptCount)
        assertEquals(FakeBiometricGateway.Prompt("Unlock", false, "Cancel", withCrypto = false), resumedFirst.gateway.prompts.single())
    }

    @Test
    fun aLockEnteredWithAnError_waitsForTheNextOnStart_thenPromptsOnce() = runTest { // T44
        withMain()
        val f = LockFixture(this)
        f.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
        f.attachActivity()
        f.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = true)
        assertEquals(0, f.gateway.prompts.size)
        f.backgroundTrip()
        assertEquals(1, f.gateway.biometricPromptCount)
        assertTrue(f.gateway.prompts.single().withCrypto)
    }

    @Test
    fun promptInFlight_preventsASecondPrompt() = runTest {
        withMain()
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.attachActivity()
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = false)
        assertTrue(f.gateway.hasOpenPrompt)
        f.controller.prompt()
        f.backgroundTrip()
        assertEquals(1, f.gateway.prompts.size)
        f.gateway.answer(PromptResult.Succeeded(cryptoMatches = true))
        assertEquals(listOf("Unlocked"), f.events.calls.filter { it == "Unlocked" })
    }

    @Test
    fun canceled_promptsAgainAtTheNextOnStart_butUserCancelWaitsForTheButton() = runTest {
        withMain()
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.attachActivity()
        f.gateway.fail(BiometricPrompt.ERROR_USER_CANCELED)
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = false)
        assertEquals("AuthError(10, null)", f.events.calls.single())
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true) // T11 as the gate reports it
        assertEquals(1, f.gateway.prompts.size)
        f.controller.prompt() // the "Unlock" button
        assertEquals(2, f.gateway.prompts.size)
    }

    // The error table of § 7.4, strong route (PLAIN and DEVICE)

    @Test
    fun errorTable_strongRoute() = runTest {
        val silent = listOf(5, 10, 13, 3).associateWith { "AuthError($it, null)" }
        val shown = listOf(2, 4, 7, 8, 42).associateWith { "AuthError($it, error $it)" }
        for ((code, expected) in silent + shown) {
            val f = LockFixture(this)
            f.mode(KeyMode.DEVICE, lock = true)
            f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
            f.gateway.fail(code)
            f.controller.prompt()
            assertEquals("code $code", listOf(expected), f.events.calls)
        }
    }

    @Test
    fun errorTable_noBiometricCodes_startTheCredentialAtOnce() = runTest {
        for (code in listOf(1, 9, 11, 12, 15)) {
            val f = LockFixture(this)
            f.mode(KeyMode.PLAIN, lock = true)
            f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
            f.gateway.fail(code)
            f.gateway.succeed()
            f.controller.prompt()
            assertEquals("code $code", 1, f.gateway.credentialPromptCount)
            assertEquals(listOf("Unlocked"), f.events.calls)
        }
    }

    @Test
    fun noDeviceCredentialOnTheCredentialPrompt_turnsTheLockOff() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = true)
        f.gateway.availability = BiometricAvailability.NoneEnrolled
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
        f.gateway.fail(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
        f.controller.prompt()
        assertEquals("lock=false, notice=true", f.settings.snapshot().lockState())
        assertEquals(listOf("Unlocked"), f.events.calls)
    }

    @Test
    fun noSecureLockScreen_turnsTheLockOffWithTheNotice_andUnlocks() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.DEVICE, lock = true)
        f.gateway.deviceSecure = false
        f.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = true)
        f.controller.prompt()
        assertEquals("lock=false, notice=true", f.settings.snapshot().lockState())
        assertEquals(listOf("Unlocked"), f.events.calls)
        assertEquals(0, f.gateway.prompts.size)
    }

    // The Q7 fallback by mode (L-39 fallbackByMode) and by API level (DEC-15)

    @Test
    fun fallbackByMode() = runTest {
        val unusable: List<LockFixture.() -> Unit> = listOf<LockFixture.() -> Unit>(
            { gateway.availability = BiometricAvailability.NoneEnrolled },
            { gateway.availability = BiometricAvailability.Unavailable },
        ) + listOf(1, 9, 11, 12, 15).map { code -> { gateway.fail(code) } }
        for (mode in KeyMode.entries) for (screen in listOf(LockScreen.PromptToOpen, LockScreen.Prompt)) for (setup in unusable) {
            val f = LockFixture(this)
            f.mode(mode, lock = true, bio = if (mode == KeyMode.PASSWORD) BioSlotStatus.Live else BioSlotStatus.None)
            f.controller.onGateScreenChanged(screen, showsError = true)
            f.setup()
            f.gateway.succeed()
            f.controller.prompt()
            if (mode == KeyMode.PASSWORD) {
                assertEquals("$mode $screen", 0, f.gateway.credentialPromptCount)
                assertEquals(0, f.gateway.credentialIntentCalls)
                assertTrue("$mode $screen ${f.events.calls}", f.events.calls.single().startsWith("PasswordRequired"))
            } else {
                assertEquals("$mode $screen", 1, f.gateway.credentialPromptCount)
                assertEquals(listOf("Unlocked"), f.events.calls)
            }
        }
    }

    @Test
    fun strongBiometricsGone_credentialFallback_byApiLevel() = runTest {
        val api30 = LockFixture(this, sdkInt = 30)
        api30.mode(KeyMode.DEVICE, lock = true)
        api30.gateway.availability = BiometricAvailability.Unavailable
        api30.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
        api30.gateway.succeed()
        api30.controller.prompt()
        assertEquals(1, api30.gateway.credentialPromptCount)
        assertEquals(0, api30.gateway.credentialIntentCalls)
        val api29 = LockFixture(this, sdkInt = 29)
        api29.mode(KeyMode.DEVICE, lock = true)
        api29.gateway.availability = BiometricAvailability.Unavailable
        api29.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
        api29.controller.prompt()
        assertEquals(0, api29.gateway.prompts.size)
        assertEquals(1, api29.gateway.credentialIntentCalls)
        // The fake has no confirm-credential intent: no screen lock, so the lock turns itself off (§ 7.6).
        assertEquals(listOf("Unlocked"), api29.events.calls)
    }

    @Test
    fun credentialResult_okUnlocks_anyOtherCodeIsSilent() = runTest {
        val f = LockFixture(this)
        f.controller.onCredentialResult(Activity.RESULT_CANCELED)
        f.controller.onCredentialResult(Activity.RESULT_OK)
        assertEquals(listOf("AuthError(10, null)", "Unlocked"), f.events.calls)
    }

    @Test
    fun enrollAction_byApiLevel() {
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, DefaultBiometricGateway.enrollAction(30))
        @Suppress("DEPRECATION")
        assertEquals(Settings.ACTION_FINGERPRINT_ENROLL, DefaultBiometricGateway.enrollAction(29))
    }

    // Toggles in PLAIN and DEVICE mode (§ 7.1)

    @Test
    fun enableLock_runsOneStrongPrompt_neverTheCredential() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = false)
        f.gateway.succeed()
        assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirm(AuthPurpose.EnableLock))
        assertEquals(FakeBiometricGateway.Prompt("Enable", false, "Cancel", withCrypto = false), f.gateway.prompts.single())
        assertTrue(f.settings.snapshot().biometricLock)
        val unusable = LockFixture(this)
        unusable.mode(KeyMode.DEVICE, lock = false)
        unusable.gateway.availability = BiometricAvailability.NoneEnrolled
        assertEquals(AppResult.Err(AppError.AuthFailed), unusable.controller.confirm(AuthPurpose.EnableLock))
        assertEquals(0, unusable.gateway.prompts.size)
        for (code in listOf(5, 10, 13, 3)) {
            val g = LockFixture(this)
            g.mode(KeyMode.PLAIN, lock = false)
            g.gateway.fail(code)
            assertEquals(AppResult.Ok(ToggleOutcome.Cancelled), g.controller.confirm(AuthPurpose.EnableLock))
            assertFalse(g.settings.snapshot().biometricLock)
        }
    }

    @Test
    fun disableLock_acceptsTheUnlockRoute_withTheFallback() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.DEVICE, lock = true)
        f.gateway.availability = BiometricAvailability.NoneEnrolled
        f.gateway.succeed()
        assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirm(AuthPurpose.DisableLock))
        assertEquals(1, f.gateway.credentialPromptCount)
        assertFalse(f.settings.snapshot().biometricLock)
        val insecure = LockFixture(this)
        insecure.mode(KeyMode.PLAIN, lock = true)
        insecure.gateway.deviceSecure = false
        assertEquals(AppResult.Ok(ToggleOutcome.Done), insecure.controller.confirm(AuthPurpose.DisableLock))
        assertEquals(0, insecure.gateway.prompts.size)
    }

    @Test
    fun aCancelledCaller_dismissesItsPrompt_andTheNextPromptRuns() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PLAIN, lock = false)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { f.controller.confirm(AuthPurpose.EnableLock) }
        assertTrue(f.gateway.hasOpenPrompt)
        waiting.cancelAndJoin()
        assertEquals(1, f.gateway.cancelled)
        assertFalse(f.gateway.hasOpenPrompt)
        f.gateway.succeed()
        assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirm(AuthPurpose.EnableLock))
        assertEquals(2, f.gateway.prompts.size)
    }
}

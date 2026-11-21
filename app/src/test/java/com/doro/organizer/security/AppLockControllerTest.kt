package com.doro.organizer.security

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.LockFixture
import com.doro.organizer.testing.lockState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM halves of L-50 (f), L-51 (i), L-52 (b), L-53 (b), L-54 (a) and the offer and toggle rules of security.md
 * § 7.1, § 7.3 and § 7.8 (L-40 controller part), on the real controller over fakes.
 */
class AppLockControllerTest {
    private suspend fun LockFixture.passwordMode(bio: BioSlotStatus = BioSlotStatus.Live, lock: Boolean = true, reminder: Boolean = false) {
        mode(KeyMode.PASSWORD, lock, bio)
        vault.reminderDue = reminder
    }

    // L-50 (f), L-54 (a): the kekBroken flag

    @Test
    fun kekBrokenIgnoresReminder() = runTest {
        val f = LockFixture(this)
        f.passwordMode(reminder = true)
        assertEquals(UnlockRoute.Password(PasswordReason.Reminder, false), f.controller.unlockRoute())
        f.controller.reportKekBroken()
        assertEquals(UnlockRoute.Slot, f.controller.unlockRoute())
        assertTrue(f.controller.kekNotice.value)
        f.controller.dismissKekNotice()
        assertFalse(f.controller.kekNotice.value)
        assertEquals("dismissing the notice keeps the route flag", UnlockRoute.Slot, f.controller.unlockRoute())
        f.controller.clearKekBroken()
        assertEquals(UnlockRoute.Password(PasswordReason.Reminder, false), f.controller.unlockRoute())
        assertTrue(f.controller.reportPasswordKekFailure())
        assertEquals(UnlockRoute.Slot, f.controller.unlockRoute())
    }

    @Test
    fun clearKekBroken_clearsNoticeAndFlag_afterEitherSource() = runTest {
        for (source in listOf("reportKekBroken", "reportPasswordKekFailure")) {
            val f = LockFixture(this)
            f.passwordMode(reminder = true)
            if (source == "reportKekBroken") f.controller.reportKekBroken() else assertTrue(f.controller.reportPasswordKekFailure())
            f.controller.clearKekBroken()
            assertFalse(f.controller.kekNotice.value)
            assertEquals(source, UnlockRoute.Password(PasswordReason.Reminder, false), f.controller.unlockRoute())
            assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.RestoreKey))
            assertEquals(0, f.gateway.biometricPromptCount)
            // A slot-route prompt error 1 maps to None again, not KeyStoreFailed.
            f.vault.reminderDue = false
            f.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = false)
            f.gateway.fail(BiometricPrompt.ERROR_HW_UNAVAILABLE)
            f.controller.prompt()
            assertEquals(source, "PasswordRequired(None, false)", f.events.calls.last())
        }
    }

    @Test
    fun clearKekBroken_withNothingSet_changesNothing() = runTest {
        val f = LockFixture(this)
        f.passwordMode()
        f.controller.clearKekBroken()
        assertFalse(f.controller.kekNotice.value)
        assertEquals(UnlockRoute.Slot, f.controller.unlockRoute())
    }

    // L-51 (i): the restore key prompt

    @Test
    fun restoreKey_withTheReminderDue_promptsOnlyWhileTheFlagIsSet() = runTest {
        for (source in listOf("reportKekBroken", "reportPasswordKekFailure")) {
            val f = LockFixture(this)
            f.passwordMode(reminder = true)
            assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.RestoreKey))
            assertEquals(0, f.gateway.biometricPromptCount)
            if (source == "reportKekBroken") f.controller.reportKekBroken() else assertTrue(f.controller.reportPasswordKekFailure())
            f.gateway.succeed()
            val result = f.controller.slotPrompt(AuthPurpose.RestoreKey)
            assertTrue(source, result is AppResult.Ok && result.value != null)
            assertEquals(FakeBiometricGateway.Prompt("Unlock", false, "Use password", withCrypto = true), f.gateway.prompts.single())
        }
    }

    @Test
    fun restoreKey_answersOkNull_forEveryNonSuccess() = runTest {
        val outcomes = listOf(5, 10, 13, 3, 7, 2, 1, 9, 11, 12, 15).map { PromptResult.Error(it, "error $it") } +
            PromptResult.Succeeded(cryptoMatches = false)
        for (outcome in outcomes) {
            val f = LockFixture(this)
            f.passwordMode()
            f.gateway.next(outcome)
            assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.RestoreKey))
            assertEquals(1, f.gateway.biometricPromptCount)
        }
    }

    @Test
    fun restoreKey_neverPrompts_withoutALiveFingerprintRoute() = runTest {
        val setups: List<suspend LockFixture.() -> Unit> = listOf(
            { passwordMode(lock = false) },
            { passwordMode(bio = BioSlotStatus.Dead) },
            { passwordMode(bio = BioSlotStatus.Unknown) },
            { passwordMode(bio = BioSlotStatus.None) },
            { passwordMode(); gateway.availability = BiometricAvailability.Unavailable },
            { passwordMode(); gateway.deviceSecure = false },
        )
        for (setup in setups) {
            val f = LockFixture(this)
            f.setup()
            f.controller.reportKekBroken()
            assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.RestoreKey))
            assertEquals(0, f.gateway.biometricPromptCount)
        }
    }

    @Test
    fun reportPasswordKekFailure_isTrueExactlyForALiveSlot_andNeverCounts() = runTest {
        val cases: List<Triple<KeyMode, Boolean, BioSlotStatus>> = listOf(
            Triple(KeyMode.PASSWORD, true, BioSlotStatus.Live),
            Triple(KeyMode.PASSWORD, false, BioSlotStatus.Live),
            Triple(KeyMode.PASSWORD, true, BioSlotStatus.Dead),
            Triple(KeyMode.PASSWORD, true, BioSlotStatus.Unknown), // DEC-77: never loops back to the slot
            Triple(KeyMode.PASSWORD, true, BioSlotStatus.None),
            Triple(KeyMode.DEVICE, true, BioSlotStatus.None),
        )
        for ((mode, lock, bio) in cases) {
            val f = LockFixture(this)
            f.mode(mode, lock, bio)
            val live = f.controller.reportPasswordKekFailure()
            assertEquals("$mode $lock $bio", mode == KeyMode.PASSWORD && lock && bio == BioSlotStatus.Live, live)
            assertEquals(live, f.controller.kekNotice.value)
            assertEquals(0, f.gateway.prompts.size)
            assertTrue(f.vault.calls.none { it in setOf("unlock", "verifyPassword", "openKey", "biometricUnlockCipher") })
        }
    }

    // L-52 (b): the key-store reason

    @Test
    fun keyStoreReason_onThePasswordRoute_whileTheFlagIsSetOverALiveSlot() = runTest {
        val unusable: List<LockFixture.() -> Unit> = listOf(
            { gateway.availability = BiometricAvailability.Unavailable },
            { gateway.availability = BiometricAvailability.NoneEnrolled },
            { gateway.deviceSecure = false },
        )
        for (makeUnusable in unusable) {
            val f = LockFixture(this)
            f.passwordMode(reminder = true)
            f.makeUnusable()
            assertEquals(UnlockRoute.Password(PasswordReason.None, false), f.controller.unlockRoute())
            f.controller.reportKekBroken()
            assertEquals(UnlockRoute.Password(PasswordReason.KeyStoreFailed, false), f.controller.unlockRoute())
            f.controller.dismissKekNotice()
            assertEquals(UnlockRoute.Password(PasswordReason.KeyStoreFailed, false), f.controller.unlockRoute())
        }
        val f = LockFixture(this)
        f.passwordMode(reminder = true)
        f.controller.reportKekBroken()
        assertEquals("usable: the slot, also with the reminder due", UnlockRoute.Slot, f.controller.unlockRoute())
    }

    @Test
    fun keyStoreReason_dependsOnTheSlotAndTheLock() = runTest {
        val expectations = listOf(
            Triple(BioSlotStatus.Dead, true, UnlockRoute.Password(PasswordReason.FingerprintChanged, false)),
            // DEC-77: a slot that could not be looked up may still be good, so no text says the fingerprints changed.
            Triple(BioSlotStatus.Unknown, true, UnlockRoute.Password(PasswordReason.None, false)),
            Triple(BioSlotStatus.None, true, UnlockRoute.Password(PasswordReason.None, false)),
            Triple(BioSlotStatus.Live, false, UnlockRoute.Password(PasswordReason.None, false)),
        )
        for ((bio, lock, expected) in expectations) {
            val f = LockFixture(this)
            f.passwordMode(bio = bio, lock = lock)
            f.controller.reportKekBroken()
            assertEquals("$bio $lock", expected, f.controller.unlockRoute())
        }
    }

    @Test
    fun keyStoreReason_mapsTheNoBiometricPromptErrors_butNotUsePassword() = runTest {
        for (code in listOf(1, 9, 11, 12, 15, 13)) {
            for (flag in listOf(true, false)) {
                val f = LockFixture(this)
                f.passwordMode()
                if (flag) f.controller.reportKekBroken()
                f.controller.onGateScreenChanged(LockScreen.PromptToOpen, showsError = false)
                f.gateway.fail(code)
                f.controller.prompt()
                val expected = when {
                    code == 13 -> "PasswordRequired(None, true)"
                    flag -> "PasswordRequired(KeyStoreFailed, false)"
                    else -> "PasswordRequired(None, false)"
                }
                assertEquals("code $code flag $flag", expected, f.events.calls.single())
            }
        }
    }

    // L-53 (b): the ON_START re-check

    @Test
    fun keyStoreRecheck_atOnStart_reportsWhetherTheSlotRouteIsUsable() = runTest {
        val cases: List<Pair<LockFixture.() -> Unit, Boolean>> = listOf(
            Pair({ }, true),
            Pair({ gateway.availability = BiometricAvailability.StatusUnknown }, true),
            Pair({ vault.reminderDue = true }, true),
            Pair({ controller.beforeLaunch(ExternalKind.Backup) }, true),
            Pair({ gateway.availability = BiometricAvailability.Unavailable }, false),
            Pair({ gateway.availability = BiometricAvailability.NoneEnrolled }, false),
            Pair({ gateway.deviceSecure = false }, false),
            Pair({ vault.bioStatus = BioSlotStatus.Dead }, false),
            Pair({ vault.bioStatus = BioSlotStatus.Unknown }, false),
        )
        for ((setup, usable) in cases) {
            val f = LockFixture(this)
            f.passwordMode()
            f.controller.reportKekBroken()
            f.controller.onGateScreenChanged(LockScreen.Password, showsError = false)
            f.setup()
            f.process.moveTo(Lifecycle.State.CREATED)
            f.events.clear() // the ON_STOP lock is the re-lock rule's, not the re-check's
            f.process.moveTo(Lifecycle.State.RESUMED)
            assertEquals(listOf("Rechecked($usable)"), f.events.calls)
            assertEquals(0, f.gateway.prompts.size)
            assertFalse(f.vault.calls.contains("biometricUnlockCipher"))
        }
    }

    @Test
    fun keyStoreRecheck_needsTheFlagAndThePasswordScreen() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false)
        f.controller.onGateScreenChanged(LockScreen.Password, showsError = false)
        f.backgroundTrip()
        f.controller.reportKekBroken()
        f.controller.clearKekBroken()
        f.backgroundTrip()
        f.controller.reportKekBroken()
        for (screen in listOf(LockScreen.Content, LockScreen.Prompt, LockScreen.PromptToOpen, LockScreen.PromptWithoutKey, LockScreen.Other)) {
            f.controller.onGateScreenChanged(screen, showsError = true) // an error state, so nothing auto-prompts
            f.backgroundTrip()
        }
        assertTrue(f.events.calls.none { it.startsWith("Rechecked") })
    }

    // § 7.8: the re-creation offer, L-40 (g)

    @Test
    fun offer_isPublishedAfterAPasswordUnlock_forAMissingOrDeadSlot_onlyInContent() = runTest {
        val expectations = listOf(
            BioSlotStatus.None to SlotOffer.Missing,
            BioSlotStatus.Dead to SlotOffer.Invalidated,
            BioSlotStatus.Live to null,
            BioSlotStatus.Unknown to null, // DEC-77: no set-up while the slot may still be good
        )
        for ((bio, expected) in expectations) {
            val f = LockFixture(this)
            f.passwordMode(bio = bio)
            f.controller.onGateScreenChanged(LockScreen.Content, showsError = false)
            f.controller.offerSlotSetup()
            assertEquals("$bio", expected, f.controller.slotOffer.value)
        }
        val locked = LockFixture(this)
        locked.passwordMode(bio = BioSlotStatus.None)
        locked.controller.onGateScreenChanged(LockScreen.Prompt, showsError = false)
        locked.controller.offerSlotSetup()
        assertNull(locked.controller.slotOffer.value)
    }

    @Test
    fun createSlot_withoutAnOffer_promptsNothing_andTheOfferEndsWithTheContent() = runTest {
        val f = LockFixture(this)
        f.passwordMode(bio = BioSlotStatus.None)
        assertEquals(AppResult.Ok(false), f.controller.createSlot())
        f.controller.onGateScreenChanged(LockScreen.Content, showsError = false)
        f.controller.offerSlotSetup()
        assertEquals(SlotOffer.Missing, f.controller.slotOffer.value)
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = false) // LockNow
        assertNull(f.controller.slotOffer.value)
        assertEquals(AppResult.Ok(false), f.controller.createSlot())
        assertEquals(0, f.gateway.prompts.size)
    }

    @Test
    fun createSlot_promptsOnceWithTheArmCipher_commits_andClearsTheOffer() = runTest {
        val f = LockFixture(this)
        f.passwordMode(bio = BioSlotStatus.Dead)
        f.controller.onGateScreenChanged(LockScreen.Content, showsError = false)
        f.controller.offerSlotSetup()
        f.gateway.succeed()
        assertEquals(AppResult.Ok(true), f.controller.createSlot())
        assertEquals(FakeBiometricGateway.Prompt("Set up", false, "Cancel", withCrypto = true), f.gateway.prompts.single())
        assertEquals(listOf("create(lock=true)"), f.slots.calls)
        assertNull(f.controller.slotOffer.value)
    }

    @Test
    fun reconcileLock_removesAStaleSlot_andTurnsThePasswordModeLockOffWithoutASecureScreen() = runTest {
        val stale = LockFixture(this)
        stale.passwordMode(lock = false)
        stale.controller.reconcileLock()
        assertEquals(listOf("remove(lock=false)"), stale.slots.calls)
        val insecure = LockFixture(this)
        insecure.passwordMode()
        insecure.gateway.deviceSecure = false
        insecure.controller.reconcileLock()
        assertEquals("lock=false, notice=true", insecure.settings.snapshot().lockState())
        assertEquals("the setting first", listOf("remove(lock=false)"), insecure.slots.calls)
        val fine = LockFixture(this)
        fine.passwordMode()
        fine.controller.reconcileLock()
        assertTrue(fine.slots.calls.isEmpty())
    }

    @Test
    fun reconcileLock_deletesNothing_whileTheSlotCannotBeLookedUp() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.Unknown)

        f.controller.reconcileLock()

        assertTrue("DEC-77: nothing is deleted on an unknown answer", f.slots.calls.isEmpty())
    }

    @Test
    fun availability_ofASlotThatCannotBeLookedUp_isUnknown_neverMissing() = runTest {
        val f = LockFixture(this)
        f.passwordMode(bio = BioSlotStatus.Unknown)

        assertEquals(SlotState.Unknown, f.controller.availability().slot)
    }

    // L-40, controller part: the PASSWORD-mode toggles (security.md § 7.1)

    @Test
    fun disableLock_inPasswordMode_mapsEveryPromptOutcome() = runTest {
        val cases = listOf(13 to ToggleOutcome.PasswordRequired, 5 to ToggleOutcome.Cancelled, 10 to ToggleOutcome.Cancelled,
            3 to ToggleOutcome.Cancelled, 1 to ToggleOutcome.PasswordRequired, 9 to ToggleOutcome.PasswordRequired,
            11 to ToggleOutcome.PasswordRequired, 12 to ToggleOutcome.PasswordRequired, 15 to ToggleOutcome.PasswordRequired)
        for ((code, outcome) in cases) {
            val f = LockFixture(this)
            f.passwordMode()
            f.gateway.fail(code)
            assertEquals("code $code", AppResult.Ok(outcome), f.controller.confirm(AuthPurpose.DisableLock))
            assertEquals(0, f.gateway.credentialPromptCount)
        }
        val missing = LockFixture(this)
        missing.passwordMode(bio = BioSlotStatus.None)
        assertEquals(AppResult.Ok(ToggleOutcome.PasswordRequired), missing.controller.confirm(AuthPurpose.DisableLock))
        assertEquals(0, missing.gateway.prompts.size)
        val ok = LockFixture(this)
        ok.passwordMode()
        ok.gateway.succeed()
        assertEquals(AppResult.Ok(ToggleOutcome.Done), ok.controller.confirm(AuthPurpose.DisableLock))
        assertEquals(FakeBiometricGateway.Prompt("Disable", false, "Use password", withCrypto = true), ok.gateway.prompts.single())
        assertEquals(listOf("remove(lock=false)"), ok.slots.calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmWithPassword_outsidePasswordMode_isAProgrammingError() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.DEVICE, lock = false)
        f.controller.confirmWithPassword(AuthPurpose.EnableLock, "pw".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmWithPassword_withAnotherPurpose_isAProgrammingError() = runTest {
        val f = LockFixture(this)
        f.passwordMode()
        f.controller.confirmWithPassword(AuthPurpose.Unlock, "pw".toCharArray())
    }

    // L-42 (controller part): fingerprintUnlockAvailable and the reset prompt

    @Test
    fun fingerprintUnlockAvailable_ignoresTheReminder() = runTest {
        val f = LockFixture(this)
        f.passwordMode(reminder = true)
        assertEquals(FingerprintAvailability.Available, f.controller.fingerprintUnlockAvailable())
        f.vault.bioStatus = BioSlotStatus.Dead
        assertEquals(FingerprintAvailability.Unavailable, f.controller.fingerprintUnlockAvailable())
    }

    @Test
    fun fingerprintUnlockAvailable_ofASlotThatCannotBeLookedUp_isUnknown_onlyWhereTheSlotRouteWouldBeOpen() = runTest {
        val f = LockFixture(this)
        f.passwordMode(bio = BioSlotStatus.Unknown)
        assertEquals(FingerprintAvailability.Unknown, f.controller.fingerprintUnlockAvailable())
        f.gateway.availability = BiometricAvailability.Unavailable
        assertEquals(FingerprintAvailability.Unavailable, f.controller.fingerprintUnlockAvailable())
    }

    @Test
    fun resetPassword_cancelIsOkNull_otherErrorsAndAForeignCipherAreAuthFailed() = runTest {
        for (code in listOf(5, 10, 13, 3)) {
            val f = LockFixture(this)
            f.passwordMode()
            f.gateway.fail(code)
            assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.ResetPassword))
        }
        for (outcome in listOf(PromptResult.Error(7, "x"), PromptResult.Error(1, "x"), PromptResult.Succeeded(cryptoMatches = false))) {
            val f = LockFixture(this)
            f.passwordMode()
            f.gateway.next(outcome)
            assertEquals(AppResult.Err(AppError.AuthFailed), f.controller.slotPrompt(AuthPurpose.ResetPassword))
        }
        val f = LockFixture(this)
        f.passwordMode()
        f.vault.nextUnlockCipher.addLast(SlotCipherResult.Invalidated)
        assertEquals(AppResult.Ok(null), f.controller.slotPrompt(AuthPurpose.ResetPassword))
        assertEquals(0, f.gateway.prompts.size)
        val ok = LockFixture(this)
        ok.passwordMode()
        ok.gateway.succeed()
        assertTrue((ok.controller.slotPrompt(AuthPurpose.ResetPassword) as AppResult.Ok).value != null)
        assertEquals(FakeBiometricGateway.Prompt("Reset", false, "Cancel", withCrypto = true), ok.gateway.prompts.single())
    }

    // Fail closed (security.md § 5.1, § 7.5; architecture.md § Invariants 7)

    @Test
    fun passwordMode_lockTurnedOffWhileLocked_asksThePassword_otherModesUnlock() = runTest {
        for (mode in KeyMode.entries) {
            val f = LockFixture(this)
            f.mode(mode, lock = false, bio = if (mode == KeyMode.PASSWORD) BioSlotStatus.Live else BioSlotStatus.None)
            f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
            f.controller.prompt()
            val expected = if (mode == KeyMode.PASSWORD) "PasswordRequired(None, false)" else "Unlocked"
            assertEquals("$mode", listOf(expected), f.events.calls)
            assertEquals(0, f.gateway.prompts.size)
        }
    }

    @Test
    fun unreadableKeyState_overAKeyScreen_neverUnlocks_butPromptWithoutKeyDoes() = runTest {
        for (screen in listOf(LockScreen.Prompt, LockScreen.PromptToOpen)) for (secure in listOf(true, false)) {
            val f = LockFixture(this)
            f.passwordMode()
            f.vault.readFailure = KeyVaultException(KeyFailure.InvalidState)
            f.gateway.deviceSecure = secure
            f.gateway.availability = BiometricAvailability.NoneEnrolled // would be the credential, or AutoOff
            f.controller.onGateScreenChanged(screen, showsError = true)
            f.controller.prompt()
            // DEC-96 as the owner signed it off: a line says so, no longer the silent cancel (5).
            assertEquals("$screen secure=$secure", listOf("AuthError(${LockError.KEY_STATE_UNREADABLE}, null)"), f.events.calls)
            assertEquals(0, f.gateway.prompts.size)
            assertTrue("no auto-off", f.settings.snapshot().biometricLock)
            assertEquals(UnlockRoute.Biometric, f.controller.lockRoute.value)
        }
        val f = LockFixture(this)
        f.passwordMode()
        f.vault.readFailure = KeyVaultException(KeyFailure.InvalidState)
        f.controller.onGateScreenChanged(LockScreen.PromptWithoutKey, showsError = true)
        f.gateway.succeed()
        f.controller.prompt()
        assertEquals(listOf("Unlocked"), f.events.calls)
        assertEquals(FakeBiometricGateway.Prompt("Unlock", false, "Cancel", withCrypto = false), f.gateway.prompts.single())
    }

    // Failed settings writes

    @Test
    fun toggleSettingWriteFailure_isStorageFull() = runTest {
        for (purpose in listOf(AuthPurpose.EnableLock, AuthPurpose.DisableLock)) {
            val f = LockFixture(this)
            f.mode(KeyMode.PLAIN, lock = purpose == AuthPurpose.DisableLock)
            f.gateway.succeed()
            f.settings.failEdits = true
            assertEquals("$purpose", AppResult.Err(AppError.StorageFull), f.controller.confirm(purpose))
        }
    }
}

package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.LockFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-40 (controller half, Instr because the password runs through `passwordBytes` and NFC needs `android.icu`): the
 * password-first lock toggles of `PASSWORD` mode (DEC-65, security.md § 7.1), and the `confirmWithPassword` case of
 * L-54 (a), on the real controller over the fakes of testing.md § 7.
 */
@RunWith(AndroidJUnit4::class)
class LockTogglePasswordModeTest {
    private suspend fun LockFixture.passwordMode(lock: Boolean, bio: BioSlotStatus) = mode(KeyMode.PASSWORD, lock, bio)

    @Test
    fun enableLock_passwordFirst_thenOnePrompt_thenTheSetting_thenTheSlot() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.None)
        assertEquals(AppResult.Ok(ToggleOutcome.PasswordRequired), f.controller.confirm(AuthPurpose.EnableLock))
        assertEquals(0, f.gateway.prompts.size)
        f.vault.nextVerifyPassword.addLast(UnlockResult.PasswordRejected(1, 0))
        assertEquals(AppResult.Err(AppError.WrongPassword), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "bad".toCharArray()))
        f.vault.nextVerifyPassword.addLast(UnlockResult.Throttled(5, 30_000))
        assertEquals(AppResult.Err(AppError.Throttled(30_000)), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "bad".toCharArray()))
        f.vault.nextVerifyPassword.addLast(UnlockResult.StorageFull(5, 0)) // not checked: not counted (DEC-75)
        assertEquals(AppResult.Err(AppError.StorageFull), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "pw".toCharArray()))
        f.vault.nextVerifyPassword.addLast(UnlockResult.KeyFailed)
        assertEquals(AppResult.Err(AppError.KeyUnavailable), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "pw".toCharArray()))
        assertEquals(0, f.gateway.prompts.size)
        assertFalse(f.settings.snapshot().biometricLock)
        f.gateway.succeed()
        val password = "correct".toCharArray()
        assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirmWithPassword(AuthPurpose.EnableLock, password))
        assertTrue(password.all { it == '\u0000' })
        assertTrue(f.vault.receivedPasswords.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(1, f.vault.reminderRestarts)
        assertEquals(FakeBiometricGateway.Prompt("Enable", false, "Cancel", withCrypto = true), f.gateway.prompts.single())
        assertEquals("the setting first (DEC-65)", listOf("create(lock=true)"), f.slots.calls)
    }

    @Test
    fun enableLock_cancelledPrompt_leavesTheLockOff_withTheReminderRestarted() = runTest {
        for (code in listOf(5, 10, 13, 3)) {
            val f = LockFixture(this)
            f.passwordMode(lock = false, bio = BioSlotStatus.None)
            f.gateway.fail(code)
            assertEquals(AppResult.Ok(ToggleOutcome.Cancelled), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "pw".toCharArray()))
            assertFalse(f.settings.snapshot().biometricLock)
            assertTrue(f.slots.calls.isEmpty())
            assertEquals(1, f.vault.reminderRestarts)
        }
    }

    @Test
    fun enableLock_busySlotWriter_writesTheSettingBackToFalse() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.None)
        f.slots.nextCreate = AppResult.Err(AppError.Busy)
        f.gateway.succeed()
        assertEquals(AppResult.Err(AppError.Busy), f.controller.confirmWithPassword(AuthPurpose.EnableLock, "pw".toCharArray()))
        assertFalse(f.settings.snapshot().biometricLock)
    }

    @Test
    fun setUpSlot_passwordThenOnePrompt_writesTheSlotAndNoSetting() = runTest {
        for (bio in listOf(BioSlotStatus.None, BioSlotStatus.Dead)) {
            val f = LockFixture(this)
            f.passwordMode(lock = true, bio = bio)
            val edits = f.settings.edits
            f.gateway.succeed()
            assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirmWithPassword(AuthPurpose.SetUpSlot, "pw".toCharArray()))
            assertEquals(FakeBiometricGateway.Prompt("Set up", false, "Cancel", withCrypto = true), f.gateway.prompts.single())
            assertEquals(edits, f.settings.edits)
            assertEquals(listOf("create(lock=true)"), f.slots.calls)
        }
    }

    @Test
    fun disableLock_withThePassword_writesTheSettingFirst_thenRemovesTheSlot_neverTheCredential() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = true, bio = BioSlotStatus.Live)
        f.gateway.fail(13)
        assertEquals(AppResult.Ok(ToggleOutcome.PasswordRequired), f.controller.confirm(AuthPurpose.DisableLock))
        assertEquals(AppResult.Ok(ToggleOutcome.Done), f.controller.confirmWithPassword(AuthPurpose.DisableLock, "pw".toCharArray()))
        assertEquals(listOf("remove(lock=false)"), f.slots.calls)
        assertEquals(0, f.gateway.credentialPromptCount)
        assertEquals(0, f.gateway.credentialIntentCalls)
    }

    @Test
    fun passwordFirst_lockOffAndATrip_askNothing_andTurningOnAsksThePasswordBeforeAnyPrompt() = runTest { // L-40 (h)
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.None)
        f.backgroundTrip()
        assertTrue(f.events.calls.isEmpty())
        assertEquals(AppResult.Ok(ToggleOutcome.PasswordRequired), f.controller.confirm(AuthPurpose.EnableLock))
        assertEquals(0, f.gateway.prompts.size)
    }

    @Test
    fun confirmWithPassword_clearsTheKekBrokenFlag_onlyAfterAVerifiedPassword() = runTest { // L-54 (a)
        val f = LockFixture(this)
        f.passwordMode(lock = true, bio = BioSlotStatus.Live)
        f.vault.reminderDue = true
        f.controller.reportKekBroken()
        f.vault.nextVerifyPassword.addLast(UnlockResult.PasswordRejected(1, 0))
        f.controller.confirmWithPassword(AuthPurpose.DisableLock, "bad".toCharArray())
        f.vault.nextVerifyPassword.addLast(UnlockResult.KeyFailed)
        f.controller.confirmWithPassword(AuthPurpose.DisableLock, "pw".toCharArray())
        assertTrue(f.controller.kekNotice.value)
        assertEquals(UnlockRoute.Slot, f.controller.unlockRoute())
        f.controller.confirmWithPassword(AuthPurpose.DisableLock, "pw".toCharArray())
        assertFalse(f.controller.kekNotice.value)
        f.settings.setBiometricLock(true)
        assertEquals(UnlockRoute.Password(PasswordReason.Reminder, false), f.controller.unlockRoute())
    }

    @Test
    fun enableLock_settingWriteFailure_isStorageFull_andCreatesNoSlot() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.None)
        f.gateway.succeed()
        f.settings.failEdits = true
        val enabled = f.controller.confirmWithPassword(AuthPurpose.EnableLock, "password".toCharArray())
        assertEquals(AppResult.Err(AppError.StorageFull), enabled)
        assertEquals("no slot without its setting (DEC-65)", emptyList<String>(), f.slots.calls)
    }

    @Test
    fun enableWithPassword_slotFailure_returnsItsError_evenWhenTheWriteBackFails() = runTest {
        val f = LockFixture(this)
        f.passwordMode(lock = false, bio = BioSlotStatus.None)
        f.gateway.succeed()
        f.slots.nextCreate = AppResult.Err(AppError.KeyUnavailable)
        f.settings.failEditsFrom = f.settings.edits + 2 // the setting is written, its write-back fails
        assertEquals(
            AppResult.Err(AppError.KeyUnavailable),
            f.controller.confirmWithPassword(AuthPurpose.EnableLock, "password".toCharArray()),
        )
        assertEquals(listOf("create(lock=true)"), f.slots.calls)
    }
}

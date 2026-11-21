package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.LockFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-42 `SlotPromptGatingTest`: the real controller with `FakeBiometricGateway` and `FakeKeyVault` on the device.
 * `fingerprintUnlockAvailable()` ignores the reminder; `slotPrompt(RestoreKey)` prompts only where the route would be
 * `Slot`, and answers `Ok(null)` for every failure; `slotPrompt(ResetPassword)` answers `Err(AuthFailed)` for a
 * non-cancel error and a foreign cipher.
 */
@RunWith(AndroidJUnit4::class)
class SlotPromptGatingTest {
    @Test
    fun fingerprintUnlockAvailable_ignoresTheReminder() = runTest {
        val f = LockFixture(this)
        f.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
        f.vault.reminderDue = true
        assertEquals(FingerprintAvailability.Available, f.controller.fingerprintUnlockAvailable())
    }

    @Test
    fun restoreKey_promptsOnlyOnTheSlotRoute() = runTest {
        val due = LockFixture(this)
        due.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
        due.vault.reminderDue = true
        assertEquals(AppResult.Ok(null), due.controller.slotPrompt(AuthPurpose.RestoreKey))
        assertEquals(0, due.gateway.prompts.size)
        val live = LockFixture(this)
        live.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
        live.gateway.fail(7)
        assertEquals(AppResult.Ok(null), live.controller.slotPrompt(AuthPurpose.RestoreKey))
        assertEquals(1, live.gateway.prompts.size)
    }

    @Test
    fun resetPassword_errorAndForeignCipher_areAuthFailed() = runTest {
        for (script in listOf<(LockFixture) -> Unit>({ it.gateway.fail(7) }, { it.gateway.succeed(cryptoMatches = false) })) {
            val f = LockFixture(this)
            f.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
            script(f)
            assertEquals(AppResult.Err(AppError.AuthFailed), f.controller.slotPrompt(AuthPurpose.ResetPassword))
        }
    }
}

package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.StartupCompletion
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.LockFixture
import com.doro.organizer.testing.unused
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEC-75: a password whose attempt could not be counted was not checked. E2 and E4 raise `PasswordStorageFull` with
 * the unchanged backoff, never a rejection, and never the key-store path of DEC-86. Instrumented, because the
 * password's NFC form needs the platform's ICU.
 */
@RunWith(AndroidJUnit4::class)
class GateEffectsStorageFullTest {
    private object StartupDone : DbMaintenance by unused() {
        override suspend fun completeStartup() = StartupCompletion.Done
    }

    @Test
    fun anUncountedPassword_isPasswordStorageFull_atTheOpenAndAtTheCheck() = runTest {
        val f = LockFixture(this)
        f.vault.commitMode(KeyMode.PASSWORD, generation = 1)
        f.vault.nextUnlock += UnlockResult.StorageFull(2, 0)
        f.vault.nextVerifyPassword += UnlockResult.StorageFull(2, 0)
        val effects = GateEffects(unused(), StartupDone, f.vault, f.controller, f.settings, f.time)

        assertEquals(
            GateEvent.PasswordStorageFull(2, 0),
            effects.unlockAndOpen(password = "pw".toCharArray(), cipher = null, resetKey = false),
        )
        assertEquals(GateEvent.PasswordStorageFull(2, 0), effects.verifyPassword("pw".toCharArray()))
        assertFalse("not a key-store failure", f.controller.kekNotice.value)
    }
}

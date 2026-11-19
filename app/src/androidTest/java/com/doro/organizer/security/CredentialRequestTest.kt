package com.doro.organizer.security

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.LockFixture
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The API 28–29 confirm-credential screen (security.md § 7.6) on the controller over fakes: a request that never
 * launches, or that a newer one supersedes, ends like a cancelled screen, so no caller waits forever. Instr because
 * the request carries an `Intent`.
 */
@RunWith(AndroidJUnit4::class)
class CredentialRequestTest {
    private suspend fun LockFixture.credentialOnly() {
        mode(KeyMode.DEVICE, lock = true)
        gateway.availability = BiometricAvailability.NoneEnrolled
        gateway.credentialIntentFactory = { title -> Intent(ACTION).putExtra(Intent.EXTRA_TITLE, title) }
    }

    @Test
    fun disableLock_credentialScreenNeverLaunched_isCancelled() = runTest {
        val f = LockFixture(this, sdkInt = 29)
        f.credentialOnly()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { f.controller.confirm(AuthPurpose.DisableLock) }
        f.controller.notLaunched(f.external.requests.single())
        assertEquals(AppResult.Ok(ToggleOutcome.Cancelled), outcome.await())
        assertTrue("the lock stays on", f.settings.snapshot().biometricLock)
    }

    @Test
    fun unlock_supersededRequest_endsAsACancel_andOnlyTheCurrentOneCounts() = runTest {
        val f = LockFixture(this, sdkInt = 29)
        f.credentialOnly()
        f.controller.onGateScreenChanged(LockScreen.Prompt, showsError = true)
        f.controller.prompt()
        f.controller.prompt()
        val (first, second) = f.external.requests
        f.controller.notLaunched(first) // the replaced request, reported by ExternalActivities
        assertEquals(listOf("AuthError(10, null)"), f.events.calls)
        f.controller.onCredentialResult(Activity.RESULT_OK)
        assertEquals(listOf("AuthError(10, null)", "Unlocked"), f.events.calls)
        f.controller.notLaunched(second) // already answered: nothing more
        assertEquals(2, f.events.calls.size)
    }

    private companion object {
        const val ACTION = "com.doro.organizer.test.CONFIRM_CREDENTIAL"
    }
}

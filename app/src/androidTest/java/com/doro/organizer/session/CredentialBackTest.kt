package com.doro.organizer.session

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-19 (Instr): the API 28–29 confirm-credential screen of security.md § 7.6, on a phone of any API level. The
 * controller takes the API 29 branch, the fake gateway hands out a stub intent, and Espresso-Intents answers it the
 * way the system screen would: Back is a silent cancel that leaves the lock in place, `RESULT_OK` unlocks.
 */
@RunWith(AndroidJUnit4::class)
class CredentialBackTest : GateTest() {
    @Before
    fun recordIntents() = Intents.init()

    @After
    fun releaseIntents() = Intents.release()

    @Test
    fun backFromTheCredentialScreen_staysLockedSilently_thenOkUnlocks() = runBlocking {
        scene.controllerSdkInt = 29
        scene.plant(KeyMode.DEVICE, lock = true)
        scene.gateway.availability = BiometricAvailability.NoneEnrolled // the credential is the fallback (Q7)
        scene.gateway.credentialIntentFactory = { Intent(ACTION) }
        intending(hasAction(ACTION)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        scene.coldStart()
        val locked = scene.awaitGate { it is GateState.Locked && it.authError != null } as GateState.Locked
        assertEquals(BiometricPrompt.ERROR_USER_CANCELED, locked.authError?.code)
        assertNull("no error text", locked.authError?.message)
        assertEquals(Resume.Open, locked.resume)
        intended(hasAction(ACTION))

        intending(hasAction(ACTION)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        GateScene.onMain { app.container.appLockController.prompt() } // the lock screen's "Unlock"
        scene.awaitGate(GateState.Ready)
        assertEquals(0, scene.gateway.prompts.size)
        assertEquals(2, scene.gateway.credentialIntentCalls)
    }

    private companion object {
        const val ACTION = "com.doro.organizer.test.CONFIRM_DEVICE_CREDENTIAL"
    }
}

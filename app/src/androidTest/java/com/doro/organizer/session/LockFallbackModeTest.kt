package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** L-39 (Instr): the Q7 device-credential fallback applies to `PLAIN` and `DEVICE` only (DEC-65). */
@RunWith(AndroidJUnit4::class)
class LockFallbackModeTest : GateTest() {
    @Test
    fun passwordMode_noneEnrolled_asksThePassword_atColdStartAndOnReturn_neverTheCredential() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.availability = BiometricAvailability.NoneEnrolled
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password != null }
        assertEquals(0, scene.gateway.prompts.size)
        assertEquals(0, scene.gateway.credentialIntentCalls)
    }

    @Test
    fun strongBiometricsGone_credentialFallback() = runBlocking {
        scene.plant(KeyMode.DEVICE, lock = true)
        scene.gateway.availability = BiometricAvailability.NoneEnrolled
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        assertEquals(1, scene.gateway.credentialPromptCount)
        assertEquals(0, scene.gateway.biometricPromptCount)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

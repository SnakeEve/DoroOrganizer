package com.doro.organizer.security

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production gateway answers every request exactly once (security.md § 7.3), so `AppLockController` never waits
 * on a prompt that cannot call back: androidx.biometric 1.1.0 drops a request once the activity has saved its state,
 * and a prompt that dies with a finishing activity promises no callback. Each case runs on the main thread of a
 * launched `MainActivity`, which the app's own (fake) gateway is attached to; this test attaches its own.
 */
@RunWith(AndroidJUnit4::class)
class BiometricGatewayAnswerTest : GateTest() {
    private val gateway = DefaultBiometricGateway(InstrumentationRegistry.getInstrumentation().targetContext)
    private val answers = mutableListOf<Pair<String, Int>>()

    private fun request(name: String) =
        gateway.authenticate(PromptRequest("Test", false, "Cancel")) { answers += name to (it as PromptResult.Error).code }

    private suspend fun started() {
        scene.plant(KeyMode.PLAIN)
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
    }

    @Test
    fun withoutAnActivity_itAnswersACancelAtOnce() {
        request("first")
        assertEquals(listOf("first" to BiometricPrompt.ERROR_CANCELED), answers)
    }

    @Test
    fun aStoppedActivity_isAnsweredWithACancel_insteadOfADroppedPrompt() = runBlocking {
        started()
        scene.moveActivityTo(Lifecycle.State.CREATED)
        scene.onActivity { activity ->
            gateway.attach(activity)
            request("first")
            gateway.detach(activity)
        }
        assertEquals(listOf("first" to BiometricPrompt.ERROR_CANCELED), answers)
    }

    @Test
    fun aReplacedRequest_andOneOfAFinishingActivity_areEachAnsweredOnce() = runBlocking {
        started()
        scene.onActivity { activity ->
            gateway.attach(activity)
            request("first")
            request("second") // replaces the first, which is answered at once
            assertEquals(listOf("first" to BiometricPrompt.ERROR_CANCELED), answers)
            gateway.detach(activity) // not a configuration change: the open prompt dies with it
        }
        assertEquals(listOf("first", "second").map { it to BiometricPrompt.ERROR_CANCELED }, answers)
        scene.onActivity { } // a late system callback for the dismissed prompt reaches no one
        assertEquals(2, answers.size)
    }
}

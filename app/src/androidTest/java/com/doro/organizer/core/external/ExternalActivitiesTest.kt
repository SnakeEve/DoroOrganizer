package com.doro.organizer.core.external

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-19 (`ExternalActivities` part): a launch that finds no activity is reported on `failures` and leaves no re-lock
 * exemption, so the next process ON_STOP locks (security.md § 7.7, architecture.md § Result launchers).
 */
@RunWith(AndroidJUnit4::class)
class ExternalActivitiesTest : GateTest() {
    @Test
    fun aLaunchWithoutHandler_isAFailure_andLeavesNoExemption() = runBlocking<Unit> {
        scene.plant(KeyMode.PLAIN, lock = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        val external = app.container.externalActivities
        val failure = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(GateScene.TIMEOUT_MS) { external.failures.first() } }
        GateScene.onMain { external.request(ExternalRequest(ExternalKind.Enroll, Intent(NO_SUCH_ACTION))) }
        assertEquals(ExternalKind.Enroll, failure.await())
        GateScene.onMain { scene.process.moveTo(Lifecycle.State.CREATED) }
        scene.awaitGate(GateState.Locked(Resume.Ready))
    }

    @Test
    fun aMissingHandler_andAMissingFallback_areReportedOnce() = runBlocking {
        scene.plant(KeyMode.PLAIN)
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        val external = app.container.externalActivities
        val failure = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(GateScene.TIMEOUT_MS) { external.failures.first() } }
        val request = ExternalRequest(ExternalKind.Enroll, Intent(NO_SUCH_ACTION), fallbackIntent = Intent(NO_SUCH_ACTION + "_2"))
        GateScene.onMain { external.request(request) }
        assertEquals("both failed: reported once", ExternalKind.Enroll, failure.await())
    }

    private companion object {
        const val NO_SUCH_ACTION = "com.doro.organizer.test.NO_SUCH_ACTION"
    }
}

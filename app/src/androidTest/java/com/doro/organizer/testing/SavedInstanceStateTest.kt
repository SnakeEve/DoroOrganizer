package com.doro.organizer.testing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.MainActivity
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [captureSavedState] returns the save that follows the stop even when its block returns at the stop, before the main
 * thread has reached `onSaveInstanceState` (from API 28 it runs after `onStop`, in the same main-thread message): the
 * order run A of T22 hit by chance. Here the block returns when `MainActivity` reports its stop, and the main thread is
 * held inside `onStop` until the test thread has left the block and is parked, so without the wait in
 * [captureSavedState] its callback is gone before the save, every time.
 */
@RunWith(AndroidJUnit4::class)
class SavedInstanceStateTest : GateTest() {
    @Test
    fun captureSavedState_getsTheSaveThatFollowsTheStop_whenItsBlockReturnsFirst() = runBlocking<Unit> {
        scene.plant(KeyMode.PLAIN)
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testThread = Thread.currentThread()
        val stopped = CountDownLatch(1)
        val blockReturned = AtomicBoolean(false)
        val holdTimedOut = AtomicBoolean(false)
        val holdAtTheStop = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                if (activity !is MainActivity) return
                stopped.countDown()
                val deadline = SystemClock.uptimeMillis() + GateScene.TIMEOUT_MS
                while (!blockReturned.get() || testThread.state !in PARKED) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        holdTimedOut.set(true)
                        return
                    }
                    Thread.yield()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(holdAtTheStop)
        // moveToState also waits for its EmptyActivity's resume, which a held main thread cannot receive, so it runs
        // on a thread of its own and the block returns at the stop whichever of the two comes first.
        val moverFailure = AtomicReference<Throwable>()
        val mover = thread(name = "moveActivityToCreated") {
            try {
                scene.moveActivityTo(Lifecycle.State.CREATED)
            } catch (failure: Throwable) {
                moverFailure.set(failure)
            }
        }
        try {
            val saved = captureSavedState {
                assertTrue("MainActivity stopped", stopped.await(GateScene.TIMEOUT_MS, TimeUnit.MILLISECONDS))
                blockReturned.set(true)
            }
            assertNotNull("Compose's part of the saved state", saved.composeSavedState())
        } finally {
            mover.join()
            application.unregisterActivityLifecycleCallbacks(holdAtTheStop)
        }
        moverFailure.get()?.let { throw it }
        assertFalse("the main thread was held until the test thread parked", holdTimedOut.get())
    }

    private companion object {
        val PARKED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }
}

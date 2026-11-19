package com.doro.organizer.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** architecture.md § Layering and data flow, rule 6: a write finishes without its caller, and a bug still crashes. */
class RunDetachedTest {
    private val uncaught = mutableListOf<Throwable>()

    @Test
    fun returnsTheBlockResult() = runTest {
        assertEquals(42, detachedScope().runDetached { 42 })
    }

    @Test
    fun cancelledCaller_blockStillFinishes() = runTest {
        val scope = detachedScope()
        val release = CompletableDeferred<Unit>()
        var finished = false
        val caller = launch { scope.runDetached { release.await().also { finished = true } } }
        runCurrent()

        caller.cancelAndJoin()
        assertFalse(finished)
        release.complete(Unit)
        runCurrent()

        assertTrue(finished)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun failure_reachesTheCallerAndTheUncaughtHandler_scopeSurvives() = runTest {
        val scope = detachedScope()

        val thrown = runCatching { scope.runDetached { throw IllegalStateException("bug") } }.exceptionOrNull()
        runCurrent()

        // Stack-trace recovery may hand the caller a copy, so compare type and message.
        assertTrue("$thrown", thrown is IllegalStateException && thrown.message == "bug")
        assertEquals(listOf("bug"), uncaught.map { it.message })
        assertEquals("the scope keeps running later writes", 7, scope.runDetached { 7 })
    }

    @Test
    fun cancelledScope_callerGetsCancellation_blockNeverRuns() = runTest {
        val scope = detachedScope().apply { cancel() }
        var ran = false

        val thrown = runCatching { scope.runDetached { ran = true } }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
        assertFalse(ran)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    private fun TestScope.detachedScope() = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e },
    )
}

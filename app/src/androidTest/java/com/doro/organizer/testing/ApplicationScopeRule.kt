package com.doro.organizer.testing

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource

/**
 * One test's `applicationScope`, the scope of the detached writes (architecture.md § Layering and data flow, rule 6).
 * A failure inside a write still reaches the caller; the handler also collects it, instead of letting it crash the
 * instrumentation process and every later test, and fails the test that caused it. Cancelled after each test.
 */
class ApplicationScopeRule : ExternalResource() {
    private val failures = ConcurrentLinkedQueue<Throwable>()

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures += e },
    )

    /**
     * Removes and returns the failures collected so far, for a test that injects one on purpose. A failing write
     * reaches this scope's handler before its caller resumes, so the failure is here once the call has thrown.
     */
    fun takeFailures(): List<Throwable> = generateSequence { failures.poll() }.toList()

    override fun after() {
        scope.cancel()
        assertEquals("failures inside detached writes", emptyList<Throwable>(), failures.toList())
    }
}

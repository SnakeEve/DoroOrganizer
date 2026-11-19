package com.doro.organizer.core.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block] in this scope, detached from the caller (architecture.md § Layering and data flow, rule 6): a write
 * finishes even when its caller is cancelled, and a cancelled caller only stops waiting. A failure reaches the
 * caller and also this scope's uncaught-exception handler, so a bug inside a write still crashes the app.
 */
suspend fun <T> CoroutineScope.runDetached(block: suspend () -> T): T {
    val result = CompletableDeferred<T>()
    // Completing from the job, not from inside the body, also ends the wait when this scope is already cancelled
    // and the body never starts.
    launch { result.complete(block()) }.invokeOnCompletion { cause ->
        if (cause != null) result.completeExceptionally(cause)
    }
    return result.await()
}

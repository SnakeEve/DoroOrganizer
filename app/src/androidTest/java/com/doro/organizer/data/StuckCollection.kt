package com.doro.organizer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The scene of the flow drain's timeout path (testing.md L-17; db-lifecycle.md § Exclusive mode step 4, § Close
 * step 3): a collection through [DatabaseHolder.observe] whose flow ignores its cancellation until [release], so a
 * drain ends on it only by its timeout and leaves it behind. `DatabaseHolderTest` and `DrainTimeoutLogTest` share it.
 */
internal class StuckCollection(private val collector: Job, private val released: CompletableDeferred<Unit>) {
    /** Lets the flow finish its cancellation; until then the collection, and a test that waits for it, cannot end. */
    fun release() {
        released.complete(Unit)
    }

    /** [release], then cancels the collection and waits until it has ended. */
    suspend fun end() {
        release()
        collector.cancelAndJoin()
    }
}

/** Starts a [StuckCollection] of [holder] in this scope and returns once its flow has emitted on the open instance. */
internal suspend fun CoroutineScope.startStuckCollection(holder: DatabaseHolder): StuckCollection {
    val subscribed = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val collector = launch {
        holder.observe {
            flow {
                emit(Unit)
                subscribed.complete(Unit)
                withContext(NonCancellable) { released.await() }
            }
        }.collect {}
    }
    subscribed.await()
    return StuckCollection(collector, released)
}

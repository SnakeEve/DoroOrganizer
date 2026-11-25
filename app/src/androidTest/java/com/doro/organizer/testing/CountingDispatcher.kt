package com.doro.organizer.testing

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/**
 * [real] counting its queued and running tasks, as the app's `default` dispatcher of a list-position test (testing.md
 * I-58): the list maps its rows there, so [idle] tells that no rows are on their way to the screen.
 */
class CountingDispatcher(private val real: CoroutineDispatcher = Dispatchers.Default) : CoroutineDispatcher() {
    private val tasks = AtomicInteger()

    /** No task queued or running. */
    val idle: Boolean get() = tasks.get() == 0

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = real.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.incrementAndGet()
        real.dispatch(context) {
            try {
                block.run()
            } finally {
                tasks.decrementAndGet()
            }
        }
    }
}

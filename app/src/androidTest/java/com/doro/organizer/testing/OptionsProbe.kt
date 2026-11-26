package com.doro.organizer.testing

import com.doro.organizer.data.EntityRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The entity repository of an "All people" / "All projects" test (testing.md I-63; DEC-112): the real one, whose
 * `observeOptions` collections it counts, so a test knows when a picker's upstream has stopped (`WhileSubscribed`),
 * and whose next collection's first emission it can hold, as [RowsProbe] holds rows. Its calls run on the app's
 * threads, so its state is thread-safe.
 */
class OptionsProbe(private val real: EntityRepository) : EntityRepository by real {
    private val activeCount = AtomicInteger()
    private val nextFirstEmission = AtomicReference<RowsProbe.Hold?>()

    /** The `observeOptions` collections running now: started and not ended. */
    val active: Int get() = activeCount.get()

    /** Holds the first emission of the next `observeOptions` collection. */
    fun holdNextFirstEmission(): RowsProbe.Hold = RowsProbe.Hold().also { nextFirstEmission.set(it) }

    override fun observeOptions(kind: EntityKind, coLinkedTo: List<Long>, exceptNoteId: Long?): Flow<List<EntityOption>> =
        flow {
            activeCount.incrementAndGet()
            val hold = nextFirstEmission.getAndSet(null)
            try {
                var first = true
                real.observeOptions(kind, coLinkedTo, exceptNoteId).collect { options ->
                    if (first) {
                        first = false
                        hold?.await()
                    }
                    emit(options)
                }
            } finally {
                activeCount.decrementAndGet()
            }
        }
}

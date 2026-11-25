package com.doro.organizer.testing

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.SaveResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The note repository of a list-position test (testing.md I-58): the real one, whose `observeRows` collections it
 * records ([collections]: started, emitted, ended, per [NoteQuery]) and whose next `save` or next collection's first
 * emission it can hold. Its calls run on the app's threads, so its records are thread-safe.
 */
class RowsProbe(private val real: NoteRepository) : NoteRepository by real {
    /** Every `observeRows` collection, in the order they started. */
    val collections: List<RowsCollection> get() = started.toList()

    private val started = CopyOnWriteArrayList<RowsCollection>()
    private val nextSave = AtomicReference<Hold?>(null)
    private val nextFirstEmission = AtomicReference<Hold?>(null)

    /** One collection of `observeRows`, started at [startedAt] (`System.nanoTime()`). */
    class RowsCollection(val query: NoteQuery) {
        val startedAt: Long = System.nanoTime()

        @Volatile var emissions = 0
            internal set

        @Volatile var ended = false
            internal set

        override fun toString() = "RowsCollection(emissions=$emissions, ended=$ended)"
    }

    /**
     * A held call: [reached] completes when the call waits, which lasts until [release]. A held first emission names
     * its [collection].
     */
    class Hold internal constructor(internal val afterWrite: Boolean = false) {
        val reached = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        @Volatile var collection: RowsCollection? = null
            internal set

        fun release() {
            gate.complete(Unit)
        }

        internal suspend fun await() {
            reached.complete(Unit)
            gate.await()
        }
    }

    /** Holds the next `save` before the real write ([afterWrite] false) or after it, before it returns. */
    fun holdNextSave(afterWrite: Boolean): Hold = Hold(afterWrite).also { nextSave.set(it) }

    /** Holds the first emission of the next `observeRows` collection. */
    fun holdNextFirstEmission(): Hold = Hold().also { nextFirstEmission.set(it) }

    /** The collections active now: started and not ended. */
    fun active(): List<RowsCollection> = started.filterNot { it.ended }

    override fun observeRows(query: NoteQuery): Flow<List<NoteRow>> = flow {
        val collection = RowsCollection(query).also { started += it }
        val hold = nextFirstEmission.getAndSet(null)?.also { it.collection = collection }
        try {
            real.observeRows(query).collect { rows ->
                if (collection.emissions == 0) hold?.await()
                emit(rows)
                collection.emissions++
            }
        } finally {
            collection.ended = true
        }
    }

    override suspend fun save(draft: NoteDraft): AppResult<SaveResult> {
        val hold = nextSave.getAndSet(null) ?: return real.save(draft)
        if (!hold.afterWrite) hold.await()
        return real.save(draft).also { if (hold.afterWrite) hold.await() }
    }
}

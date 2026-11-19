package com.doro.organizer.data

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only owner of the live Room instance and its key (DEC-81, db-lifecycle.md § DatabaseHolder). Repositories reach
 * their DAOs per call through [withDb] or per subscription through [observe], and never keep `db` or a DAO beyond
 * the block. Nothing here ever deletes a file (DEC-85).
 */
interface DatabaseHolder {
    val state: StateFlow<HolderState>

    /**
     * Effect E2 only: runs the open sequence of db-lifecycle.md on `doro-<generation>.db`. Takes ownership of
     * [rawKey]: it is kept while Room is open and zeroed by the holder on failure and on close. Callable only in
     * [HolderState.Closed]; in any other state it zeroes [rawKey] and throws `IllegalStateException`.
     */
    suspend fun open(generation: Long, rawKey: ByteArray): OpenResult

    /** Effect E3: drains, closes Room, zeroes the key. A no-op when already closed. */
    suspend fun close()

    /**
     * The flow [query] builds on the open database, subscribed again after every reopen (db-lifecycle.md
     * § Observation). It emits nothing while the database is not open and never a placeholder, an empty list or null
     * because of a close, so an existence probe may read null as "the row is gone".
     */
    fun <T> observe(query: (AppDatabase) -> Flow<T>): Flow<T>

    /**
     * Runs [block] on the open database (db-lifecycle.md § One-shot access: withDb). A closed or replaced database
     * and the expected SQLite failures come back as [AppResult.Err]; anything else propagates.
     */
    suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T>

    /**
     * DbMaintenance only (db-lifecycle.md § Exclusive mode): drains, closes Room, runs [block] and applies the
     * [Reopen] it returns. Returns null when the block asks to stay closed, `Failed(Transient)` without running the
     * block when the holder is already closed.
     */
    suspend fun exclusive(block: suspend (ExclusiveScope) -> Reopen): OpenResult?

    /**
     * DbMaintenance only (restore from Ready): a copy of the live key in state Open, else null. The caller zeroes the
     * copy. Every zeroing of the live key holds the same transition lock, so a copy is never half-zeroed.
     */
    suspend fun copyLiveKey(): ByteArray?
}

sealed interface HolderState {
    data object Closed : HolderState

    data object Opening : HolderState

    /** Identity equality on purpose: a reopen of the same generation is a new state, so observers re-subscribe. */
    class Open(val db: AppDatabase, val generation: Long, val dataset: Long) : HolderState {
        /** The `observe` collections currently running on [db]; exclusive mode waits for it to reach 0. */
        internal val activeFlows = AtomicInteger()

        override fun toString() = "Open(generation=$generation, dataset=$dataset)"
    }

    data object Draining : HolderState

    data object Exclusive : HolderState
}

sealed interface OpenResult {
    data object Opened : OpenResult

    /**
     * [keyRejected]: the key does not fit the file (header/mode mismatch, or `SQLiteNotADatabaseException` when the
     * raw connection opens or at its first read); [kind] is then Corrupt. Only the slot path of E2 reads it.
     */
    data class Failed(val kind: OpenFailureKind, val keyRejected: Boolean = false) : OpenResult {
        override fun toString() = "Failed(kind=$kind, keyRejected=$keyRejected)"
    }
}

/** The kind of `GateEvent.OpenFailed` and of `MaintenanceOutcome.ReopenFailed`. */
enum class OpenFailureKind { Corrupt, Transient }

/** [liveKey] is a loan: bind it as the export source key, never zero or keep it. */
class ExclusiveScope(val generation: Long, val liveKey: ByteArray) {
    override fun toString() = "ExclusiveScope(generation=$generation)"
}

/** What the holder does after an exclusive block (db-lifecycle.md § Exclusive mode, step 6). */
sealed interface Reopen {
    /** Same generation, same key. */
    data object Same : Reopen

    /**
     * Opens [generation]; a non-null [newKey] replaces (and zeroes) the old key and is owned by the holder from now
     * on. [newData] marks replaced data (a Restore), which fails every `withDb` call that waited across it.
     */
    class Generation(val generation: Long, val newKey: ByteArray?, val newData: Boolean) : Reopen {
        override fun toString() = "Generation(generation=$generation, newData=$newData)"
    }

    data object StayClosed : Reopen
}

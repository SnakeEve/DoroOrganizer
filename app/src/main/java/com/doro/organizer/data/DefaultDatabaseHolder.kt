package com.doro.organizer.data

import android.content.Context
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The production [DatabaseHolder] (db-lifecycle.md § DatabaseHolder). One transition [Mutex] serializes `open`,
 * `close`, `exclusive` and `copyLiveKey`, and every zeroing of the live key; a short-held state lock guards the
 * in-flight count of `withDb`, so once `Draining` is set no new call can enter. [corruptionListener] is a provider,
 * resolved at each corruption event, because SessionGate depends on this holder.
 */
class DefaultDatabaseHolder(
    private val context: Context,
    private val files: DbFiles,
    private val dispatchers: AppDispatchers,
    private val corruptionListener: () -> CorruptionListener,
    private val flowProblemListener: FlowProblemListener,
) : DatabaseHolder {
    private val mutableState = MutableStateFlow<HolderState>(HolderState.Closed)
    override val state: StateFlow<HolderState> = mutableState.asStateFlow()

    private val transition = Mutex()
    private val stateLock = Any()

    /** Running `withDb` calls; incremented only under [stateLock] and only in state Open. */
    private val inFlight = MutableStateFlow(0)

    /** Guarded by [transition]. */
    private var liveKey: ByteArray? = null

    /** Guarded by [transition]; incremented when an exclusive block reopens with replaced data. */
    private var dataset = 0L

    /**
     * Guarded by [stateLock] and published together with the Open state: the dataset of the last Open, which a waiting
     * `withDb` call reads in the same step as the state it waits on.
     */
    private var openedDataset = 0L

    override suspend fun open(generation: Long, rawKey: ByteArray): OpenResult = transition.withLock {
        if (mutableState.value !== HolderState.Closed) {
            rawKey.fill(0) // the caller handed the key over either way
            error("open outside Closed")
        }
        openLocked(generation, rawKey)
    }

    /**
     * db-lifecycle.md § Close (DEC-81): the same drain as exclusive mode (the calls in flight, then the collected flows
     * for at most the drain timeout), then Room is closed and the key is zeroed.
     */
    override suspend fun close() = transition.withLock {
        when (val current = mutableState.value) {
            HolderState.Closed -> Unit
            is HolderState.Open -> withContext(NonCancellable) {
                // Even when Room's close fails, the holder leaves Draining and the key is zeroed.
                try {
                    drainCalls()
                    drainFlows(current)
                    closeRoom(current)
                } finally {
                    stayClosed()
                }
            }
            // Transitions are serialized, so no other state is visible under the lock.
            else -> error("close in a transitional state")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun <T> observe(query: (AppDatabase) -> Flow<T>): Flow<T> = state.flatMapLatest { s ->
        if (s !is HolderState.Open) {
            emptyFlow()
        } else {
            query(s.db)
                .onStart { s.activeFlows.incrementAndGet() }
                .onCompletion { s.activeFlows.decrementAndGet() } // also on cancellation: the drain waits for it
                .retryWhen { e, attempt ->
                    e.isEnvironmental() && attempt < FLOW_RETRIES && state.value === s &&
                        run {
                            delay(FLOW_RETRY_DELAY_MS)
                            true
                        }
                }
                .catch { e -> onFlowError(e, s) }
        }
    }

    override suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T> {
        // A nested call reuses the outer instance: acquiring again could wait for a drain that waits for the outer
        // call.
        currentCoroutineContext()[Lease]?.takeIf { it.holder === this }?.let { lease ->
            return runClassified { block(lease.open.db) }
        }
        val open = acquire() ?: return AppResult.Err(AppError.DatabaseUnavailable)
        try {
            return withContext(Lease(this, open)) { runClassified { block(open.db) } }
        } finally {
            synchronized(stateLock) { inFlight.value -= 1 }
        }
    }

    override suspend fun exclusive(block: suspend (ExclusiveScope) -> Reopen): OpenResult? = transition.withLock {
        val open = when (val current = mutableState.value) {
            is HolderState.Open -> current
            // The gate may have left Ready (and run E3) between the user's action and this call.
            HolderState.Closed -> return@withLock OpenResult.Failed(OpenFailureKind.Transient)
            else -> error("exclusive in a transitional state")
        }
        withContext(NonCancellable) {
            try {
                drainCalls()
                drainFlows(open)
                closeRoom(open)
            } catch (t: Throwable) {
                stayClosed()
                throw t
            }
        }
        mutableState.value = HolderState.Exclusive
        val key = checkNotNull(liveKey)
        val directive = try {
            block(ExclusiveScope(open.generation, key))
        } catch (t: Throwable) {
            // A bug in the block: whether a commit happened is unknown, so the old generation is never reopened.
            stayClosed()
            throw t
        }
        withContext(NonCancellable) { reopen(directive, open.generation, key) }
    }

    override suspend fun copyLiveKey(): ByteArray? = transition.withLock {
        if (mutableState.value is HolderState.Open) liveKey?.copyOf() else null
    }

    /**
     * The open sequence of db-lifecycle.md, run to completion once started: a cancellation could otherwise leave a
     * built Room instance that no state holds. Takes ownership of [key]; on failure it is zeroed and the state is
     * Closed.
     */
    private suspend fun openLocked(generation: Long, key: ByteArray): OpenResult = withContext(NonCancellable) {
        mutableState.value = HolderState.Opening
        try {
            val db = withContext(dispatchers.io) { openRoom(generation, key) }
            liveKey = key
            synchronized(stateLock) {
                openedDataset = dataset
                mutableState.value = HolderState.Open(db, generation, dataset)
            }
            OpenResult.Opened
        } catch (e: Throwable) {
            key.fill(0)
            stayClosed()
            OpenResult.Failed(openFailureKind(e), keyRejected = (e as? CheckFailed)?.keyRejected == true)
        }
    }

    /** Steps 2–5: raw checks before Room sees the file, then Room's migrations and identity check. */
    private fun openRoom(generation: Long, key: ByteArray): AppDatabase {
        val file = files.live(generation)
        verifyLive(file, key)
        val db = buildRoom(context, file, key, temporary = false)
        try {
            db.openHelper.writableDatabase
        } catch (e: Throwable) {
            runCatching(db::close)
            throw e
        }
        return db
    }

    private suspend fun reopen(directive: Reopen, generation: Long, key: ByteArray): OpenResult? = when (directive) {
        Reopen.Same -> openLocked(generation, key)
        is Reopen.Generation -> {
            val next = directive.newKey?.also {
                key.fill(0) // the old instance and every maintenance connection are closed
                liveKey = it
            } ?: key
            if (directive.newData) dataset += 1
            openLocked(directive.generation, next)
        }
        Reopen.StayClosed -> {
            stayClosed()
            null
        }
    }

    /** Zeroes the live key and publishes Closed. Callers hold [transition]. */
    private fun stayClosed() {
        liveKey?.fill(0)
        liveKey = null
        mutableState.value = HolderState.Closed
    }

    /**
     * Draining: no new `withDb` call enters and `flatMapLatest` cancels the collected flows; then waits for the calls
     * in flight, so every started transaction ends. Callers hold [transition] and run it in [NonCancellable], so the
     * holder never stays in Draining.
     */
    private suspend fun drainCalls() {
        synchronized(stateLock) { mutableState.value = HolderState.Draining }
        inFlight.first { it == 0 }
    }

    /**
     * Exclusive mode step 4 and close step 3 (db-lifecycle.md): waits at most [FLOW_DRAIN_TIMEOUT_MS] for the cancelled
     * flows of [open] to end; a Room flow ends only after its running statement has returned (DEC-107). `exclusive` and
     * `close` call it after [drainCalls], holding [transition], in [NonCancellable]. On timeout it logs one constant
     * line, the same for both callers.
     */
    private suspend fun drainFlows(open: HolderState.Open) {
        val drained = withTimeoutOrNull(FLOW_DRAIN_TIMEOUT_MS) {
            while (open.activeFlows.get() > 0) delay(FLOW_DRAIN_POLL_MS)
        }
        if (drained == null) AppLog.d("exclusive: flow drain timeout")
    }

    private suspend fun closeRoom(open: HolderState.Open) = withContext(dispatchers.io) { open.db.close() }

    /**
     * Open: counts the call in and returns the instance. Closed: null at once. Otherwise waits for Open or Closed
     * and gives up when the data was replaced meanwhile (db-lifecycle.md § One-shot access: withDb).
     */
    private suspend fun acquire(): HolderState.Open? {
        var expected: Long? = null
        while (true) {
            // The state and the dataset of the last Open are read in one step, so a reopen with replaced data that
            // completes in between cannot pass for the data this call waited for.
            val current = synchronized(stateLock) {
                mutableState.value.also { s ->
                    when {
                        s is HolderState.Open -> if (expected == null || s.dataset == expected) inFlight.value += 1
                        s !== HolderState.Closed && expected == null -> expected = openedDataset
                    }
                }
            }
            when (current) {
                is HolderState.Open -> return current.takeIf { expected == null || it.dataset == expected }
                HolderState.Closed -> return null
                else -> mutableState.first { it is HolderState.Open || it === HolderState.Closed }
            }
        }
    }

    /** Context Runtime of db-lifecycle.md § Classification table for a `withDb` block. */
    private suspend fun <T> runClassified(block: suspend () -> T): AppResult<T> = try {
        AppResult.Ok(block())
    } catch (e: Throwable) {
        when (runtimeFailure(e)) {
            DbFailure.Corrupt -> {
                corruptionListener().onRuntimeCorruption()
                AppResult.Err(AppError.DatabaseUnavailable)
            }
            DbFailure.StorageFull -> AppResult.Err(AppError.StorageFull)
            DbFailure.Transient -> AppResult.Err(AppError.DatabaseUnavailable)
            else -> throw e
        }
    }

    /**
     * A flow error after the retries. From a retired instance (closed by `exclusive` or `close`) it is swallowed; the
     * flow resumes at the next Open. A transient disk error or a full disk never crashes the app: that would lose the
     * editor draft typed since the last ON_STOP (DEC-37).
     */
    private fun onFlowError(e: Throwable, s: HolderState.Open) {
        if (e is CancellationException) throw e
        if (state.value !== s) return
        when (runtimeFailure(e)) {
            DbFailure.Corrupt -> corruptionListener().onRuntimeCorruption()
            DbFailure.StorageFull -> flowProblemListener.onFlowProblem(AppError.StorageFull)
            DbFailure.Transient -> flowProblemListener.onFlowProblem(AppError.DatabaseUnavailable)
            else -> throw e
        }
    }

    /** A flow error that is retried: the Transient and storage-full rows of the Runtime table ([runtimeFailure]). */
    private fun Throwable.isEnvironmental(): Boolean = this !is CancellationException &&
        runtimeFailure(this).let { it == DbFailure.Transient || it == DbFailure.StorageFull }

    /** The instance a `withDb` call runs on, visible to nested calls in the same coroutine. */
    private class Lease(val holder: DefaultDatabaseHolder, val open: HolderState.Open) :
        AbstractCoroutineContextElement(Lease) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    private companion object {
        const val FLOW_RETRIES = 3
        const val FLOW_RETRY_DELAY_MS = 2_000L
        const val FLOW_DRAIN_TIMEOUT_MS = 2_000L
        const val FLOW_DRAIN_POLL_MS = 10L
    }
}

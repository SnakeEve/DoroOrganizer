package com.doro.organizer.data

import androidx.room.coroutines.createFlow
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.testing.LongQuery
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDatabases.Companion.GENERATION
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.openDescriptors
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lifecycle of the production holder (db-lifecycle.md § DatabaseHolder): the holder half of testing.md L-17
 * (nothing open before `open`, exclusive mode and `close()` drain work and flows, a Room flow whose statement is
 * running in SQLite drains only after the statement returns, flows survive swaps), the holder half of L-03 (raw-key
 * custody) and the exclusive-mode directives. Runs on real dispatchers, because the holder's drain polls on the wall
 * clock while Room works on its own threads. Failure messages carry times and states only, no user data (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseHolderTest {
    /** Every key array handed to SQLCipher, in order, so the tests can watch the holder's copy. */
    private val keys = Collections.synchronizedList(mutableListOf<ByteArray>())

    @get:Rule
    val databases = TestDatabases { key -> key.bytes().also { keys += it } }

    @Test
    fun open_nothingHoldsTheFileBeforeOpenOrAfterClose() = runOnDefault {
        val file = databases.createLive()
        val holder = databases.newHolder()

        assertSame(HolderState.Closed, holder.state.value)
        assertEquals(0, openDescriptors(file))
        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), holder.withDb { error("never runs") })

        assertEquals(OpenResult.Opened, holder.open(GENERATION, TestKey.Plain.bytes()))
        assertTrue(openDescriptors(file) > 0)

        holder.close()
        assertSame(HolderState.Closed, holder.state.value)
        assertEquals(0, openDescriptors(file))
    }

    /** Transitions are serialized, so Open is the only state other than Closed that a caller of `open` can meet. */
    @Test
    fun open_outsideClosed_isAProgrammingError_andZeroesTheKey() = runOnDefault {
        val holder = databases.holder()
        val key = ByteArray(67) { 1 }

        val thrown = runCatching { holder.open(GENERATION, key) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalStateException)
        assertTrue("the handed-over key is zeroed", key.all { it == 0.toByte() })
        assertTrue(holder.state.value is HolderState.Open)
    }

    @Test
    fun withDb_duringTheFirstOpen_waitsAndThenRuns() = runOnDefault {
        databases.createLive()
        val gate = CountDownLatch(1)
        val gatedIo = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        // Room is built on the IO dispatcher, so the holder stays Opening until the gate opens.
        gatedIo.executor.execute { gate.await() }
        val production = AppDispatchers.production()
        val holder = databases.newHolder(dispatchers = AppDispatchers(production.main, gatedIo, production.default))
        try {
            val opened = async(start = CoroutineStart.UNDISPATCHED) { holder.open(GENERATION, TestKey.Plain.bytes()) }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { holder.withDb { it.countNotes() } }

            assertSame(HolderState.Opening, holder.state.value)
            assertTrue("the call waits while the holder opens", waiter.isActive)
            gate.countDown()
            assertEquals(OpenResult.Opened, opened.await())
            assertEquals(AppResult.Ok(0L), waiter.await())
        } finally {
            gate.countDown()
            holder.close() // before the dispatcher stops: closing Room runs on it
            gatedIo.close()
        }
    }

    @Test
    fun close_waitsForTheCallInFlight_andFailsCallsThatWaited() = runOnDefault {
        val holder = databases.holder()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inFlight = async { holder.withDb { started.complete(Unit); release.await(); "done" } }
        started.await()

        val closing = async(start = CoroutineStart.UNDISPATCHED) { holder.close() }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { holder.withDb { "never runs" } }

        assertSame(HolderState.Draining, holder.state.value)
        assertTrue("close waits for the call in flight", closing.isActive)
        assertTrue(waiter.isActive)
        release.complete(Unit)
        closing.await()
        assertEquals(AppResult.Ok("done"), inFlight.await())
        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), waiter.await())
        assertSame(HolderState.Closed, holder.state.value)
    }

    @Test
    fun close_whenClosed_isANoOp() = runOnDefault {
        val holder = databases.newHolder()

        holder.close()

        assertSame(HolderState.Closed, holder.state.value)
    }

    @Test
    fun exclusive_waitsForWorkInFlight_andLaterCallsRunAfterTheReopen() = runOnDefault {
        val holder = databases.holder()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockRan = CompletableDeferred<Unit>()
        val inFlight = async { holder.withDb { started.complete(Unit); release.await(); "done" } }
        started.await()

        val exclusive = async(start = CoroutineStart.UNDISPATCHED) {
            holder.exclusive {
                blockRan.complete(Unit)
                Reopen.Same
            }
        }
        val later = async(start = CoroutineStart.UNDISPATCHED) { holder.withDb { "later" } }

        assertSame(HolderState.Draining, holder.state.value)
        assertFalse("the block must wait for the call in flight", blockRan.isCompleted)
        assertTrue("a new call waits for the reopen", later.isActive)
        release.complete(Unit)
        assertEquals(AppResult.Ok("done"), inFlight.await())
        assertEquals(OpenResult.Opened, exclusive.await())
        assertTrue(blockRan.isCompleted)
        assertEquals("a call that waited across a same-data reopen runs", AppResult.Ok("later"), later.await())
    }

    @Test
    fun exclusive_cancelsCollectedFlows_closesRoom_andLeavesNoWal() = runOnDefault {
        val holder = databases.holder()
        val file = databases.files.live(GENERATION)
        val sql = holder.openDatabase().openHelper.writableDatabase
        assumeTrue("Room uses WAL on this device", sql.stringsOf("PRAGMA journal_mode").single() == "wal")
        TestRows(sql).person("Ann", 1)
        assertTrue("the write sits in the WAL", walOf(file).length() > 0)
        val emissions = Channel<List<EntityOptionRow>>(Channel.UNLIMITED)
        val collector = launch { holder.observe { it.personDao().observeOptions() }.collect(emissions::send) }
        receive(emissions)
        val open = holder.state.value as HolderState.Open

        val result = holder.exclusive {
            assertEquals("every flow on the instance ended", 0, open.activeFlows.get())
            assertFalse("closing Room checkpointed and removed the WAL", walOf(file).exists())
            Reopen.Same
        }

        assertEquals(OpenResult.Opened, result)
        assertEquals(1, receive(emissions).size) // re-subscribed to the reopened instance
        collector.cancelAndJoin()
    }

    @Test
    fun exclusive_flowThatIgnoresCancellation_isAbandonedAfterTheDrainTimeout() = runOnDefault {
        val holder = databases.holder()
        val stuck = startStuckCollection(holder)
        val open = holder.state.value as HolderState.Open

        val result = holder.exclusive {
            assertEquals("the stuck collection was left behind", 1, open.activeFlows.get())
            Reopen.Same
        }

        assertEquals(OpenResult.Opened, result)
        stuck.end()
    }

    /**
     * db-lifecycle.md § Close step 3 (DEC-81): `close()` runs the flow drain of exclusive mode, so it returns only
     * after a collected flow that finishes its cancellation late has ended, and the live key is zeroed only after
     * that (L-03).
     */
    @Test
    fun close_waitsForAFlowThatEndsItsCancellationLate_andZeroesTheKeyAfterIt() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 2 }))
        val liveKey = keys.last()
        val origin = System.nanoTime()
        fun now() = (System.nanoTime() - origin) / 1_000_000
        val subscribed = CompletableDeferred<Unit>()
        val flowEnd = AtomicLong(NOT_YET)
        val keyIntactAtFlowEnd = AtomicBoolean()
        val collector = launch {
            holder.observe {
                flow {
                    emit(Unit)
                    subscribed.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(LATE_MS)
                            keyIntactAtFlowEnd.set(liveKey.any { it != 0.toByte() })
                            flowEnd.set(now())
                        }
                    }
                }
            }.collect {}
        }
        subscribed.await()

        val closeStart = now()
        holder.close()
        val endedBeforeCloseReturned = flowEnd.get() != NOT_YET
        val closeEnd = now()
        val keyZeroAfterClose = liveKey.all { it == 0.toByte() }
        collector.cancelAndJoin() // a flow that has not ended yet ends within LATE_MS

        val timeline = "close() began at +$closeStart ms and returned at +$closeEnd ms; the flow ended at +${flowEnd.get()} ms"
        assertTrue("close() returned before the flow ended; $timeline", endedBeforeCloseReturned)
        assertTrue("the live key was zeroed before the flow ended; $timeline", keyIntactAtFlowEnd.get())
        assertTrue("the live key is zeroed by close", keyZeroAfterClose)
    }

    /**
     * The timeout path through `close()` (L-17, db-lifecycle.md § Close step 3): a collection that ignores its
     * cancellation is abandoned after the drain timeout, and `close()` still closes Room (no descriptor of the live
     * file is left: the stuck collection holds no connection) and zeroes the key.
     */
    @Test
    fun close_flowThatIgnoresCancellation_isAbandonedAfterTheDrainTimeout() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 4 }))
        val liveKey = keys.last()
        val file = databases.files.live(GENERATION)
        val stuck = startStuckCollection(holder)
        val open = holder.state.value as HolderState.Open

        val start = System.nanoTime()
        // Awaited apart from the call, so that a close() that waits for the stuck collection fails this test instead
        // of hanging it.
        val closing = async { holder.close() }
        val returned: Boolean
        val elapsedMs: Long
        val stateAfterClose: HolderState
        val countedAfterClose: Int
        val keyZeroAfterClose: Boolean
        val descriptorsAfterClose: Int
        try {
            returned = withTimeoutOrNull(LIMIT) { closing.await() } != null
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            stateAfterClose = holder.state.value
            countedAfterClose = open.activeFlows.get()
            keyZeroAfterClose = liveKey.all { it == 0.toByte() }
            descriptorsAfterClose = openDescriptors(file)
        } finally {
            stuck.release() // before any assertion: a failure must not leave the collection, and the test, stuck
        }
        closing.await()
        stuck.end()

        assertTrue("close() did not return within $LIMIT; it waited for the stuck collection", returned)
        assertTrue(
            "close() returned after $elapsedMs ms, before the drain timeout of $FLOW_DRAIN_TIMEOUT_MS ms",
            elapsedMs >= FLOW_DRAIN_TIMEOUT_MS - TIMER_TOLERANCE_MS,
        )
        assertSame(HolderState.Closed, stateAfterClose)
        assertEquals("the stuck collection was left behind", 1, countedAfterClose)
        assertEquals("Room was not closed after the drain timeout (open descriptors)", 0, descriptorsAfterClose)
        assertTrue("the live key is zeroed by close", keyZeroAfterClose)
    }

    /**
     * DEC-107: a Room flow on the generated DAO path ([roomFlow]) whose statement is running in SQLite when exclusive
     * mode cancels it ends only after the statement has returned (the flow's own end is stamped, so this holds
     * whatever Room's `close()` does), so the flow drain (db-lifecycle.md § Exclusive mode step 4) waits for the
     * statement, and Room closes with no connection in use and leaves no `-wal`. It pins the Room behavior the drain
     * relies on, so it passes with or without the drain in `close()`.
     */
    @Test
    fun exclusive_aFlowWhoseStatementIsRunningInSqlite_drainsOnlyAfterTheStatementReturns_andLeavesNoWal() = runOnDefault {
        val holder = databases.holder()
        val statement = startLongStatement(holder)
        val open = holder.state.value as HolderState.Open
        // Recorded in the block and asserted after the preconditions, which need the statement's end.
        var blockStart = NOT_YET
        var endAtBlock: Long? = null
        var flowEndAtBlock: Long? = null
        var flowsAtBlock = -1
        var walAtBlock = true

        val drainStart = statement.now()
        val endAtDrain = statement.endedAt()
        val walAtDrain = statement.wal.exists()
        val result = holder.exclusive {
            blockStart = statement.now()
            endAtBlock = statement.endedAt()
            flowEndAtBlock = statement.flowEndedAt()
            flowsAtBlock = open.activeFlows.get()
            walAtBlock = statement.wal.exists()
            Reopen.Same
        }
        statement.awaitEnd()

        val timeline = statement.timeline("the drain began" to drainStart, "the exclusive block began" to blockStart) +
            "; -wal when the drain began: $walAtDrain, in the block: $walAtBlock; " +
            "flows counted in the block: $flowsAtBlock"
        statement.assertPreconditions(drainStart, endAtDrain, walAtDrain, timeline)
        assertEquals(OpenResult.Opened, result)
        assertTrue("the exclusive block began while the statement was still running; $timeline", endAtBlock != null)
        assertTrue("the exclusive block began before the flow had ended; $timeline", flowEndAtBlock != null)
        assertTrue(
            "the flow ended before its statement had returned, or after the block began; $timeline",
            flowEndAtBlock!! >= endAtBlock!! && flowEndAtBlock!! <= blockStart,
        )
        assertEquals("a flow of the instance had not ended when the block began; $timeline", 0, flowsAtBlock)
        assertFalse("a -wal existed when the block began; $timeline", walAtBlock)
        statement.collector.cancelAndJoin()
    }

    /**
     * DEC-107, db-lifecycle.md § Close step 3: `close()` runs the same flow drain, so it returns only after a Room
     * flow's statement that was running in SQLite has returned and the flow has ended, and no `-wal` is left when it
     * returns.
     */
    @Test
    fun close_aFlowWhoseStatementIsRunningInSqlite_drainsOnlyAfterTheStatementReturns_andLeavesNoWal() = runOnDefault {
        val holder = databases.holder()
        val statement = startLongStatement(holder)

        val drainStart = statement.now()
        val endAtDrain = statement.endedAt()
        val walAtDrain = statement.wal.exists()
        holder.close()
        val endAtClose = statement.endedAt()
        val flowEndAtClose = statement.flowEndedAt()
        val closeEnd = statement.now()
        val walAtClose = statement.wal.exists()
        statement.awaitEnd()

        val timeline = statement.timeline("the drain began" to drainStart, "close() returned" to closeEnd) +
            "; -wal when the drain began: $walAtDrain, when close() returned: $walAtClose"
        statement.assertPreconditions(drainStart, endAtDrain, walAtDrain, timeline)
        assertTrue("close() returned while the statement was still running; $timeline", endAtClose != null)
        assertTrue("close() returned before the flow had ended; $timeline", flowEndAtClose != null)
        assertTrue("the flow ended before its statement had returned; $timeline", flowEndAtClose!! >= endAtClose!!)
        assertFalse("a -wal existed when close() returned; $timeline", walAtClose)
        statement.collector.cancelAndJoin()
    }

    @Test
    fun twentySwaps_whileAFlowIsCollected_reEmitEachTime() = runOnDefault {
        val holder = databases.holder()
        TestRows(holder.openDatabase().openHelper.writableDatabase).person("Ann", 1)
        val emissions = Channel<List<EntityOptionRow>>(Channel.UNLIMITED)
        // A failure in the collection (such as Room's IllegalStateException after close) fails this test's scope.
        val collector = launch { holder.observe { it.personDao().observeOptions() }.collect(emissions::send) }
        receive(emissions)

        repeat(20) { swap ->
            assertEquals(OpenResult.Opened, holder.exclusive { Reopen.Same })
            assertEquals("swap $swap", 1, receive(emissions).size)
        }
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun rawKey_isKeptWhileOpen_andZeroAfterClose() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { it.toByte() }))
        val liveKey = keys.last()
        assertEquals(67, liveKey.size)

        // Fifty concurrent reads make SQLCipher's pool open and key every reader connection from the key array.
        List(50) { async(Dispatchers.IO) { holder.withDb { it.countNotes() } } }.awaitAll()
            .forEach { assertEquals(AppResult.Ok(0L), it) }
        assertEquals(AppResult.Ok(0L), holder.withDb { it.countNotes() })
        assertTrue("the live key is intact while open", liveKey.any { it != 0.toByte() })

        holder.close()

        assertTrue("the live key is zeroed by close", liveKey.all { it == 0.toByte() })
    }

    @Test
    fun copyLiveKey_isACopyWhileOpen_andNullWhenClosed() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 7 }))
        val liveKey = keys.last()

        val copy = checkNotNull(holder.copyLiveKey())

        assertArrayEquals(liveKey, copy)
        assertNotSame(liveKey, copy)
        holder.close()
        assertNull(holder.copyLiveKey())
    }

    @Test
    fun exclusive_whenClosed_failsTransientWithoutRunningTheBlock() = runOnDefault {
        val holder = databases.newHolder()

        val result = holder.exclusive { error("never runs") }

        assertEquals(OpenResult.Failed(OpenFailureKind.Transient), result)
    }

    @Test
    fun exclusive_scopeLendsTheGenerationAndLiveKey() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 3 }))
        val liveKey = keys.last()

        holder.exclusive { scope ->
            assertEquals(GENERATION, scope.generation)
            assertSame(liveKey, scope.liveKey)
            assertSame(HolderState.Exclusive, holder.state.value)
            Reopen.Same
        }

        assertTrue(holder.state.value is HolderState.Open)
    }

    @Test
    fun exclusive_stayClosed_returnsNull_andZeroesTheKey() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 5 }))
        val liveKey = keys.last()

        assertNull(holder.exclusive { Reopen.StayClosed })

        assertSame(HolderState.Closed, holder.state.value)
        assertTrue(liveKey.all { it == 0.toByte() })
    }

    @Test
    fun exclusive_blockThatThrows_staysClosed_zeroesTheKey_andRethrows() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 9 }))
        val liveKey = keys.last()

        val thrown = runCatching { holder.exclusive { throw IllegalStateException("bug") } }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalStateException)
        assertSame(HolderState.Closed, holder.state.value)
        assertTrue(liveKey.all { it == 0.toByte() })
    }

    @Test
    fun exclusive_newGenerationWithNewData_switchesKey_andFailsCallsThatWaitedAcrossIt() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 1 }))
        val oldKey = keys.last()
        val newKey = TestKey.Raw(ByteArray(32) { 2 })
        val before = holder.state.value as HolderState.Open
        lateinit var waiter: Deferred<AppResult<Long>>

        val result = holder.exclusive {
            waiter = async(start = CoroutineStart.UNDISPATCHED) { holder.withDb { it.countNotes() } }
            assertTrue("the call waits during exclusive mode", waiter.isActive)
            databases.createLive(GENERATION + 1, newKey)
            Reopen.Generation(GENERATION + 1, newKey.bytes(), newData = true)
        }

        assertEquals(OpenResult.Opened, result)
        val after = holder.state.value as HolderState.Open
        assertEquals(GENERATION + 1, after.generation)
        assertEquals(before.dataset + 1, after.dataset)
        assertTrue("the old key is zeroed", oldKey.all { it == 0.toByte() })
        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), waiter.await())
        assertEquals(AppResult.Ok(0L), holder.withDb { it.countNotes() })
    }

    @Test
    fun exclusive_keyChange_runsCallsThatWaitedAcrossIt_onTheNewGeneration() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 1 }))
        val oldKey = keys.last()
        val newKey = TestKey.Raw(ByteArray(32) { 8 })
        lateinit var waiter: Deferred<AppResult<Long>>

        val result = holder.exclusive {
            waiter = async(start = CoroutineStart.UNDISPATCHED) {
                holder.withDb { (holder.state.value as HolderState.Open).generation }
            }
            assertTrue("the call waits during exclusive mode", waiter.isActive)
            databases.createLive(GENERATION + 1, newKey)
            Reopen.Generation(GENERATION + 1, newKey.bytes(), newData = false)
        }

        assertEquals(OpenResult.Opened, result)
        assertTrue("the old key is zeroed", oldKey.all { it == 0.toByte() })
        assertEquals(AppResult.Ok(GENERATION + 1), waiter.await())
    }

    @Test
    fun exclusive_newGenerationWithoutANewKey_keepsTheLiveKeyAndTheDataset() = runOnDefault {
        val key = TestKey.Raw(ByteArray(32) { 6 })
        val holder = databases.holder(key)
        val liveKey = keys.last()
        val before = holder.state.value as HolderState.Open

        val result = holder.exclusive {
            databases.createLive(GENERATION + 1, key)
            Reopen.Generation(GENERATION + 1, newKey = null, newData = false)
        }

        assertEquals(OpenResult.Opened, result)
        val after = holder.state.value as HolderState.Open
        assertEquals(GENERATION + 1, after.generation)
        assertEquals(before.dataset, after.dataset)
        assertTrue("the live key is intact", liveKey.any { it != 0.toByte() })
        holder.exclusive { scope ->
            assertSame("the same array is still the live key", liveKey, scope.liveKey)
            Reopen.Same
        }
    }

    @Test
    fun exclusive_failedReopen_reportsItsKind_andStaysClosed() = runOnDefault {
        val holder = databases.holder(TestKey.Raw(ByteArray(32) { 4 }))
        val liveKey = keys.last()

        val result = holder.exclusive {
            databases.files.live(GENERATION).writeBytes(ByteArray(0))
            Reopen.Same
        }

        assertEquals(OpenResult.Failed(OpenFailureKind.Corrupt), result)
        assertSame(HolderState.Closed, holder.state.value)
        assertTrue(liveKey.all { it == 0.toByte() })
        assertTrue("nothing deletes the file (DEC-85)", databases.files.live(GENERATION).exists())
    }

    @Test
    fun withDb_nestedCall_reusesTheOuterInstance() = runOnDefault {
        val holder = databases.holder()

        val result = holder.withDb { outer -> holder.withDb { inner -> inner === outer } }

        assertEquals(AppResult.Ok(AppResult.Ok(true)), result)
    }

    /**
     * The scene of the running-statement tests (DEC-107): on a WAL database with a write in its `-wal`, a real Room
     * flow ([roomFlow]) is collected through `observe`; its first statement is [LongQuery], calibrated on this
     * instance to run about [STATEMENT_MS]. The Room flow's own `onCompletion` stamps the flow's end; it runs before
     * the holder's `onCompletion`, which takes the flow off `activeFlows`. Returns [DRAIN_AFTER_MS] after that
     * statement began, while it is running in SQLite.
     */
    private suspend fun CoroutineScope.startLongStatement(holder: DatabaseHolder): LongStatement {
        val db = holder.openDatabase()
        val sql = db.openHelper.writableDatabase
        assumeTrue("Room uses WAL on this device", sql.stringsOf("PRAGMA journal_mode").single() == "wal")
        TestRows(sql).person("Ann", 1)
        val wal = walOf(databases.files.live(GENERATION))
        assertTrue("the write sits in the WAL", wal.length() > 0)
        val statement = LongStatement(wal, LongQuery.boundFor(STATEMENT_MS) { db.countTo(it) })
        statement.collector = launch {
            holder.observe { roomFlow(it, statement::countOn).onCompletion { statement.markFlowEnd() } }.collect {}
        }
        statement.awaitStart()
        delay(DRAIN_AFTER_MS)
        return statement
    }

    /**
     * A Room flow over `persons`, built by the function the generated DAO flows call (`PersonDao_Impl`:
     * `createFlow(__db, false, arrayOf("persons")) { _connection -> ... }`), so its query runs, and its cancellation
     * ends, on their path.
     */
    // createFlow is @RestrictTo(LIBRARY_GROUP_PREFIX). It is the exact function the generated DAO code calls, which is
    // the point here, so the suppression covers this one call and nothing else.
    @Suppress("RestrictedApi")
    private fun <R> roomFlow(db: AppDatabase, block: (SQLiteConnection) -> R): Flow<R> =
        createFlow(db, false, arrayOf("persons"), block)

    /** [LongQuery] counting to [bound] on Room's reader connection, the connection a Room flow's query takes. */
    private suspend fun AppDatabase.countTo(bound: Long): Long = useReaderConnection { connection ->
        connection.usePrepared(LongQuery.SQL) { statement ->
            statement.bindLong(1, bound)
            check(statement.step()) { "no row" }
            statement.getLong(0)
        }
    }

    private fun walOf(file: File) = File(file.path + "-wal")

    private suspend fun <T> receive(channel: Channel<T>): T = withTimeout(10.seconds) { channel.receive() }

    private fun AppDatabase.countNotes(): Long =
        openHelper.writableDatabase.longOf("SELECT count(*) FROM notes")

    private fun runOnDefault(block: suspend CoroutineScope.() -> Unit) =
        runBlocking(Dispatchers.Default) { withTimeout(60.seconds) { block() } }

    /**
     * The statement of [startLongStatement]'s flow. Its first run counts to [bound], stamping its start just before
     * `step()` and its end after it; a later run (on a reopened instance) counts to 1. Times are milliseconds on one
     * clock; [timeline] prints them from the statement's start.
     */
    private class LongStatement(val wal: File, private val bound: Long) {
        lateinit var collector: Job
        private val origin = System.nanoTime()
        private val runs = AtomicInteger()
        private val startMs = AtomicLong(NOT_YET)
        private val endMs = AtomicLong(NOT_YET)
        private val flowEndMs = AtomicLong(NOT_YET)
        private val outcome = AtomicReference<String>()

        fun now(): Long = (System.nanoTime() - origin) / 1_000_000

        /** When the statement returned, or null while it runs. */
        fun endedAt(): Long? = endMs.get().takeIf { it != NOT_YET }

        /** When the first instance of the flow ended, or null while it runs. */
        fun flowEndedAt(): Long? = flowEndMs.get().takeIf { it != NOT_YET }

        /**
         * The Room flow's `onCompletion`. Only the first instance's end counts: a later instance (on a reopened
         * instance) ends when the test cancels its collector.
         */
        fun markFlowEnd() {
            flowEndMs.compareAndSet(NOT_YET, now())
        }

        /** The flow's query: runs on Room's query thread, inside the flow's coroutine. */
        fun countOn(connection: SQLiteConnection): Long {
            val first = runs.getAndIncrement() == 0
            return connection.prepare(LongQuery.SQL).use { statement ->
                statement.bindLong(1, if (first) bound else 1L)
                if (first) startMs.set(now())
                val result = runCatching {
                    check(statement.step()) { "no row" }
                    statement.getLong(0)
                }
                if (first) {
                    endMs.set(now())
                    outcome.set(
                        result.fold({ if (it == bound) "full count" else "another count" }, { "threw ${it.javaClass.name}" }),
                    )
                }
                result.getOrThrow()
            }
        }

        suspend fun awaitStart() {
            withTimeout(LIMIT) { while (startMs.get() == NOT_YET) delay(POLL_MS) }
        }

        /**
         * Waits for the statement's end and then for the flow's, each at most [LIMIT], so that the preconditions and
         * the timeline see the whole picture.
         */
        suspend fun awaitEnd() {
            withTimeoutOrNull(LIMIT) { while (endMs.get() == NOT_YET) delay(POLL_MS) }
            withTimeoutOrNull(LIMIT) { while (flowEndMs.get() == NOT_YET) delay(POLL_MS) }
        }

        /**
         * The preconditions of a running-statement test, on its whole [timeline]: when the drain began at
         * [drainStart] the statement was still running ([endAtDrain] null) and a `-wal` existed; the statement ran on
         * for at least [MIN_OVERLAP_MS] after that, else the test shows nothing; and it ended at most
         * [MAX_OVERLAP_MS] after it, well inside the drain timeout, else the test would take the drain's timeout path
         * (a calibration failure, not a pass).
         */
        fun assertPreconditions(drainStart: Long, endAtDrain: Long?, walAtDrain: Boolean, timeline: String) {
            val end = endedAt()
            assertTrue("precondition: the statement did not end within $LIMIT; $timeline", end != null)
            assertTrue("precondition: the statement had ended when the drain began; $timeline", endAtDrain == null)
            assertTrue("precondition: no -wal existed when the drain began; $timeline", walAtDrain)
            val overlap = end!! - drainStart
            assertTrue(
                "precondition: the statement ran on less than $MIN_OVERLAP_MS ms after the drain began; $timeline",
                overlap >= MIN_OVERLAP_MS,
            )
            assertTrue(
                "precondition: the statement ran on more than $MAX_OVERLAP_MS ms after the drain began, too close to " +
                    "the drain timeout of $FLOW_DRAIN_TIMEOUT_MS ms (calibration); $timeline",
                overlap <= MAX_OVERLAP_MS,
            )
        }

        /** [moments], the statement's end and the flow's end, in milliseconds from the statement's start. */
        fun timeline(vararg moments: Pair<String, Long>): String {
            fun fromStart(at: Long?): String {
                if (at == null || at == NOT_YET) return "never"
                return (at - startMs.get()).let { if (it < 0) "$it" else "+$it" }
            }
            val ends = listOf(
                "the statement ended at ${fromStart(endedAt())} (${outcome.get() ?: "no outcome"})",
                "the flow ended at ${fromStart(flowEndedAt())}",
            )
            return (moments.map { (what, at) -> "$what at ${fromStart(at)}" } + ends)
                .joinToString(prefix = "timeline in ms from the statement's start: ")
        }
    }

    private companion object {
        /** A time not stamped yet. */
        const val NOT_YET = -1L

        /**
         * The flow drain's timeout, db-lifecycle.md § Exclusive mode step 4 and § Close step 3 ("at most 2 s");
         * `DefaultDatabaseHolder`'s own constant is private.
         */
        const val FLOW_DRAIN_TIMEOUT_MS = 2_000L

        /** Timer slack below [FLOW_DRAIN_TIMEOUT_MS] for a drain timed from outside the holder. */
        const val TIMER_TOLERANCE_MS = 100L

        /** How long the late flow takes to end after its cancellation: far above timer noise, inside the drain timeout. */
        const val LATE_MS = 500L

        /** The long statement's calibrated running time: long against [DRAIN_AFTER_MS], short against the timeout. */
        const val STATEMENT_MS = 800L

        /** How long after the long statement began the drain begins. */
        const val DRAIN_AFTER_MS = 200L

        /** The least time the statement must still run after the drain began. */
        const val MIN_OVERLAP_MS = 200L

        /** The latest end of the statement after the drain began: well inside [FLOW_DRAIN_TIMEOUT_MS]. */
        const val MAX_OVERLAP_MS = 1_500L

        const val POLL_MS = 5L

        /** Bounds every wait of these tests, so that a failure reports instead of hanging. */
        val LIMIT = 10.seconds
    }
}

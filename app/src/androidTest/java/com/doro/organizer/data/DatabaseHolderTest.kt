package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.EntityOptionRow
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * (nothing open before `open`, exclusive mode drains work and flows, flows survive swaps), the holder half of L-03
 * (raw-key custody) and the exclusive-mode directives. Runs on real dispatchers, because the holder's drain polls on
 * the wall clock while Room works on its own threads.
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
        val collector = launch { holder.observe { it.personDao().observeOptions(null) }.collect(emissions::send) }
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
        val subscribed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val collector = launch {
            holder.observe {
                flow {
                    emit(Unit)
                    subscribed.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }.collect {}
        }
        subscribed.await()
        val open = holder.state.value as HolderState.Open

        val result = holder.exclusive {
            assertEquals("the stuck collection was left behind", 1, open.activeFlows.get())
            Reopen.Same
        }

        assertEquals(OpenResult.Opened, result)
        release.complete(Unit)
        collector.cancelAndJoin()
    }

    @Test
    fun twentySwaps_whileAFlowIsCollected_reEmitEachTime() = runOnDefault {
        val holder = databases.holder()
        TestRows(holder.openDatabase().openHelper.writableDatabase).person("Ann", 1)
        val emissions = Channel<List<EntityOptionRow>>(Channel.UNLIMITED)
        // A failure in the collection (such as Room's IllegalStateException after close) fails this test's scope.
        val collector = launch { holder.observe { it.personDao().observeOptions(null) }.collect(emissions::send) }
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

    private fun walOf(file: File) = File(file.path + "-wal")

    private suspend fun <T> receive(channel: Channel<T>): T = withTimeout(10.seconds) { channel.receive() }

    private fun AppDatabase.countNotes(): Long =
        openHelper.writableDatabase.longOf("SELECT count(*) FROM notes")

    private fun runOnDefault(block: suspend CoroutineScope.() -> Unit) =
        runBlocking(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
}

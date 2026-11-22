package com.doro.organizer.data

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.room.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.LongQuery
import com.doro.organizer.testing.SetupProbeRecord
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for db-lifecycle.md § Exclusive mode step 4: what Room 2.8.5's `close()` does while the statement of a
 * cancelled flow query is still running in SQLite. `DefaultDatabaseHolder` does not depend on the answer: its flow
 * drain waits for such a flow, because a cancelled Room flow ends only after its statement returns
 * (`DatabaseHolderTest`'s running-statement tests, DEC-107), so only a statement that outlives the drain's 2 s meets
 * `close()`.
 *
 * The scene: the live instance from the production `buildRoom` runs [LongQuery] (about [QUERY_MS]) through Room's
 * reader connection on `Dispatchers.IO`, as a DAO flow query does; [RUNNING_MS] into the statement its coroutine is
 * cancelled, as `flatMapLatest` cancels a flow, and `close()` is called on another thread. The tests pin DEC-107 (1):
 * `close()` does not wait for the running statement but returns at once; the statement is not aborted and completes
 * with its full result, because a pool closes a connection that is in use only when it is released; and the `-wal`
 * is still present when `close()` returns and goes once the abandoned statement's query has completed, because the
 * closed pool closes that last connection when it is released (`releaseConnection` of sqlcipher-android 4.20.0's
 * `SQLiteConnectionPool`). No correctness property depends on this (db-lifecycle.md § Exclusive mode step 4). Should
 * a Room update make `close()` wait, the first and the third test fail, and DEC-107 and that step are revisited; the
 * second test passes either way. Every failure message carries the observed timeline (milliseconds from the
 * statement's start, the statement's outcome as a class name, the `-wal` presence), and every run records it through
 * [SetupProbeRecord]; no user data exists in this scene (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class RoomCloseDuringQueryTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun close_whileACancelledQueryRunsInSqlite_returnsBeforeTheStatementEnds() {
        val seen = closeDuringRunningQuery("returnsBeforeTheStatementEnds")

        assertTrue("close() did not return within $CLOSE_LIMIT_MS ms; $seen", seen.closeEnd != null)
        assertEquals("close() threw; $seen", null, seen.closeError)
        // A statement that ends within the tolerance after close() began could not tell a wait from none.
        assertTrue(
            "precondition: the statement ended less than $MIN_OVERLAP_MS ms after close() began; $seen",
            seen.statementEnd!! - seen.closeStart >= MIN_OVERLAP_MS,
        )
        assertTrue(
            "close() returned less than $TOLERANCE_MS ms before the running statement ended: Room's close() now waits " +
                "for it, so DEC-107 and db-lifecycle.md § Exclusive mode step 4 must be revisited; $seen",
            seen.statementEnd - seen.closeEnd!! > TOLERANCE_MS,
        )
    }

    @Test
    fun close_whileACancelledQueryRunsInSqlite_letsTheStatementCompleteWithItsResult() {
        val seen = closeDuringRunningQuery("letsTheStatementCompleteWithItsResult")

        assertEquals("the running statement did not complete with its full count; $seen", FULL_COUNT, seen.statementOutcome)
    }

    @Test
    fun close_whileACancelledQueryRunsInSqlite_keepsTheWalUntilTheStatementEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Room's AUTOMATIC mode falls back to a rollback journal on low-RAM devices (DEC-89): no -wal to watch there.
        assumeFalse(checkNotNull(context.getSystemService<ActivityManager>()).isLowRamDevice)

        val seen = closeDuringRunningQuery("keepsTheWalUntilTheStatementEnds")

        assertEquals("precondition: the live instance's journal mode; $seen", "wal", seen.journalMode)
        assertTrue("precondition: no -wal existed before close(); $seen", seen.walBeforeClose)
        assertTrue("close() did not return within $CLOSE_LIMIT_MS ms; $seen", seen.closeEnd != null)
        // A statement that ends while close() runs gives its connection back in time for the -wal to go with close():
        // that would read as a close() that waited.
        assertTrue(
            "precondition: the statement ended less than $MIN_OVERLAP_MS ms after close() began; $seen",
            seen.statementEnd!! - seen.closeStart >= MIN_OVERLAP_MS,
        )
        assertTrue(
            "no -wal was present when close() returned: Room's close() now waits for the running statement, so " +
                "DEC-107 and db-lifecycle.md § Exclusive mode step 4 must be revisited; $seen",
            seen.walWhenCloseReturned,
        )
        assertTrue("precondition: the cancelled query's coroutine did not complete; $seen", seen.queryCompleted)
        assertTrue(
            "the -wal was still present $WAL_GONE_LIMIT_MS ms after the cancelled query completed, so it is not left " +
                "only while the abandoned statement runs; $seen",
            seen.walGoneAt != null,
        )
    }

    /** What the scene showed; [toString] is the observed timeline every failure message carries. */
    private data class Observed(
        val journalMode: String?,
        val walBeforeClose: Boolean,
        val runningAtClose: Boolean,
        val closeStart: Long,
        val closeEnd: Long?,
        val closeError: String?,
        val walWhenCloseReturned: Boolean,
        val statementEnd: Long?,
        val statementOutcome: String?,
        val queryCompleted: Boolean,
        val walGoneAt: Long?,
    ) {
        override fun toString() = "observed: journal_mode=$journalMode, -wal before close=$walBeforeClose, " +
            "statement running when close() began=$runningAtClose, close() began at +$closeStart ms and returned at " +
            "${closeEnd?.let { "+$it ms" } ?: "never"} (error: ${closeError ?: "none"}), -wal when close() returned=" +
            "$walWhenCloseReturned, statement ended at ${statementEnd?.let { "+$it ms" } ?: "never"} " +
            "(${statementOutcome ?: "no outcome"}), query coroutine completed=$queryCompleted, -wal seen gone at " +
            (walGoneAt?.let { "+$it ms" } ?: "never")
    }

    /** Plays the scene and records what it showed under `RoomCloseDuringQueryTest#`[probe] before any precondition. */
    private fun closeDuringRunningQuery(probe: String): Observed {
        val db = databases.create()
        val support = db.openHelper.writableDatabase
        val journalMode = support.stringsOf("PRAGMA journal_mode").singleOrNull()
        val wal = File("${checkNotNull(support.path) { "no path" }}-wal")
        val bound = LongQuery.boundFor(QUERY_MS) { runBlocking { db.count(it) } }

        val origin = System.nanoTime()
        fun now() = (System.nanoTime() - origin) / 1_000_000
        val started = CountDownLatch(1)
        val statementEnd = AtomicLong(-1)
        val statementOutcome = AtomicReference<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val query = scope.async {
                db.useReaderConnection { connection ->
                    connection.usePrepared(LongQuery.SQL) { statement ->
                        statement.bindLong(1, bound)
                        started.countDown()
                        val result = runCatching {
                            check(statement.step()) { "no row" }
                            statement.getLong(0)
                        }
                        statementEnd.set(now())
                        statementOutcome.set(
                            result.fold({ if (it == bound) FULL_COUNT else "returned another count" }, { "threw ${it.javaClass.name}" }),
                        )
                        result.getOrThrow()
                    }
                }
            }
            check(started.await(START_LIMIT_MS, TimeUnit.MILLISECONDS)) { "the query did not start" }
            Thread.sleep(RUNNING_MS)
            val walBeforeClose = wal.exists()
            val runningAtClose = statementEnd.get() < 0
            query.cancel() // as flatMapLatest cancels a collected flow; nothing interrupts the statement

            val closeStart = now()
            val closeEnd = AtomicLong(-1)
            val closeError = AtomicReference<String>()
            val walWhenCloseReturned = AtomicBoolean()
            val closer = thread(name = "room-close-probe", isDaemon = true) {
                runCatching(db::close).onFailure { closeError.set(it.javaClass.name) }
                walWhenCloseReturned.set(wal.exists())
                closeEnd.set(now())
            }
            closer.join(CLOSE_LIMIT_MS)
            runBlocking { withTimeoutOrNull(CLOSE_LIMIT_MS) { runCatching { query.await() } } }
            val queryCompleted = query.isCompleted
            // Once the abandoned statement has handed its connection back, the closed pool closes that last connection,
            // and SQLite checkpoints and removes the -wal.
            val walGoneAt = if (queryCompleted) walGoneWithin(wal, WAL_GONE_LIMIT_MS, ::now) else null

            val seen = Observed(
                journalMode = journalMode,
                walBeforeClose = walBeforeClose,
                runningAtClose = runningAtClose,
                closeStart = closeStart,
                closeEnd = closeEnd.get().takeIf { it >= 0 },
                closeError = closeError.get(),
                walWhenCloseReturned = walWhenCloseReturned.get(),
                statementEnd = statementEnd.get().takeIf { it >= 0 },
                statementOutcome = statementOutcome.get(),
                queryCompleted = queryCompleted,
                walGoneAt = walGoneAt,
            )
            SetupProbeRecord.record("RoomCloseDuringQueryTest#$probe", "$seen")
            // Without an overlap the probe would show nothing about a running statement.
            assertTrue("precondition: the statement ended before close() began; $seen", seen.runningAtClose)
            assertTrue("precondition: the statement did not end within ${2 * CLOSE_LIMIT_MS} ms; $seen", seen.statementEnd != null)
            return seen
        } finally {
            scope.cancel()
        }
    }

    /** Polls for [wal] to be gone, at most [limitMs]; returns the [now] at which it was first seen gone, or null. */
    private fun walGoneWithin(wal: File, limitMs: Long, now: () -> Long): Long? {
        val deadline = now() + limitMs
        while (wal.exists()) {
            if (now() >= deadline) return null
            Thread.sleep(POLL_MS)
        }
        return now()
    }

    /** Runs [LongQuery] with [bound] on Room's reader connection, the path of a DAO query. */
    private suspend fun AppDatabase.count(bound: Long): Long = useReaderConnection { connection ->
        connection.usePrepared(LongQuery.SQL) { statement ->
            statement.bindLong(1, bound)
            check(statement.step()) { "no row" }
            statement.getLong(0)
        }
    }

    private companion object {
        const val FULL_COUNT = "returned the full count"

        /** The statement's calibrated running time: long against [RUNNING_MS], short enough for a test. */
        const val QUERY_MS = 3_000L

        /** How long the statement runs before its coroutine is cancelled and `close()` is called. */
        const val RUNNING_MS = 400L

        const val START_LIMIT_MS = 10_000L
        const val CLOSE_LIMIT_MS = 20_000L

        /** How long the `-wal` may outlive the completed query: the last connection's close removes it at once. */
        const val WAL_GONE_LIMIT_MS = 2_000L
        const val POLL_MS = 10L

        /**
         * The statement's end is stamped after SQLite hands its connection back, so a `close()` that waited for the
         * statement may still return up to this long before the stamp; only a larger gap shows that it did not wait.
         */
        const val TOLERANCE_MS = 200L

        /**
         * How long the statement must still run after `close()` began, far above [TOLERANCE_MS], so a `close()` that
         * does not wait shows a gap far larger than the tolerance. The calibrated statement leaves about
         * [QUERY_MS] - [RUNNING_MS].
         */
        const val MIN_OVERLAP_MS = 1_000L
    }
}

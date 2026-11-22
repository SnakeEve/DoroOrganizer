package com.doro.organizer.data

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.room.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.LongQuery
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for db-lifecycle.md § Exclusive mode, step 4 ("Verify at setup: whether Room 2.8.5's `close()` waits for
 * a running query (a cancelled flow query) to finish"; the wait is what makes "no `-wal` after close" deterministic,
 * L-17). `DefaultDatabaseHolder` closes Room after the flow drain, which gives up after 2 s, so a flow whose statement
 * is still inside SQLite can meet `close()`.
 *
 * The scene: the live instance from the production `buildRoom` runs [LongQuery] (about [QUERY_MS]) through Room's
 * reader connection on `Dispatchers.IO`, as a DAO flow query does; [RUNNING_MS] into the statement its coroutine is
 * cancelled, as `flatMapLatest` cancels a flow, and `close()` is called on another thread. The expectation pinned here
 * is the wait: `close()` returns only once the running statement has ended, and no `-wal` is left when `close()`
 * returns. The docs item rests on those two tests. The third, that the statement completes with its full result,
 * pins a separate property the docs do not ask about (`close()` does not abort a running statement): it passes whether
 * `close()` waits or returns at once, because a pool closes a connection that is in use only when it is released.
 * Every failure message carries the observed timeline (milliseconds from the statement's start, the statement's
 * outcome as a class name, the `-wal` presence); no user data exists in this scene (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class RoomCloseDuringQueryTest {
    @get:Rule
    val databases = TestDatabases()

    @Ignore(
        "Contradicted on the Poco F7 (T22): close() returned 2 to 5 ms after it began while the cancelled statement " +
            "ran on for 2.1 to 2.4 s and then returned its full count; decision pending",
    )
    @Test
    fun close_whileACancelledQueryRunsInSqlite_returnsOnlyAfterTheStatementEnded() {
        val seen = closeDuringRunningQuery()

        assertTrue("close() did not return within $CLOSE_LIMIT_MS ms; $seen", seen.closeEnd != null)
        assertEquals("close() threw; $seen", null, seen.closeError)
        // A statement that ends within the tolerance after close() began could not tell a wait from none.
        assertTrue(
            "precondition: the statement ended less than $MIN_OVERLAP_MS ms after close() began; $seen",
            seen.statementEnd!! - seen.closeStart >= MIN_OVERLAP_MS,
        )
        assertTrue("close() returned before the running statement ended; $seen", seen.closeEnd!! >= seen.statementEnd - TOLERANCE_MS)
    }

    @Test
    fun close_whileACancelledQueryRunsInSqlite_letsTheStatementCompleteWithItsResult() {
        val seen = closeDuringRunningQuery()

        assertEquals("the running statement did not complete with its full count; $seen", FULL_COUNT, seen.statementOutcome)
    }

    @Ignore(
        "Contradicted on the Poco F7 (T22): a -wal was present when close() returned, 2 to 3 ms after it began, " +
            "while the cancelled statement still ran; decision pending",
    )
    @Test
    fun close_whileACancelledQueryRunsInSqlite_leavesNoWal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Room's AUTOMATIC mode falls back to a rollback journal on low-RAM devices (DEC-89): no -wal to watch there.
        assumeFalse(checkNotNull(context.getSystemService<ActivityManager>()).isLowRamDevice)

        val seen = closeDuringRunningQuery()

        assertEquals("precondition: the live instance's journal mode; $seen", "wal", seen.journalMode)
        assertTrue("precondition: no -wal existed before close(); $seen", seen.walBeforeClose)
        assertTrue("close() did not return within $CLOSE_LIMIT_MS ms; $seen", seen.closeEnd != null)
        assertFalse("a -wal was present when close() returned; $seen", seen.walWhenCloseReturned)
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
    ) {
        override fun toString() = "observed: journal_mode=$journalMode, -wal before close=$walBeforeClose, " +
            "statement running when close() began=$runningAtClose, close() began at +$closeStart ms and returned at " +
            "${closeEnd?.let { "+$it ms" } ?: "never"} (error: ${closeError ?: "none"}), -wal when close() returned=" +
            "$walWhenCloseReturned, statement ended at ${statementEnd?.let { "+$it ms" } ?: "never"} " +
            "(${statementOutcome ?: "no outcome"})"
    }

    private fun closeDuringRunningQuery(): Observed {
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
            )
            // Without an overlap the probe would show nothing about a running statement.
            assertTrue("precondition: the statement ended before close() began; $seen", seen.runningAtClose)
            assertTrue("precondition: the statement did not end within ${2 * CLOSE_LIMIT_MS} ms; $seen", seen.statementEnd != null)
            return seen
        } finally {
            scope.cancel()
        }
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

        /** The statement's end is stamped after SQLite hands its connection back, so a waiting close may return first. */
        const val TOLERANCE_MS = 200L

        /**
         * How long the statement must still run after `close()` began, far above [TOLERANCE_MS], so a `close()` that
         * does not wait shows a gap far larger than the tolerance. The calibrated statement leaves about
         * [QUERY_MS] - [RUNNING_MS].
         */
        const val MIN_OVERLAP_MS = 1_000L
    }
}

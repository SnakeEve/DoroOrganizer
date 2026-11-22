package com.doro.organizer.data.maintenance

import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.LongQuery
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for db-lifecycle.md § Restore, § Steps, step 3 ("Verify at setup: … whether sqlcipher-android's
 * `query`/`rawQuery` accepts a `CancellationSignal` (if it does not, a running statement finishes first, bounded by the
 * file size)"). `cancelRestore()` triggers the signal that every candidate statement runs with; `QueryConnection`
 * passes it to sqlcipher-android's `rawQuery`.
 *
 * The scene: a test database opened by the production `QueryConnection.open` with a signal runs [LongQuery] (about
 * [QUERY_MS]) on a thread; [RUNNING_MS] into the statement the signal is cancelled. Expected: the statement stops
 * within [PROMPT_MS] of the cancel by throwing, long before it would have finished. Failure messages state the
 * observed outcome (the result or the exception class) and timeline; no user data exists in this scene (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class CancellationSignalStopsStatementTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun rawQuery_cancelledWhileItsStatementRuns_stopsPromptly() {
        val file = databases.file("candidate.db")
        databases.createRaw(file, TestKey.Plain).close()
        val bound = QueryConnection.open(file, ByteArray(0), null).use { connection ->
            LongQuery.boundFor(QUERY_MS) { connection.long(LongQuery.SQL, it.toString()) }
        }

        val signal = CancellationSignal()
        QueryConnection.open(file, ByteArray(0), signal).use { connection ->
            val origin = System.nanoTime()
            fun now() = (System.nanoTime() - origin) / 1_000_000
            val ended = CountDownLatch(1)
            val endedAt = AtomicLong(-1)
            val outcome = AtomicReference<String>()
            val runner = thread(name = "cancel-signal-probe", isDaemon = true) {
                outcome.set(
                    runCatching { connection.long(LongQuery.SQL, bound.toString()) }
                        .fold({ if (it == bound) "returned the full count" else "returned another count" }, { "$THREW ${it.javaClass.name}" }),
                )
                endedAt.set(now())
                ended.countDown()
            }
            // Without a running statement the cancel would show nothing about stopping one.
            assertTrue(
                "precondition: the statement ended before the cancel (at +${endedAt.get()} ms: ${outcome.get()})",
                !ended.await(RUNNING_MS, TimeUnit.MILLISECONDS),
            )

            val cancelledAt = now()
            signal.cancel()
            val stopped = ended.await(END_LIMIT_MS, TimeUnit.MILLISECONDS)
            runner.join(END_LIMIT_MS)
            val seen = "observed: cancelled at +$cancelledAt ms, statement ended at " +
                "${endedAt.get().takeIf { it >= 0 }?.let { "+$it ms" } ?: "never"} (${outcome.get() ?: "no outcome"}), " +
                "calibrated running time about $QUERY_MS ms"

            assertTrue("the statement did not end within $END_LIMIT_MS ms of the cancel; $seen", stopped)
            assertTrue("the statement was not stopped by the cancel; $seen", outcome.get()?.startsWith("$THREW ") == true)
            assertTrue("the statement stopped only ${endedAt.get() - cancelledAt} ms after the cancel; $seen", endedAt.get() - cancelledAt <= PROMPT_MS)
        }
    }

    private companion object {
        const val THREW = "threw"

        /** The statement's calibrated running time: long against [RUNNING_MS] + [PROMPT_MS]. */
        const val QUERY_MS = 6_000L

        /** How long the statement runs before the signal is cancelled. */
        const val RUNNING_MS = 500L

        /** The cancel must stop the statement within this time; an ignored cancel leaves about 5 s to run. */
        const val PROMPT_MS = 1_000L

        const val END_LIMIT_MS = 60_000L
    }
}

package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.ownLogThrough
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The flow drain's timeout line (testing.md L-17; db-lifecycle.md § Exclusive mode step 4, § Close step 3): both
 * callers run the one drain, and each logs its own constant line, which the test reads back from this process's
 * logcat. A [StuckCollection], the scene of `DatabaseHolderTest`'s two timeout tests, makes the drain time out. Only
 * the debug `AppLog` writes to logcat; the release one, which `qa` and `benchmark` also use, logs nothing. The test
 * therefore stays in package `data` of `src/androidTest`, which only the debug test APK compiles (DEC-103 (5)). Each
 * test logs a marker line through `AppLog` before and after its drain and reads only the lines between the two, so a
 * line of an earlier process with the same pid is ignored, and a log read that returns nothing or an `AppLog` that
 * logs nothing fails it.
 */
@RunWith(AndroidJUnit4::class)
class DrainTimeoutLogTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun close_flowDrainTimeout_logsTheCloseLine() = runOnDefault {
        val holder = databases.holder()

        val lines = loggedByATimedOutDrain(holder) { holder.close() }

        assertEquals("lines between the markers: $lines", listOf(CLOSE_LINE), lines.filter { DRAIN_TIMEOUT in it })
    }

    @Test
    fun exclusive_flowDrainTimeout_logsTheExclusiveLine() = runOnDefault {
        val holder = databases.holder()

        val lines = loggedByATimedOutDrain(holder) {
            assertEquals(OpenResult.Opened, holder.exclusive { Reopen.Same })
        }

        assertEquals("lines between the markers: $lines", listOf(EXCLUSIVE_LINE), lines.filter { DRAIN_TIMEOUT in it })
    }

    /**
     * Runs [drain] (`close` or `exclusive` on [holder]) while a [StuckCollection] makes its flow drain time out,
     * between two marker lines logged through `AppLog`, and returns the lines this process logged under the app's tag
     * in between. [drain] runs apart from the test, and the collection is released once [drain] has returned or
     * [LIMIT] has passed, so a drain that waits for the collection fails the test instead of hanging it.
     */
    private suspend fun CoroutineScope.loggedByATimedOutDrain(
        holder: DatabaseHolder,
        drain: suspend () -> Unit,
    ): List<String> {
        val stuck = startStuckCollection(holder)
        val token = UUID.randomUUID()
        val start = "DrainTimeoutLogTest: start $token"
        val end = "DrainTimeoutLogTest: end $token"
        AppLog.d(start)
        val draining = async { drain() }
        val returned = try {
            withTimeoutOrNull(LIMIT) { draining.await() } != null
        } finally {
            stuck.release()
        }
        draining.await()
        stuck.end()
        AppLog.d(end)
        assertTrue("the drain did not return within $LIMIT; it waited for the stuck collection", returned)
        return ownLinesBetween(start, end)
    }

    /**
     * The lines this process logged under the app's tag strictly between the marker lines [start] and [end]. Reads the
     * log again until [end] is in it, for at most [LIMIT], because logd may make a line readable only some time after
     * it was written; a marker missing from the log fails the test.
     */
    private suspend fun ownLinesBetween(start: String, end: String): List<String> {
        val lines = ownLogThrough(end, "-s $TAG:D", LIMIT)
        val from = lines.indexOfLast { start in it }
        val to = lines.indexOfLast { end in it }
        assertTrue("the start marker is not in this process's log (${lines.size} lines read)", from >= 0)
        assertTrue("the end marker did not reach the log within $LIMIT (${lines.size} lines read)", to > from)
        return lines.subList(from + 1, to)
    }

    private fun runOnDefault(block: suspend CoroutineScope.() -> Unit) =
        runBlocking(Dispatchers.Default) { withTimeout(60.seconds) { block() } }

    private companion object {
        /** The tag of the debug `AppLog`. */
        const val TAG = "Doro"

        /** The end of both timeout lines. */
        const val DRAIN_TIMEOUT = "flow drain timeout"

        /** The timeout lines, copied from db-lifecycle.md; `DefaultDatabaseHolder`'s own constants are private. */
        const val EXCLUSIVE_LINE = "exclusive: flow drain timeout"
        const val CLOSE_LINE = "close: flow drain timeout"

        /** Bounds the drain and the wait for the log, so that a failure reports instead of hanging. */
        val LIMIT = 10.seconds
    }
}

package com.doro.organizer.data.maintenance

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for db-lifecycle.md § Restore, § Steps, step 3 ("Verify at setup: closing a `ParcelFileDescriptor`
 * unblocks a `read()` pending on it (libcore's `IoBridge` signals blocked threads)"). `cancelRestore()` relies on it
 * during `COPY_INPUT`: `SafDocuments.openInput` hands the provider's descriptor to the cancel, which closes it while
 * the copy reads through `ParcelFileDescriptor.AutoCloseInputStream`.
 *
 * A pipe stands for a streaming provider (one that serves its document through a pipe and has not written yet): its
 * read end is wrapped as `SafDocuments.openInput` wraps a descriptor, a thread blocks in `read()`, and the test closes
 * the descriptor. Expected: `read()` returns promptly, with the end of the stream or an `IOException` (both are
 * handled as the cancel by `RestorePipeline.copyInput`), never with data. Failure messages state the observed
 * outcome (byte count, end of stream or exception class) and timing; no user data exists in this scene (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class DescriptorCloseUnblocksReadTest {
    @Test
    fun closingTheDescriptor_unblocksAReadPendingOnIt() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        var reader: Thread? = null
        try {
            val input = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
            val entered = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val outcome = AtomicReference<String>()
            reader = thread(name = "descriptor-read-probe", isDaemon = true) {
                entered.countDown()
                outcome.set(
                    try {
                        val n = input.read(ByteArray(BLOCK))
                        if (n < 0) END_OF_STREAM else "returned $n bytes"
                    } catch (e: IOException) {
                        "$THREW ${e.javaClass.name}"
                    } catch (e: Throwable) {
                        "threw a non-IOException ${e.javaClass.name}"
                    },
                )
                returned.countDown()
            }
            check(entered.await(START_LIMIT_MS, TimeUnit.MILLISECONDS)) { "the reader did not start" }
            // Without a pending read the close would show nothing about unblocking one.
            assertTrue(
                "precondition: read() returned before the descriptor was closed: ${outcome.get()}",
                !returned.await(PENDING_MS, TimeUnit.MILLISECONDS),
            )

            val closedAt = System.nanoTime()
            readEnd.close()
            val unblocked = returned.await(UNBLOCK_LIMIT_MS, TimeUnit.MILLISECONDS)
            val afterMs = (System.nanoTime() - closedAt) / 1_000_000

            assertTrue(
                "observed: read() still pending $UNBLOCK_LIMIT_MS ms after ParcelFileDescriptor.close() " +
                    "(reader thread state ${reader.state})",
                unblocked,
            )
            val seen = outcome.get()
            assertTrue(
                "observed: read() returned $afterMs ms after close() with: $seen",
                seen == END_OF_STREAM || seen?.startsWith("$THREW ") == true,
            )
        } finally {
            // Ends a read that the close did not unblock, so the probe leaves no blocked thread behind.
            writeEnd.close()
            runCatching { readEnd.close() }
            reader?.join(UNBLOCK_LIMIT_MS)
        }
    }

    private companion object {
        /** The block size of `COPY_INPUT` (64 KiB). */
        const val BLOCK = 64 * 1024

        const val END_OF_STREAM = "end of stream"
        const val THREW = "threw IOException"

        const val START_LIMIT_MS = 10_000L

        /** How long the read must stay pending before the close: it has no data and its writer stays open. */
        const val PENDING_MS = 300L

        const val UNBLOCK_LIMIT_MS = 2_000L
    }
}

package com.doro.organizer.core.io

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.SyncFailedException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `syncFile` (core/io/SyncFile.kt) keeps the errno of a failed fsync as the cause of its `SyncFailedException`, so
 * DbMaintenance classifies a failed sync of the key-state or of a staged file by that errno (db-lifecycle.md
 * § Generation commit, § Failure classification; DEC-107 (4)). The failures are real fsync failures, made without any
 * disk pressure: a descriptor that is already closed (`EBADF`) and the write end of a pipe (`EINVAL`: a pipe cannot be
 * synced). Neither writes a byte to the storage. `FileDescriptor.sync()` fails both without an errno
 * (`FileDescriptorSyncLosesErrnoTest`), so the two failure tests fail if `syncFile` goes back to it. The success test
 * writes four bytes to a file in its own folder of the app's cache directory and deletes the folder afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SyncFileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File get() = File(context.cacheDir, "test-sync-file")

    @After
    fun removeFiles() {
        root.deleteRecursively()
    }

    @Test
    fun aClosedDescriptor_throwsSyncFailedException_causedByItsErrnoEbadf() {
        val (read, write) = Os.pipe()
        Os.close(read)
        Os.close(write) // Os.close also invalidates the FileDescriptor: the sync below reaches the kernel with -1

        val thrown = assertThrows(SyncFailedException::class.java) { syncFile(write) }

        assertKeepsErrno(OsConstants.EBADF, thrown)
    }

    @Test
    fun aPipe_throwsSyncFailedException_causedByItsErrnoEinval() {
        val (read, write) = Os.pipe()
        try {
            val thrown = assertThrows(SyncFailedException::class.java) { syncFile(write) }

            assertKeepsErrno(OsConstants.EINVAL, thrown)
        } finally {
            Os.close(read)
            Os.close(write)
        }
    }

    /** The two production forms: the open `.new` stream of the key-state, and a staged file opened read-only. */
    @Test
    fun aWrittenFile_syncs_throughItsOutputStream_andThroughAReadOnlyDescriptor() {
        check(root.isDirectory || root.mkdirs()) { "cannot create the sync test root" }
        val file = File(Files.createTempDirectory(root.toPath(), "sf").toFile(), "synced")

        FileOutputStream(file).use { out ->
            out.write(BYTES)
            syncFile(out.fd)
        }
        RandomAccessFile(file, "r").use { syncFile(it.fd) }

        assertArrayEquals(BYTES, file.readBytes())
    }

    /**
     * The cause is the `ErrnoException` with [errno], and the cause chain holds no other errno: the chain that
     * `isStorageFull()` walks. Failure messages name classes and errno names only.
     */
    private fun assertKeepsErrno(errno: Int, thrown: SyncFailedException) {
        val chain = generateSequence<Throwable>(thrown) { it.cause }.toList()
        val seen = chain.joinToString(" <- ") { t ->
            t.javaClass.simpleName + ((t as? ErrnoException)?.let { "(${OsConstants.errnoName(it.errno)})" } ?: "")
        }
        val cause = thrown.cause
        assertTrue("the cause is not an ErrnoException: $seen", cause is ErrnoException)
        assertEquals("the errno of the failed fsync was not kept: $seen", errno, (cause as ErrnoException).errno)
        val errnos = chain.filterIsInstance<ErrnoException>().map { it.errno }
        assertEquals("the cause chain holds another errno: $seen", listOf(errno), errnos)
    }

    private companion object {
        val BYTES = "sync".toByteArray()
    }
}

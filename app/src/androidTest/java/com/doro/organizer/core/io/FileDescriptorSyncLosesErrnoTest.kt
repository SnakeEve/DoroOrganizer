package com.doro.organizer.core.io

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.SetupProbeRecord
import java.io.SyncFailedException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for the platform behavior that `syncFile` (core/io/SyncFile.kt) exists for (DEC-107 (4)): on ART
 * `java.io.FileDescriptor.sync()` is native (the android.jar stub declares it `public native`; libcore's JNI function
 * throws `SyncFailedException` with a constant message when `fsync` fails), and that exception carries no
 * `ErrnoException` on its cause chain. The errno of a failed sync is therefore lost: `isStorageFull()` could not see an
 * `ENOSPC` or `EDQUOT`, and a key-state or staged-file sync that fails on a full storage would be classified
 * `Unexpected` (`AuthFailed` for a biometric slot creation) instead of `StorageFull`. The failures are those of
 * `SyncFileTest`, made without disk pressure: a closed descriptor (`EBADF`) and the write end of a pipe (`EINVAL`);
 * nothing is written to the storage.
 *
 * The probe pins the root cause, not the fix, so it passes with `syncFile` and without it. Should a platform update
 * make `FileDescriptor.sync()` keep the errno, these tests fail and DEC-107 (4) is revisited; `syncFile` stays correct
 * either way. Each run records the observed cause chain through [SetupProbeRecord]; messages name classes and errno
 * names only (DEC-70).
 */
@RunWith(AndroidJUnit4::class)
class FileDescriptorSyncLosesErrnoTest {
    @Test
    fun sync_ofAClosedDescriptor_throwsSyncFailedException_withNoErrnoOnItsCauseChain() {
        val (read, write) = Os.pipe()
        Os.close(read)
        Os.close(write) // Os.close also invalidates the FileDescriptor: the sync below reaches the kernel with -1

        val thrown = assertThrows(SyncFailedException::class.java) { write.sync() }

        assertNoErrno("closed descriptor", thrown)
    }

    @Test
    fun sync_ofAPipe_throwsSyncFailedException_withNoErrnoOnItsCauseChain() {
        val (read, write) = Os.pipe()
        try {
            val thrown = assertThrows(SyncFailedException::class.java) { write.sync() }

            assertNoErrno("pipe", thrown)
        } finally {
            Os.close(read)
            Os.close(write)
        }
    }

    private fun assertNoErrno(scene: String, thrown: SyncFailedException) {
        val chain = generateSequence<Throwable>(thrown) { it.cause }.toList()
        val seen = chain.joinToString(" <- ") { t ->
            t.javaClass.simpleName + ((t as? ErrnoException)?.let { "(${OsConstants.errnoName(it.errno)})" } ?: "")
        }
        SetupProbeRecord.record("FileDescriptorSyncLosesErrnoTest", "$scene: $seen")
        assertTrue(
            "FileDescriptor.sync() now keeps the errno of a failed sync ($scene: $seen): revisit DEC-107 (4)",
            chain.none { it is ErrnoException },
        )
    }
}

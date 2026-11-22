package com.doro.organizer.core.io

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.DbFiles
import java.io.File
import java.io.FileDescriptor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe for docs/db-lifecycle.md § Generation commit ("Verify at setup: `Os.open(dir, O_RDONLY)` plus `Os.fsync`
 * on a directory succeeds for `databases/` and `noBackupFilesDir` on the owner's phone", DEC-15). The directories are
 * the production roots of `DbFiles.forApp`, the ones `syncDirectory` is called on by `GenerationCommit`,
 * `DataResetMarker` and the biometric slot commits. `syncDirectory` itself swallows and only logs a failure (it is
 * best effort by design), so this test repeats its two calls with the same flags and fails with the errno name when
 * either throws. It also checks with `fstat` that the descriptor is a directory, so the probe cannot pass by opening
 * something else.
 */
@RunWith(AndroidJUnit4::class)
class DirectoryFsyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = DbFiles.forApp(context)
    private val created = mutableListOf<File>()

    @After
    fun removeCreatedDirectories() {
        created.forEach { if (it.list().isNullOrEmpty()) it.delete() }
    }

    @Test
    fun openReadOnlyAndFsync_succeedOnTheDatabasesDirectory() =
        assertDirectoryFsyncSucceeds(files.databasesDir, "databases/")

    @Test
    fun openReadOnlyAndFsync_succeedOnTheNoBackupDirectory() =
        assertDirectoryFsyncSucceeds(files.noBackupDir, "no_backup/")

    /** Failure messages carry the constant [label] and the errno, never a path. */
    private fun assertDirectoryFsyncSucceeds(dir: File, label: String) {
        if (!dir.isDirectory) {
            assertTrue("cannot create $label for the probe", dir.mkdirs())
            created += dir
        }
        val fd: FileDescriptor = try {
            Os.open(dir.path, OsConstants.O_RDONLY, 0)
        } catch (e: ErrnoException) {
            fail("Os.open(O_RDONLY) of $label failed: errno=${OsConstants.errnoName(e.errno)} (${e.errno})")
            return
        }
        try {
            val mode = Os.fstat(fd).st_mode
            assertTrue(
                "the descriptor of $label is not a directory: st_mode=0${Integer.toOctalString(mode)}",
                OsConstants.S_ISDIR(mode),
            )
            try {
                Os.fsync(fd)
            } catch (e: ErrnoException) {
                fail("Os.fsync of $label failed: errno=${OsConstants.errnoName(e.errno)} (${e.errno})")
            }
        } finally {
            Os.close(fd)
        }
    }
}

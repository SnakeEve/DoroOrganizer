package com.doro.organizer.security

import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.core.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.FaultyKekKeystore
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.testDispatchers
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probes for the two `androidx.core.util.AtomicFile` (core 1.19.1) behaviors that docs/db-lifecycle.md
 * § Generation commit ("Verify at setup … Still open: the sync in `finishWrite()` and that `readFully()` deletes a
 * stale `.new`"), docs/security.md § 3.5 ("`finishWrite()` (it syncs before renaming)"; "a stale
 * `db-state.json.new` is ignored and discarded when the base file exists") and docs/testing.md L-16 rely on:
 *
 * - `finishWrite()` fsyncs the `<name>.new` stream before it renames `.new` over the base file. A sync cannot be seen
 *   in the file afterwards, so the test watches the calls: a StrictMode thread policy with `detectDiskWrites` and a
 *   `penaltyListener` reports every disk write that goes through BlockGuard (`FileDescriptor.sync` reaches
 *   `BlockGuardOs.fsync`, `File.renameTo` reaches `UnixFileSystem.rename`), synchronously and in call order on a
 *   thread without a Looper. A control run first proves that the observer sees an fsync and a rename on this device,
 *   so a missing sync cannot be confused with a blind observer.
 * - `readFully()` deletes a stale `<name>.new` when the base file exists, and returns the base file's bytes; through
 *   `DefaultKeyVault.readState()` the same holds for `db-state.json.new`.
 *
 * Failure messages state the observed event kinds and file facts (exists, length), never file contents or paths.
 */
@RunWith(AndroidJUnit4::class)
class AtomicFileSyncAndStaleNewTest {
    @get:Rule
    val vaults = KeyVaultRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy {
        // noBackupFilesDir: the filesystem db-state.json lives on.
        val root = File(context.noBackupFilesDir, "test-atomic-file")
        check(root.isDirectory || root.mkdirs()) { "cannot create the AtomicFile test root" }
        Files.createTempDirectory(root.toPath(), "af").toFile()
    }

    @After
    fun removeFiles() {
        dir.deleteRecursively()
    }

    @Test
    fun finishWrite_fsyncsTheNewFile_beforeItRenamesItOverTheBase() {
        val control = diskWritesDuring {
            val scratch = File(dir, "control")
            FileOutputStream(scratch).use { it.write(BASE); it.fd.sync() }
            check(scratch.renameTo(File(dir, "control-renamed")))
        }
        assertTrue(
            "the StrictMode observer cannot see an fsync followed by a rename on this device; observed=$control",
            control.indexOf(FSYNC) in 0 until control.lastIndexOf(RENAME),
        )
        val base = File(dir, "state.json")
        val atomic = AtomicFile(base)
        val stream = atomic.startWrite().apply { write(BASE) }

        val events = diskWritesDuring { atomic.finishWrite(stream) }

        val renameAt = events.indexOf(RENAME)
        assertTrue("finishWrite renamed nothing; observed=$events", renameAt >= 0)
        assertTrue(
            "finishWrite did not fsync before its rename; observed=$events",
            events.indexOf(FSYNC) in 0 until renameAt,
        )
        assertArrayEquals("the base file does not hold the written bytes", BASE, base.readBytes())
        assertFalse("<name>.new still exists after finishWrite", File(base.path + ".new").exists())
    }

    @Test
    fun readFully_deletesAStaleNewFile_whenTheBaseFileExists() {
        val base = File(dir, "state.json")
        val atomic = AtomicFile(base)
        atomic.startWrite().also { it.write(BASE) }.let(atomic::finishWrite)
        val stale = File(base.path + ".new").apply { writeBytes(STALE) }

        val read = atomic.readFully()

        assertArrayEquals("readFully did not return the base file's bytes (length ${read.size})", BASE, read)
        assertFalse(
            "readFully left the stale <name>.new in place: exists=${stale.exists()}, length=${stale.length()}",
            stale.exists(),
        )
        assertArrayEquals("readFully changed the base file", BASE, base.readBytes())
    }

    @Test
    fun keyVaultReadState_deletesAStaleNewKeyStateFile_andReadsTheBase() = runTest(timeout = 60.seconds) {
        val vault = vaults.vault(FakeSettingsStore(), FakeAppTime(), testDispatchers(), FaultyKekKeystore())
        vault.commit(1, vault.prepare(KeyMode.PLAIN, null).slot, null)
        val stale = File(vaults.stateFile.path + ".new").apply { writeText("{\"formatVersion\":") }

        val state = vault.readState()

        assertEquals("readState over a valid base and a stale .new", 1L, state?.generation)
        assertFalse(
            "readState left the stale db-state.json.new in place: exists=${stale.exists()}, length=${stale.length()}",
            stale.exists(),
        )
    }

    /**
     * The kinds of the disk writes [block] makes, in order, as StrictMode reports them on a fresh thread without a
     * Looper (so each violation reaches the listener synchronously, never batched for an idle handler).
     */
    private fun diskWritesDuring(block: () -> Unit): List<String> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        var failure: Throwable? = null
        val thread = Thread {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites()
                    .penaltyListener(Executor { it.run() }) { violation -> events += kindOf(violation) }
                    .build(),
            )
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
            }
        }
        thread.start()
        thread.join(30_000)
        check(!thread.isAlive) { "the observed block did not finish" }
        failure?.let { throw it }
        return events.toList()
    }

    /** A constant kind, or `other:` with the first frame outside StrictMode and BlockGuard (code names only). */
    private fun kindOf(violation: Violation): String {
        val frames = violation.stackTrace
        return when {
            frames.any(::isSync) -> FSYNC
            frames.any(::isRename) -> RENAME
            frames.any { it.methodName == "open" } -> "open"
            frames.any { it.methodName in WRITE_CALLS } -> "write"
            else -> "other:" + frames.firstOrNull { frame -> OBSERVER_PACKAGES.none { frame.className.startsWith(it) } }
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        }
    }

    private fun isSync(frame: StackTraceElement) =
        (frame.className == "java.io.FileDescriptor" && frame.methodName == "sync") || frame.methodName in FSYNC_CALLS

    private fun isRename(frame: StackTraceElement) =
        (frame.className == "java.io.File" && frame.methodName == "renameTo") || frame.methodName == "rename"

    private companion object {
        const val FSYNC = "fsync"
        const val RENAME = "rename"
        val FSYNC_CALLS = setOf("fsync", "fdatasync")
        val WRITE_CALLS = setOf("write", "pwrite", "writeBytes")
        val OBSERVER_PACKAGES =
            listOf("android.os.StrictMode", "android.os.strictmode.", "dalvik.system.BlockGuard", "libcore.io.")
        val BASE = "{\"base\":1}".toByteArray()
        val STALE = "{\"stale\":".toByteArray()
    }
}

package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDatabases.Companion.GENERATION
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe (SPEC T22) for db-lifecycle.md § Open sequence, "Verify at setup" (1): a raw `OPEN_READWRITE` connection
 * reports `PRAGMA journal_mode` = `delete`, and the `-wal` of a file left by an unclean exit is gone after it closes.
 * The same section states why: sqlcipher-android applies its default journal mode DELETE to a connection opened
 * without the WAL flag, which checkpoints the WAL an unclean exit left.
 *
 * The connections are production's: `RawConnections.open` and the raw verification `verifyLive` that uses it. The
 * unclean exit is the on-disk state a killed process leaves: the live Room instance (WAL) commits a row and its files,
 * `-wal` and `-shm` included, are copied to `doro-1.db` while it is still open, so the copy is a database whose last
 * connection never closed and whose row is only in the `-wal`. Nothing is deleted. A failure states the observed value
 * (DEC-70: no user data) and is reported; the expectation is never bent to pass.
 */
@RunWith(AndroidJUnit4::class)
class RawConnectionJournalModeTest {
    @get:Rule
    val databases = TestDatabases()

    private val deviceKey = TestKey.Raw(ByteArray(32) { (it * 5 + 1).toByte() })

    @Test
    fun plain_rawConnection_onUncleanWalFile_reportsDelete_andLeavesNoWal() =
        assertRawConnectionReportsDelete(TestKey.Plain)

    @Test
    fun device_rawConnection_onUncleanWalFile_reportsDelete_andLeavesNoWal() =
        assertRawConnectionReportsDelete(deviceKey)

    @Test
    fun plain_verifyLive_afterUncleanExit_checkpointsTheWal_andLeavesNone() = assertVerifyCheckpoints(TestKey.Plain)

    @Test
    fun device_verifyLive_afterUncleanExit_checkpointsTheWal_andLeavesNone() = assertVerifyCheckpoints(deviceKey)

    private fun assertRawConnectionReportsDelete(key: TestKey) {
        val file = uncleanLiveFile(key)
        val bytes = key.bytes()

        val mode = try {
            RawConnections.open(file, bytes).use { it.column0("PRAGMA journal_mode") }
        } finally {
            bytes.fill(0)
        }

        assertEquals("journal_mode of a raw OPEN_READWRITE connection on a WAL file", listOf("delete"), mode)
        assertNoWal(file)
        if (key == TestKey.Plain) {
            // Header bytes 18 and 19: 2 = WAL, 1 = rollback journal. The switch is in the file, not only reported.
            assertEquals("header bytes 18-19 after the raw connection closed", listOf(1, 1), formatVersions(file))
        }
    }

    private fun assertVerifyCheckpoints(key: TestKey) {
        val file = uncleanLiveFile(key)
        val bytes = key.bytes()

        try {
            verifyLive(file, bytes)
        } catch (e: CheckFailed) {
            fail("raw verification of the unclean file failed: ${e.failure}, keyRejected=${e.keyRejected}")
        } finally {
            bytes.fill(0)
        }

        assertNoWal(file)
        // With no -wal left, any connection reads the main file alone: the committed row was checkpointed into it.
        val names = databases.readOnly(file, key).column0("SELECT name FROM persons")
        assertEquals("rows of the main file after the raw verification", listOf(SENTINEL), names)
        if (key == TestKey.Plain) assertTrue("row not in the main file", contains(file, SENTINEL))
    }

    /**
     * `doro-1.db` as an unclean exit leaves it: a closed live file (schema checkpointed, WAL mode in the header),
     * reopened by the live Room instance, one row committed into its `-wal`, then the main file and its sidecars copied
     * while that instance is still open. The source instance is closed afterwards; it is not the file under test.
     */
    private fun uncleanLiveFile(key: TestKey): File {
        val source = databases.file("source.db")
        databases.room(source, key).run {
            openHelper.writableDatabase
            close()
        }
        val live = databases.room(source, key)
        val sql = live.openHelper.writableDatabase
        assumeTrue("Room uses WAL on this device (DEC-89)", sql.stringsOf("PRAGMA journal_mode").single() == "wal")
        TestRows(sql).person(SENTINEL, 1)
        val target = databases.files.live(GENERATION)
        for (suffix in listOf("", "-wal", "-shm")) {
            val from = File(source.path + suffix)
            if (from.exists()) from.copyTo(File(target.path + suffix))
        }
        live.close()

        // Preconditions: the copy really is an unclean WAL file whose row exists only in its -wal.
        assertTrue("-wal of the unclean copy is empty", File(target.path + "-wal").length() > 0)
        if (key == TestKey.Plain) {
            assertEquals("header bytes 18-19 of the unclean copy", listOf(2, 2), formatVersions(target))
            assertFalse("row already in the main file", contains(target, SENTINEL))
            assertTrue("row not in the -wal", contains(File(target.path + "-wal"), SENTINEL))
        }
        return target
    }

    private fun assertNoWal(file: File) {
        val wal = File(file.path + "-wal")
        assertFalse("-wal left after the raw connection closed, length ${wal.length()}", wal.exists())
    }

    /** Header bytes 18 and 19 of a plaintext file: the file format write and read versions. */
    private fun formatVersions(file: File): List<Int> = RandomAccessFile(file, "r").use { f ->
        f.seek(18)
        listOf(f.read(), f.read())
    }

    private fun contains(file: File, text: String): Boolean {
        val haystack = file.readBytes()
        val needle = text.toByteArray(Charsets.US_ASCII)
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }
    }
}

package com.doro.organizer.data

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.openDescriptors
import com.doro.organizer.testing.readOnThreads
import com.doro.organizer.testing.stringsOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probes (SPEC T22) for db-lifecycle.md § Room builder, "Verify at setup": "`JournalMode.TRUNCATE` makes
 * sqlcipher-android open a single non-WAL connection" and "which `PRAGMA journal_mode` a temporary instance reports,
 * and which sidecar files exist after `close()`". The instance is production's `buildRoom(…, temporary = true)`.
 *
 * The exact mode pinned is `delete`: in open-helper-factory mode Room 2.8.5 only calls
 * `setWriteAheadLoggingEnabled(false)` for `TRUNCATE` (its `PRAGMA journal_mode = TRUNCATE` belongs to the driver
 * path), and sqlcipher-android then applies its default journal mode DELETE, as on a raw connection; DELETE removes
 * the `-journal` after every transaction, so no sidecar is left after `close()`. If the phone reports `truncate`, a
 * zero-length `-journal` stays, which the design tolerates (db-lifecycle.md § Target verification): the failure then
 * changes what the docs record, not the design. A failure states the observed mode, sidecars or count and is reported.
 */
@RunWith(AndroidJUnit4::class)
class TemporaryInstanceJournalTest {
    @get:Rule
    val databases = TestDatabases()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val deviceKey = TestKey.Raw(ByteArray(32) { (it * 11 + 2).toByte() })

    @Test
    fun temporary_onNewFile_reportsDelete_andLeavesNoSidecar() {
        val file = databases.file("staged.db")

        assertDeleteModeAndNoSidecar(file, TestKey.Plain)
    }

    @Test
    fun temporary_onFileTheLiveInstanceLeftInWal_reportsDelete_andLeavesNoSidecar() {
        val file = databases.file("staged.db")
        databases.room(file, deviceKey).run {
            val mode = openHelper.writableDatabase.stringsOf("PRAGMA journal_mode")
            close()
            assumeTrue("the live instance uses WAL on this device (DEC-89)", mode == listOf("wal"))
        }

        assertDeleteModeAndNoSidecar(file, deviceKey)
    }

    @Test
    fun temporary_opensOneConnection_whereTheLiveInstanceOpensSeveral() {
        // The control needs Room's AUTOMATIC mode to choose WAL, which it does not on a low-RAM device (DEC-89).
        assumeFalse(checkNotNull(context.getSystemService<ActivityManager>()).isLowRamDevice)
        val liveFile = databases.file("live.db")
        val stagedFile = databases.file("staged.db")

        val live = connectionsAfterReads(databases.room(liveFile, TestKey.Plain), liveFile)
        val staged = connectionsAfterReads(databases.room(stagedFile, TestKey.Plain, temporary = true), stagedFile)

        // Control: the same reads make a WAL pool open reader connections, and the descriptor count sees them.
        assertTrue("connections of the live instance after concurrent reads: $live", live > 1)
        assertEquals("connections of the temporary instance after concurrent reads", 1, staged)
    }

    private fun assertDeleteModeAndNoSidecar(file: File, key: TestKey) {
        val staged = databases.room(file, key, temporary = true)
        val sql = staged.openHelper.writableDatabase
        val mode = sql.stringsOf("PRAGMA journal_mode")
        TestRows(sql).person(SENTINEL, 1)
        staged.close()

        assertEquals("journal_mode of the temporary instance", listOf("delete"), mode)
        val sidecars = listOf("-journal", "-wal", "-shm").map { File(file.path + it) }.filter(File::exists)
        assertEquals(
            "sidecars after the temporary instance closed",
            emptyList<String>(),
            sidecars.map { "${it.name.removePrefix(file.name)} (${it.length()} bytes)" },
        )
    }

    /** Opens [room], writes one row, reads on several threads, and counts the connections open on [file]. */
    private fun connectionsAfterReads(room: AppDatabase, file: File): Int {
        val sql = room.openHelper.writableDatabase
        TestRows(sql).person(SENTINEL, 1)
        sql.readOnThreads()
        return openDescriptors(file).also { room.close() } // one descriptor of the main file per open connection
    }
}

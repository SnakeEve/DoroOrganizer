package com.doro.organizer.data

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.SetupProbeRecord
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.openDescriptors
import com.doro.organizer.testing.readOnThreads
import com.doro.organizer.testing.stringsOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probes (SPEC T22) for db-lifecycle.md § Room builder, "Verify at setup": "`JournalMode.TRUNCATE` makes
 * sqlcipher-android open a single non-WAL connection" and "which `PRAGMA journal_mode` a temporary instance reports,
 * and which sidecar files exist after `close()` (a zero-length `-journal` is expected and tolerated; a non-empty one or
 * a `-wal` would be a defect)". The instance is production's `buildRoom(…, temporary = true)`.
 *
 * The docs leave the exact mode open, so the tests assert what they require (the mode is not `wal`, no `-wal`, any
 * `-journal` has length 0) and write the observed mode and sidecars through [SetupProbeRecord], whatever they are: a
 * `truncate` with a zero-length `-journal` and a `delete` with none both pass and are both recorded. A failure states
 * the observed mode, sidecars or count and is reported.
 */
@RunWith(AndroidJUnit4::class)
class TemporaryInstanceJournalTest {
    @get:Rule
    val databases = TestDatabases()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val deviceKey = TestKey.Raw(ByteArray(32) { (it * 11 + 2).toByte() })

    @Test
    fun temporary_onNewFile_reportsANonWalMode_andLeavesNoWalAndNoNonEmptyJournal() {
        val file = databases.file("staged.db")

        assertNonWalModeAndTolerableSidecars(file, TestKey.Plain, "temporary instance on a new file")
    }

    @Test
    fun temporary_onFileTheLiveInstanceLeftInWal_reportsANonWalMode_andLeavesNoWalAndNoNonEmptyJournal() {
        val file = databases.file("staged.db")
        databases.room(file, deviceKey).run {
            val mode = openHelper.writableDatabase.stringsOf("PRAGMA journal_mode")
            close()
            assumeTrue("the live instance uses WAL on this device (DEC-89)", mode == listOf("wal"))
        }

        assertNonWalModeAndTolerableSidecars(file, deviceKey, "temporary instance on a file the live instance left in WAL")
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

    private fun assertNonWalModeAndTolerableSidecars(file: File, key: TestKey, probe: String) {
        val staged = databases.room(file, key, temporary = true)
        val sql = staged.openHelper.writableDatabase
        val mode = sql.stringsOf("PRAGMA journal_mode")
        TestRows(sql).person(SENTINEL, 1)
        staged.close()
        val sidecars = listOf("-journal", "-wal", "-shm").map { File(file.path + it) }.filter(File::exists)
        val observed = "journal_mode=$mode, sidecars after close()=" +
            sidecars.map { "${it.name.removePrefix(file.name)} (${it.length()} bytes)" }
        SetupProbeRecord.record(probe, observed)

        assertTrue("journal_mode of the temporary instance is not one non-wal mode; $observed", mode.size == 1 && mode.single() != "wal")
        assertFalse("a -wal exists after the temporary instance closed; $observed", File(file.path + "-wal").exists())
        val journal = File(file.path + "-journal")
        assertTrue("a non-empty -journal exists after the temporary instance closed; $observed", !journal.exists() || journal.length() == 0L)
    }

    /** Opens [room], writes one row, reads on several threads, and counts the connections open on [file]. */
    private fun connectionsAfterReads(room: AppDatabase, file: File): Int {
        val sql = room.openHelper.writableDatabase
        TestRows(sql).person(SENTINEL, 1)
        sql.readOnThreads()
        return openDescriptors(file).also { room.close() } // one descriptor of the main file per open connection
    }
}

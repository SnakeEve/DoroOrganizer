package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.column0
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe (SPEC T22) for docs/db-lifecycle.md § Maintenance connection, the bullet on the connection's journal
 * mode: "The attached export target inherits that journal mode: DELETE leaves no `-journal` after the connection
 * closes. Verify at setup: `PRAGMA target.journal_mode` reports `delete` on the attached target".
 *
 * [attachedTarget_reportsJournalModeDelete] reads the pragma back on a connection opened as
 * `MaintenanceConnection.open` opens it (`RawConnections.open` on the live file with its key) with the target attached
 * by the very statement of `MaintenanceConnection.exportTo` (`ATTACH DATABASE ? AS target KEY ?`), both right after
 * the ATTACH and after `sqlcipher_export('target')` has written the target. [productionExport_leavesNoTargetJournal]
 * runs the production `exportTo` itself and shows the effect DELETE has: no `-journal` (and no `-wal`) next to the
 * target, neither after `exportTo` returns nor after the connection closes. TRUNCATE would leave a zero-length
 * `-journal`, PERSIST a non-empty one. Each pair of live key and target key is one production export: a backup with or
 * without a password, and the key changes. A failure states the observed mode or sidecar lengths, never user data
 * (DEC-70). If the phone contradicts the docs, the failure is the reported outcome; the expectation is not adjusted.
 */
@RunWith(AndroidJUnit4::class)
class ExportTargetJournalModeTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun attachedTarget_reportsJournalModeDelete() {
        for ((liveName, liveKey) in LIVE_KEYS) {
            for ((targetName, targetKey) in TARGET_KEYS) {
                val label = "live $liveName, target $targetName"
                val live = newLive(liveKey)
                val target = databases.file("attached-$liveName-$targetName.db")
                val sourceBytes = liveKey.bytes()
                val targetBytes = targetKey.bytes()
                try {
                    RawConnections.open(live, sourceBytes).use { db ->
                        check(target.createNewFile()) { "target exists" }
                        db.execSQL("ATTACH DATABASE ? AS target KEY ?", arrayOf<Any>(target.path, targetBytes))
                        try {
                            assertEquals(
                                "PRAGMA target.journal_mode right after ATTACH ($label); main reports " +
                                    db.column0("PRAGMA main.journal_mode"),
                                listOf("delete"),
                                db.column0("PRAGMA target.journal_mode"),
                            )
                            db.query("SELECT sqlcipher_export('target')").use { check(it.moveToFirst()) { "export" } }
                            assertEquals(
                                "PRAGMA target.journal_mode after sqlcipher_export ($label); main reports " +
                                    db.column0("PRAGMA main.journal_mode"),
                                listOf("delete"),
                                db.column0("PRAGMA target.journal_mode"),
                            )
                        } finally {
                            runCatching { db.execSQL("DETACH DATABASE target") }
                        }
                    }
                } finally {
                    sourceBytes.fill(0)
                    targetBytes.fill(0)
                }
            }
        }
    }

    @Test
    fun productionExport_leavesNoTargetJournal() {
        for ((liveName, liveKey) in LIVE_KEYS) {
            for ((targetName, targetKey) in TARGET_KEYS) {
                val label = "live $liveName, target $targetName"
                val live = newLive(liveKey)
                val target = databases.file("export-$liveName-$targetName.db")
                val sourceBytes = liveKey.bytes()
                val targetBytes = targetKey.bytes()
                try {
                    MaintenanceConnection.open(live, sourceBytes).use { connection ->
                        connection.exportTo(target, targetBytes)
                        assertEquals("target sidecars after exportTo returned ($label)", NO_SIDECARS, sidecars(target))
                    }
                    assertEquals("target sidecars after the connection closed ($label)", NO_SIDECARS, sidecars(target))
                } finally {
                    sourceBytes.fill(0)
                    targetBytes.fill(0)
                }
            }
        }
    }

    /** A live generation of [key]'s mode built by the production Room builder, as the export source. */
    private fun newLive(key: TestKey): File = databases.createLive(++generation, key)

    /** The `-journal` and `-wal` lengths next to [file]; null where the sidecar does not exist. */
    private fun sidecars(file: File): Map<String, Long?> =
        listOf("-journal", "-wal").associateWith { suffix -> File(file.path + suffix).takeIf { it.exists() }?.length() }

    private var generation = 0L

    private companion object {
        val NO_SIDECARS = mapOf<String, Long?>("-journal" to null, "-wal" to null)

        /** The live database of an encrypted install (a raw DEK) and of a Plain install. */
        val LIVE_KEYS = listOf(
            "raw" to TestKey.Raw(ByteArray(32) { (it * 3 + 7).toByte() }),
            "plain" to TestKey.Plain,
        )

        /** A backup with a password, a backup without one or the switch to Plain, and the switch to a new DEK. */
        val TARGET_KEYS = listOf(
            "passphrase" to TestKey.Passphrase("backup password 27"),
            "plain" to TestKey.Plain,
            "raw" to TestKey.Raw(ByteArray(32) { (it * 13 + 1).toByte() }),
        )
    }
}

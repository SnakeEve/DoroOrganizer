package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.maintenance.createEmptyDatabase
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDatabases.Companion.GENERATION
import com.doro.organizer.testing.TestKey
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probes (SPEC T22) for the `android_metadata` table:
 * - db-lifecycle.md § Open sequence, "Verify at setup" (5), second half: with `NO_LOCALIZED_COLLATORS` a raw
 *   connection creates no `android_metadata` table. Checked on production's raw verification `verifyLive` and on
 *   `RawConnections.open`, over a file built without the table, in plain mode and with a key.
 * - db-lifecycle.md § Room builder, "Verify at setup": whether `createEmptyDatabase` leaves an `android_metadata`
 *   table. It is ignored either way (every schema comparison excludes it), so this pins the answer sqlcipher-android
 *   4.20.0 gives: its connection skips the localized-collator setup that writes the table whenever the SQLCipher codec
 *   is present, which it always is in this build. A failure here changes what the docs record, not the design.
 *
 * Every check reads through a read-only connection, which never writes the table itself. A failure states the
 * observed count and is reported.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMetadataTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun plain_rawVerification_writesNoAndroidMetadata() = assertRawConnectionsWriteNoMetadata(TestKey.Plain)

    @Test
    fun device_rawVerification_writesNoAndroidMetadata() =
        assertRawConnectionsWriteNoMetadata(TestKey.Raw(ByteArray(32) { (it * 7 + 3).toByte() }))

    @Test
    fun createEmptyDatabase_leavesNoAndroidMetadata() {
        val file = databases.file("empty.db")

        createEmptyDatabase(InstrumentationRegistry.getInstrumentation().targetContext, file)

        val schema = databases.readOnly(file, TestKey.Plain)
        assertEquals("room_master_table objects (onCreate ran)", 1L, schema.objectsNamed("room_master_table"))
        assertEquals("android_metadata objects after createEmptyDatabase", 0L, schema.objectsNamed(METADATA))
    }

    private fun assertRawConnectionsWriteNoMetadata(key: TestKey) {
        val file = databases.files.live(GENERATION)
        databases.createRaw(file, key).run {
            execSQL("CREATE TABLE probe (x INTEGER)")
            execSQL("PRAGMA user_version = ${AppDatabase.VERSION}")
            execSQL("PRAGMA application_id = ${AppDatabase.APPLICATION_ID}")
            close()
        }
        val before = databases.readOnly(file, key).use { it.objectsNamed(METADATA) }
        assertEquals("android_metadata objects in the fixture", 0L, before)

        val bytes = key.bytes()
        try {
            verifyLive(file, bytes)
            RawConnections.open(file, bytes).close()
        } catch (e: CheckFailed) {
            fail("raw verification of the fixture failed: ${e.failure}, keyRejected=${e.keyRejected}")
        } finally {
            bytes.fill(0)
        }

        val schema = databases.readOnly(file, key)
        assertEquals("objects named probe: the check reads the file's schema", 1L, schema.objectsNamed("probe"))
        assertEquals("android_metadata objects after the raw connections", 0L, schema.objectsNamed(METADATA))
    }

    private fun SQLiteDatabase.objectsNamed(name: String): Long =
        query("SELECT count(*) FROM sqlite_master WHERE name = ?", arrayOf<Any>(name)).use { c ->
            check(c.moveToFirst()) { "no row" }
            c.getLong(0)
        }

    private companion object {
        const val METADATA = "android_metadata"
    }
}

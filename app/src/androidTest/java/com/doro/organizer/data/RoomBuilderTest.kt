package com.doro.organizer.data

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.stringsOf
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `buildRoom` (db-lifecycle.md § Room builder): journal modes, sidecar files after close and the on-disk format. */
@RunWith(AndroidJUnit4::class)
class RoomBuilderTest {
    @get:Rule
    val databases = TestDatabases()

    /** The database whose invariants `@After` asserts; each test assigns the last one it opens. */
    private lateinit var db: AppDatabase

    private val rawKey = TestKey.Raw(ByteArray(32) { (it * 3).toByte() })

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun live_usesWriteAheadLog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Room's AUTOMATIC mode falls back to a rollback journal on low-RAM devices (DEC-89).
        assumeFalse(checkNotNull(context.getSystemService<ActivityManager>()).isLowRamDevice)
        db = databases.create()

        assertEquals(listOf("wal"), journalMode(db))
    }

    @Test
    fun live_close_removesWalAndShm() {
        val file = databases.file("live.db")

        writePersonAndClose(databases.room(file, TestKey.Plain))

        // DEC-89: maintenance copies only the main file, so close must have checkpointed the WAL into it.
        assertFalse("-wal left", sidecar(file, "-wal").exists())
        assertFalse("-shm left", sidecar(file, "-shm").exists())
        db = databases.room(file, TestKey.Plain)
    }

    @Test
    fun temporary_avoidsWriteAheadLog() {
        val file = databases.file("staged.db")
        val staged = databases.room(file, TestKey.Plain, temporary = true)

        assertNotEquals(listOf("wal"), journalMode(staged))
        writePersonAndClose(staged)
        assertNoWalAndNoFilledJournal(file)
        db = databases.room(file, TestKey.Plain, temporary = true)
    }

    @Test
    fun temporary_onFileWrittenByLiveInstance_avoidsWriteAheadLog() {
        val file = databases.file("staged.db")
        writePersonAndClose(databases.room(file, rawKey)) // the live instance leaves WAL mode in the header
        val staged = databases.room(file, rawKey, temporary = true)

        assertNotEquals(listOf("wal"), journalMode(staged))
        writePersonAndClose(staged)
        assertNoWalAndNoFilledJournal(file)
        db = databases.room(file, rawKey, temporary = true)
    }

    @Test
    fun plainKey_writesPlainSqliteFileWithReadableText() {
        val file = databases.file("plain.db")

        writePersonAndClose(databases.room(file, TestKey.Plain))

        assertEquals(SQLITE_MAGIC, header(file))
        assertTrue("sentinel not found in a plain file", onDiskContainsSentinel(file)) // the search's positive control
        db = databases.room(file, TestKey.Plain)
    }

    @Test
    fun rawKey_writesEncryptedFileThatReopensWithItsKey() {
        val file = databases.file("keyed.db")

        writePersonAndClose(databases.room(file, rawKey))

        assertNotEquals(SQLITE_MAGIC, header(file))
        assertFalse("sentinel readable on disk", onDiskContainsSentinel(file)) // V3: no user text in plaintext
        db = databases.room(file, rawKey)
        assertEquals(listOf(SENTINEL), db.openHelper.writableDatabase.stringsOf("SELECT name FROM persons"))
    }

    private fun journalMode(room: AppDatabase): List<String?> =
        room.openHelper.writableDatabase.stringsOf("PRAGMA journal_mode")

    /** Opens [room], writes one person named [SENTINEL] and closes [room] again. */
    private fun writePersonAndClose(room: AppDatabase) {
        room.openHelper.writableDatabase.execSQL(
            "INSERT INTO persons (name, created_at, updated_at) VALUES (?, ?, ?)",
            arrayOf<Any>(SENTINEL, 1L, 1L),
        )
        room.close()
    }

    /** db-lifecycle.md § Room builder: a `-wal` or a non-empty `-journal` after a temporary close is a defect. */
    private fun assertNoWalAndNoFilledJournal(file: File) {
        assertFalse("-wal left", sidecar(file, "-wal").exists())
        assertEquals("-journal length", 0L, sidecar(file, "-journal").length()) // also 0 when the file is absent
    }

    /** Whether the main file or a `-wal` or `-journal` next to it holds [SENTINEL] as plain bytes. */
    private fun onDiskContainsSentinel(file: File): Boolean {
        val needle = SENTINEL.toByteArray(Charsets.US_ASCII)
        return listOf(file, sidecar(file, "-wal"), sidecar(file, "-journal"))
            .filter(File::exists)
            .any { it.readBytes().containsSequence(needle) }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun sidecar(file: File, suffix: String) = File(file.path + suffix)

    private fun header(file: File): String = String(file.readBytes().copyOf(16), Charsets.ISO_8859_1)

    private companion object {
        const val SQLITE_MAGIC = "SQLite format 3\u0000"
    }
}

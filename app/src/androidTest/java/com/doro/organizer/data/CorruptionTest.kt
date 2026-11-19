package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.testing.HolderEvents
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDatabases.Companion.GENERATION
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.corruptPage
import com.doro.organizer.testing.flipByteInPage
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.pageCount
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-12, holder part (DEC-85): a damaged file is Corrupt at open, because the open path reads every page;
 * corruption met at runtime is reported to the gate, and the file is always kept. The gate and screen halves come
 * with SessionGate. The outcomes for a tampered encrypted page follow db-lifecycle.md § Failure classification; the
 * exception class SQLCipher raises is a device check (§ Open sequence, Verify at setup (4)).
 */
@RunWith(AndroidJUnit4::class)
class CorruptionTest {
    @get:Rule
    val databases = TestDatabases()

    private val deviceKey = TestKey.Raw(ByteArray(32) { (it * 11).toByte() })

    @Test
    fun plain_page2Overwritten_isCorruptAtOpen() =
        assertCorruptAtOpen(TestKey.Plain, keyRejected = false) { corruptPage(it, 2) }

    @Test
    fun device_page3Flipped_isCorruptAtOpen() =
        assertCorruptAtOpen(deviceKey, keyRejected = false) { flipByteInPage(it, 3) }

    /** SQLCipher returns a page whose HMAC fails zeroed: `SQLITE_NOTADB` for page 1, at the key check (step 4.1). */
    @Test
    fun device_page1Tampered_isCorruptAtOpen_keyRejected() =
        assertCorruptAtOpen(deviceKey, keyRejected = true) { flipByteInPage(it, 1) }

    /** Any page after the first: `SQLITE_CORRUPT`. */
    @Test
    fun device_page2Tampered_isCorruptAtOpen() =
        assertCorruptAtOpen(deviceKey, keyRejected = false) { flipByteInPage(it, 2) }

    @Test
    fun plain_runtimeCorruption_inWithDb_isReported() = assertRuntimeCorruptionInWithDb(TestKey.Plain)

    @Test
    fun device_runtimeCorruption_inWithDb_isReported() = assertRuntimeCorruptionInWithDb(deviceKey)

    @Test
    fun runtimeCorruption_inAFlow_isReported_andTheFlowCompletes() = runBlocking(Dispatchers.Default) {
        val events = HolderEvents()
        val holder = databases.holder(TestKey.Plain, events)
        damageEveryPageButTheFirst(holder, TestKey.Plain)

        val collector = launch { holder.observe { it.personDao().observeOptions(null) }.collect {} }
        withTimeout(10.seconds) { events.corruptions.first { it > 0 } }

        assertTrue("the outer flow keeps waiting for the next Open", collector.isActive)
        assertTrue("the holder never closes Room on its own", holder.state.value is HolderState.Open)
        collector.cancel()
    }

    private fun assertRuntimeCorruptionInWithDb(key: TestKey) = runBlocking(Dispatchers.IO) {
        val events = HolderEvents()
        val holder = databases.holder(key, events)
        damageEveryPageButTheFirst(holder, key)

        val result = holder.withDb { it.openHelper.writableDatabase.longOf("SELECT count(*) FROM notes") }

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertEquals(1, events.corruptions.value)
        assertTrue("the holder never closes Room on its own", holder.state.value is HolderState.Open)
        assertTrue(databases.files.live(GENERATION).exists())
    }

    /**
     * The technique of testing.md § 5 "Runtime corruption": with Room open and the WAL empty, random bytes over every
     * page but the first in plain mode, one flipped byte per page with a key; a reader connection that has not read a
     * data page yet meets the damage on its first query.
     */
    private fun damageEveryPageButTheFirst(holder: DatabaseHolder, key: TestKey) {
        val file = databases.files.live(GENERATION)
        holder.openDatabase().openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        assertEquals("the WAL is empty", 0L, File(file.path + "-wal").length())
        (2..pageCount(file)).forEach { page ->
            if (key == TestKey.Plain) corruptPage(file, page) else flipByteInPage(file, page)
        }
    }

    private fun assertCorruptAtOpen(key: TestKey, keyRejected: Boolean, damage: (File) -> Unit) = runBlocking {
        val file = databases.createLive(GENERATION, key)
        damage(file)
        val length = file.length()
        val holder = databases.newHolder()
        val bytes = key.bytes()

        val result = holder.open(GENERATION, bytes)

        assertEquals(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected), result)
        assertSame(HolderState.Closed, holder.state.value)
        assertTrue("the key is zeroed", bytes.all { it == 0.toByte() })
        assertEquals("the file is kept until the user acts", length, file.length())
    }
}

package com.doro.organizer.data

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.HolderEvents
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDatabases.Companion.GENERATION
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.openDatabase
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-13, holder part: one test per row of the Opening and Runtime tables of db-lifecycle.md § Failure
 * classification (the DbMaintenance and Documents rows come with DbMaintenance). File conditions are produced on
 * real files; the exception classes that no file can produce on demand are thrown into the same code paths. Covered
 * by class only: a plain `SQLiteException` and a late `SQLiteNotADatabaseException` at raw verification
 * ([rawCheckFailure]), and the corruption classes of Room's open ([openFailureKind]). Key-bearing cases use a real key,
 * so the zeroing assertion can fail.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class DatabaseHolderClassifyTest {
    @get:Rule
    val databases = TestDatabases()

    private val deviceKey = TestKey.Raw(ByteArray(32) { (it * 7).toByte() })

    // Context Opening, on files.

    @Test
    fun classifyOpening_missingLiveFile_isCorrupt_andNothingCreatesIt() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt), key = deviceKey) { file ->
            assertFalse(file.exists())
        }

    @Test
    fun classifyOpening_emptyLiveFile_isCorrupt() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt), key = deviceKey) {
            it.writeBytes(ByteArray(0))
        }

    @Test
    fun classifyOpening_plaintextFileWithAKey_isKeyRejected() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected = true), key = deviceKey) {
            databases.createLive(GENERATION, TestKey.Plain)
        }

    @Test
    fun classifyOpening_encryptedFileWithTheEmptyKey_isKeyRejected() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected = true)) {
            databases.createLive(GENERATION, deviceKey)
        }

    @Test
    fun classifyOpening_headerShorterThan16Bytes_isKeyRejected() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected = true)) {
            it.writeBytes("SQLite".toByteArray())
        }

    @Test
    fun classifyOpening_wrongKey_isKeyRejected() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected = true), key = deviceKey) {
            databases.createLive(GENERATION, TestKey.Raw(ByteArray(32) { 1 }))
        }

    @Test
    fun classifyOpening_notADatabase_isKeyRejected() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt, keyRejected = true), key = deviceKey) {
            it.writeBytes(Random(42).nextBytes(8192))
        }

    @Test
    fun classifyOpening_unreadableHeader_isTransient() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Transient), key = deviceKey) { file ->
            databases.createLive(GENERATION, deviceKey)
            check(file.setReadable(false, false)) { "cannot revoke read access" }
        }

    @Test
    fun classifyOpening_noSchema_isCorrupt() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt), key = deviceKey) {
            databases.createRaw(it, deviceKey).execSQL("PRAGMA user_version = 1")
        }

    @Test
    fun classifyOpening_newerUserVersion_isTransient() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Transient), key = deviceKey) {
            databases.createLive(GENERATION, deviceKey)
            databases.raw(it, deviceKey).execSQL("PRAGMA user_version = ${AppDatabase.VERSION + 1}")
        }

    @Test
    fun classifyOpening_userVersionZero_isCorrupt() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt), key = deviceKey) {
            databases.createLive(GENERATION, deviceKey)
            databases.raw(it, deviceKey).execSQL("PRAGMA user_version = 0")
        }

    @Test
    fun classifyOpening_foreignApplicationId_isCorrupt() =
        assertOpenFails(OpenResult.Failed(OpenFailureKind.Corrupt), key = deviceKey) {
            databases.createLive(GENERATION, deviceKey)
            databases.raw(it, deviceKey).execSQL("PRAGMA application_id = 42")
        }

    @Test
    fun classifyOpening_roomIdentityMismatch_isRethrown_andLeavesTheHolderClosed() = runBlocking {
        val file = databases.createLive(GENERATION, deviceKey)
        databases.raw(file, deviceKey).execSQL("UPDATE room_master_table SET identity_hash = 'other'")
        val holder = databases.newHolder()
        val key = deviceKey.bytes()

        val thrown = runCatching { holder.open(GENERATION, key) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalStateException)
        assertSame(HolderState.Closed, holder.state.value)
        assertTrue("nothing deletes the file (DEC-85)", file.exists())
        assertTrue("the key is zeroed", key.all { it == 0.toByte() })
    }

    // Context Opening, by class.

    @Test
    fun classifyOpening_corruptionClasses_areCorrupt() {
        listOf(SQLiteNotADatabaseException(), SQLiteDatabaseCorruptException())
            .forEach { assertEquals("$it", OpenFailureKind.Corrupt, openFailureKind(it)) }
    }

    @Test
    fun classifyOpening_rawVerification_plainSqliteError_isCorrupt() {
        listOf(true, false).forEach { keyCheck ->
            val failure = rawCheckFailure(SQLiteException(), keyCheck) as CheckFailed
            assertEquals(DbFailure.Corrupt, failure.failure)
            assertFalse(failure.keyRejected)
        }
    }

    @Test
    fun classifyOpening_rawVerification_notADatabaseAtTheKeyCheck_isKeyRejected() {
        val failure = rawCheckFailure(SQLiteNotADatabaseException(), keyCheck = true) as CheckFailed

        assertEquals(DbFailure.Corrupt, failure.failure)
        assertTrue(failure.keyRejected)
    }

    @Test
    fun classifyOpening_rawVerification_notADatabaseAfterTheKeyCheck_isCorruptWithoutKeyRejected() {
        val late = SQLiteNotADatabaseException()

        val mapped = rawCheckFailure(late, keyCheck = false)

        assertSame("passed on for openFailureKind, so keyRejected stays false", late, mapped)
        assertEquals(OpenFailureKind.Corrupt, openFailureKind(mapped))
    }

    @Test
    fun classifyOpening_rawVerification_otherSubclasses_arePassedOn() {
        listOf(SQLiteDatabaseCorruptException(), SQLiteDiskIOException(), SQLiteFullException()).forEach { e ->
            listOf(true, false).forEach { keyCheck -> assertSame("$e", e, rawCheckFailure(e, keyCheck)) }
        }
    }

    @Test
    fun classifyOpening_storageFullAndTransientClasses_areTransient() {
        val transient = listOf(
            SQLiteFullException(),
            IOException(ErrnoException("write", OsConstants.ENOSPC)),
            IOException(ErrnoException("write", OsConstants.EDQUOT)),
            SQLiteDiskIOException(),
            SQLiteCantOpenDatabaseException(),
            SQLiteReadOnlyDatabaseException(),
            SQLiteAccessPermException(),
            SQLiteDatabaseLockedException(),
            SQLiteTableLockedException(),
            SQLiteOutOfMemoryException(),
            IOException(),
            CheckFailed(DbFailure.NewerVersion),
        )
        transient.forEach { assertEquals("$it", OpenFailureKind.Transient, openFailureKind(it)) }
    }

    @Test
    fun classifyOpening_everythingElse_isRethrown() {
        listOf(IllegalStateException(), SQLiteConstraintException(), CancellationException("cancelled")).forEach { e ->
            assertSame(e, runCatching { openFailureKind(e) }.exceptionOrNull())
        }
    }

    // Context Runtime, withDb.

    @Test
    fun classifyRuntime_storageFull_isStorageFull_andRollsBack() = runBlocking(Dispatchers.IO) {
        val holder = databases.holder()
        val db = holder.openDatabase()

        val result = holder.withDb {
            it.withTransaction {
                val sql = it.openHelper.writableDatabase
                TestRows(sql).person("Ann", 1)
                sql.query("PRAGMA max_page_count = ${sql.longOf("PRAGMA page_count")}").use { c -> c.moveToFirst() }
                TestRows(sql).note("x".repeat(200_000), createdAt = 1)
            }
        }

        assertEquals(AppResult.Err(AppError.StorageFull), result)
        assertEquals(0L, db.openHelper.writableDatabase.longOf("SELECT count(*) FROM persons"))
    }

    @Test
    fun classifyRuntime_transientClasses_areDatabaseUnavailable() = runBlocking {
        val holder = databases.holder()
        val transient = listOf(
            SQLiteDiskIOException(),
            SQLiteCantOpenDatabaseException(),
            SQLiteReadOnlyDatabaseException(),
            SQLiteAccessPermException(),
            SQLiteOutOfMemoryException(),
        )

        transient.forEach { e ->
            assertEquals("$e", AppResult.Err(AppError.DatabaseUnavailable), holder.withDb { throw e })
        }
    }

    @Test
    fun classifyRuntime_storageFullClasses_areStorageFull() = runBlocking {
        val holder = databases.holder()

        listOf(SQLiteFullException(), IOException(ErrnoException("write", OsConstants.ENOSPC))).forEach { e ->
            assertEquals("$e", AppResult.Err(AppError.StorageFull), holder.withDb { throw e })
        }
    }

    @Test
    fun classifyRuntime_programmingClasses_areRethrown() = runBlocking {
        val holder = databases.holder()
        val bugs = listOf(
            SQLiteConstraintException(),
            SQLiteDatabaseLockedException(),
            SQLiteTableLockedException(),
            SQLiteBlobTooBigException(),
            SQLiteMisuseException(),
            SQLiteException(),
            IOException(),
            IllegalStateException(),
        )

        bugs.forEach { e -> assertSame(e, runCatching { holder.withDb { throw e } }.exceptionOrNull()) }
        assertTrue("the holder stays usable", holder.state.value is HolderState.Open)
    }

    @Test
    fun classifyRuntime_realConstraintFailure_isRethrown() = runBlocking(Dispatchers.IO) {
        val holder = databases.holder()

        val thrown = runCatching {
            holder.withDb { db ->
                db.openHelper.writableDatabase.execSQL("INSERT INTO note_persons (note_id, person_id) VALUES (1, 1)")
            }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is SQLiteConstraintException)
    }

    // Context Runtime, flows.

    @Test
    fun classifyRuntime_transientFlowError_isRetriedThreeTimes_thenReported() =
        assertFlowRetried(SQLiteDiskIOException(), AppError.DatabaseUnavailable)

    @Test
    fun classifyRuntime_storageFullFlowError_isRetriedThreeTimes_thenReported() =
        assertFlowRetried(SQLiteFullException(), AppError.StorageFull)

    @Test
    fun classifyRuntime_corruptFlowError_isReportedOnce_withoutRetry() = runTest(timeout = 30.seconds) {
        val events = HolderEvents()
        val holder = databases.holder(events = events)
        var subscriptions = 0

        val corrupt = SQLiteDatabaseCorruptException()
        val collector = launch { holder.observe { failing(corrupt) { subscriptions++ } }.collect {} }
        advanceUntilIdle()

        assertEquals(1, subscriptions)
        assertEquals(1, events.corruptions.value)
        assertEquals(emptyList<AppError>(), events.flowProblems.value)
        collector.cancel()
    }

    @Test
    fun classifyRuntime_programmingFlowError_isRethrown() = runBlocking {
        val holder = databases.holder()
        val bug = IllegalStateException()

        val thrown = runCatching { holder.observe { failing(bug) {} }.first() }.exceptionOrNull()

        assertSame(bug, thrown)
    }

    @Test
    fun classifyRuntime_errorFromARetiredInstance_isSwallowed_andTheFlowResumes() = runBlocking(Dispatchers.Default) {
        withTimeout(30.seconds) {
            val holder = databases.holder()
            val retired = holder.state.value
            val subscribed = Channel<Int>(Channel.UNLIMITED)
            val cancelled = CompletableDeferred<Unit>()
            val stateAtThrow = CompletableDeferred<HolderState>()
            val release = CompletableDeferred<Unit>()
            var subscription = 0
            val collector = launch {
                holder.observe {
                    val number = ++subscription
                    flow {
                        emit(number)
                        // The first instance fails only after it was retired, the way a query on a closed Room fails.
                        if (number == 1) {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cancelled.complete(Unit)
                                    release.await()
                                    stateAtThrow.complete(holder.state.value)
                                    throw IllegalStateException()
                                }
                            }
                        }
                    }
                }.collect(subscribed::send)
            }
            assertEquals(1, subscribed.receive())

            val reopened = launch { holder.exclusive { Reopen.Same } }
            cancelled.await()
            release.complete(Unit)
            reopened.join()

            assertNotSame("the error came from a retired instance", retired, stateAtThrow.await())
            assertEquals(2, subscribed.receive())
            assertTrue(collector.isActive)
            collector.cancel()
        }
    }

    /**
     * Opens generation 1 with [key] after [prepare] has shaped the live file, asserts [expected], a Closed holder and
     * a zeroed key, and that the file was not deleted when [prepare] had created it.
     */
    private fun assertOpenFails(
        expected: OpenResult,
        key: TestKey = TestKey.Plain,
        prepare: (File) -> Unit,
    ) = runBlocking {
        val file = databases.files.live(GENERATION)
        prepare(file)
        val existed = file.exists()
        val holder = databases.newHolder()
        val bytes = key.bytes()

        val result = holder.open(GENERATION, bytes)

        file.setReadable(true, false)
        assertEquals(expected, result)
        assertSame(HolderState.Closed, holder.state.value)
        assertTrue("the key is zeroed", bytes.all { it == 0.toByte() })
        assertEquals("nothing creates or deletes the live file", existed, file.exists())
    }

    private fun assertFlowRetried(error: Throwable, reported: AppError) = runTest(timeout = 30.seconds) {
        val events = HolderEvents()
        val holder = databases.holder(events = events)
        var subscriptions = 0

        val collector = launch { holder.observe { failing(error) { subscriptions++ } }.collect {} }
        advanceUntilIdle() // the 2 s retry delays run in virtual time

        assertEquals("one subscription and three retries", 4, subscriptions)
        assertEquals(listOf(reported), events.flowProblems.value)
        assertEquals(0, events.corruptions.value)
        collector.cancel()
    }

    private fun failing(error: Throwable, onSubscribe: () -> Unit): Flow<Unit> = flow {
        onSubscribe()
        throw error
    }
}

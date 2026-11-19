package com.doro.organizer.data

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.longOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's one transaction frame ([transaction]) beyond the real `SQLITE_FULL` of DatabaseHolderClassifyTest: which
 * failure propagates when both the block and the end of the transaction fail ([transactionFailure]), and a block's
 * own failure when the transaction ends normally.
 */
@RunWith(AndroidJUnit4::class)
class WriteTransactionTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun anAutomaticRollback_keepsTheBlocksFailure_withTheFailedRollbackSuppressed() {
        val full = SQLiteFullException("full")
        val end = SQLiteException("no transaction is active")

        assertSame(full, transactionFailure(full, end))
        assertEquals(listOf<Throwable>(end), full.suppressed.toList())
    }

    /** Only SQLite rolls back by itself: after any other block failure a failing end means something else broke. */
    @Test
    fun aBlockFailureFromOutsideSqlite_letsTheEndFailurePropagate() {
        val failure = IllegalStateException("not SQLite")
        val end = SQLiteException("no transaction is active")

        assertSame(end, transactionFailure(failure, end))
        assertEquals(listOf<Throwable>(failure), end.suppressed.toList())
    }

    /** An end failure other than `SQLITE_ERROR` may leave the transaction open; an `Error` is the process's. */
    @Test
    fun anEndFailureThatMayLeaveTheTransactionOpen_propagates() {
        listOf(SQLiteDiskIOException("io"), SQLiteDatabaseLockedException("busy"), OutOfMemoryError("memory")).forEach { end ->
            val full = SQLiteFullException("full")

            assertSame(end, transactionFailure(full, end))
            assertEquals(listOf<Throwable>(full), end.suppressed.toList())
        }
    }

    @Test
    fun aBlockFailure_whenTheTransactionEndsNormally_propagatesUnchanged_andRollsBack() = runBlocking(Dispatchers.IO) {
        val db = databases.create()
        val failure = IllegalStateException("block")

        val thrown = runCatching {
            db.transaction {
                TestRows(db.openHelper.writableDatabase).person("Ann", 1)
                throw failure
            }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown === failure || thrown?.cause === failure)
        assertEquals(0, failure.suppressed.size)
        assertEquals(0L, db.openHelper.writableDatabase.longOf("SELECT count(*) FROM persons"))
        db.transaction { TestRows(db.openHelper.writableDatabase).person("Bea", 2) }
        assertEquals(1L, db.openHelper.writableDatabase.longOf("SELECT count(*) FROM persons"))
    }
}

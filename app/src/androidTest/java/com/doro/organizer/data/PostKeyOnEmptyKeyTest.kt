package com.doro.organizer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.openDescriptors
import com.doro.organizer.testing.readOnThreads
import java.util.concurrent.atomic.AtomicInteger
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe (SPEC T22) for db-lifecycle.md § Open sequence, "Verify at setup" (3): whether
 * `SQLiteDatabaseHook.postKey` is called for an empty key. The design relies on yes: db-lifecycle.md § Room builder
 * says sqlcipher-android calls the hook for every connection its pool opens, whatever the key, so a future
 * per-connection setting goes into the same hook, which is then passed for every key. Production passes no hook for
 * the empty key today (`RawConnections.open`, `buildRoom`), so these tests hand the library a recording hook on the
 * same calls: `SQLiteDatabase.openDatabase` with production's raw flags and error handler, and the
 * `SupportOpenHelperFactory` that `buildRoom` uses. A failure states the observed counts and is reported.
 */
@RunWith(AndroidJUnit4::class)
class PostKeyOnEmptyKeyTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun rawConnection_emptyKey_callsPostKeyOnce_andItsSettingHolds() {
        val file = databases.createLive()
        val hook = RecordingHook()

        val db = SQLiteDatabase.openDatabase(
            file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            NoDeleteErrorHandler, hook,
        )
        val cacheSize = try {
            db.longOf("PRAGMA cache_size")
        } finally {
            db.close()
        }

        assertEquals("preKey calls for one raw connection with the empty key", 1, hook.preKeys.get())
        assertEquals("postKey calls for one raw connection with the empty key", 1, hook.postKeys.get())
        assertEquals("cache_size after postKey set it on the empty-key connection", CACHE_SIZE, cacheSize)
    }

    @Test
    fun walPool_emptyKey_callsPostKeyForEveryConnectionItOpens() {
        val file = databases.createLive()
        val hook = RecordingHook()
        val helper = SupportOpenHelperFactory(ByteArray(0), hook, true).create(
            SupportSQLiteOpenHelper.Configuration
                .builder(InstrumentationRegistry.getInstrumentation().targetContext)
                .name(file.absolutePath)
                .callback(ExistingDatabase)
                .build(),
        )

        val connections = try {
            helper.writableDatabase.readOnThreads()
            openDescriptors(file) // one descriptor of the main file per open connection
        } finally {
            helper.close()
        }

        assertTrue("connections the WAL pool opened: $connections", connections >= 2)
        assertEquals("postKey calls of a pool that opened $connections connections", connections, hook.postKeys.get())
    }

    /** Counts the hook's calls; `postKey` applies a connection-scope setting that the test can read back. */
    private class RecordingHook : SQLiteDatabaseHook {
        val preKeys = AtomicInteger()
        val postKeys = AtomicInteger()

        override fun preKey(connection: SQLiteConnection) {
            preKeys.incrementAndGet()
        }

        override fun postKey(connection: SQLiteConnection) {
            postKeys.incrementAndGet()
            connection.executeRaw("PRAGMA cache_size = $CACHE_SIZE", null, null)
        }
    }

    /** The helper opens a database of this build that already exists, so neither onCreate nor onUpgrade may run. */
    private object ExistingDatabase : SupportSQLiteOpenHelper.Callback(AppDatabase.VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) = error("onCreate on an existing database")

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("onUpgrade on a database of this build")
    }

    private companion object {
        /** A page-cache size no default produces (negative: KiB). */
        const val CACHE_SIZE = -2345L
    }
}

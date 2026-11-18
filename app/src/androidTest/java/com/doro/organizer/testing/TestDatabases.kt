package com.doro.organizer.testing

import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.CipherCompatibilityHook
import com.doro.organizer.data.SqlcipherEngine
import com.doro.organizer.data.buildRoom
import com.doro.organizer.data.db.AppDatabase
import java.io.File
import java.nio.file.Files
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.rules.ExternalResource

/**
 * Test databases on real SQLCipher files (testing.md § 7). Each instance gets its own fresh directory, so two rules in
 * one process never touch each other's files; the rule closes every connection it opened, zeroes its key and deletes
 * its directory afterwards. Declare it as a `@get:Rule`. [keyBytes] turns a [TestKey] into the array SQLCipher
 * receives; a test replaces it only to watch those arrays.
 */
class TestDatabases(private val keyBytes: (TestKey) -> ByteArray = TestKey::bytes) : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connections = mutableListOf<Pair<SQLiteDatabase, ByteArray>>()
    private val rooms = mutableListOf<Pair<AppDatabase, ByteArray>>()
    private var created = 0

    /** This instance's own directory, empty when the test starts. */
    lateinit var directory: File
        private set

    override fun before() {
        SqlcipherEngine.init() // the production process-wide setup: library, logger, SQLCipher 4 pin
        val root = File(context.noBackupFilesDir, "test-databases")
        check(root.isDirectory || root.mkdirs()) { "cannot create the test database root" }
        directory = Files.createTempDirectory(root.toPath(), "db").toFile()
    }

    /** Closes and zeroes everything even when one close fails, deletes [directory], then rethrows the first failure. */
    override fun after() {
        // RoomDatabase.close() is idempotent and also releases an instance that was built but never opened.
        val failures = rooms.map { (db, key) -> closeThenZero(key, db::close) } +
            connections.map { (db, key) -> closeThenZero(key) { if (db.isOpen) db.close() } }
        rooms.clear()
        connections.clear()
        directory.deleteRecursively()
        failures.firstOrNull { it != null }?.let { throw it }
    }

    /** A file named [name] in [directory]. */
    fun file(name: String): File = File(directory, name)

    /**
     * The app's Room database on a new file in [directory], built by the production `buildRoom` (testing.md § 7).
     * Nothing is opened until the test reads `openHelper.writableDatabase`; the rule closes it after the test.
     */
    fun create(key: TestKey = TestKey.Plain): AppDatabase {
        val file = file("app-${++created}.db")
        check(!file.exists()) { "create builds a new file; use room for an existing one" }
        return room(file, key)
    }

    /**
     * The app's Room database on [file], built by the production `buildRoom`: as the live instance by default, or as
     * a [temporary] maintenance instance. Room creates the file on the first open when it does not exist; the rule
     * closes it after the test.
     */
    fun room(file: File, key: TestKey, temporary: Boolean = false): AppDatabase {
        val bytes = keyBytes(key)
        return buildRoom(context, file, bytes, temporary).also { rooms += it to bytes }
    }

    /**
     * A SQLCipher connection on the existing [file], opened as production's raw connections are (db-lifecycle.md
     * § Open sequence, `RawConnections.open`): never creating the file, never writing `android_metadata`, never
     * deleting on corruption. For pragmas, header reads and mutations; the rule closes it after the test.
     */
    fun raw(file: File, key: TestKey): SQLiteDatabase = open(file, key, create = false)

    /** As [raw], but creates [file], which must not exist yet: for building fixtures. */
    fun createRaw(file: File, key: TestKey): SQLiteDatabase {
        check(!file.exists()) { "createRaw builds a new file; use raw for an existing one" }
        return open(file, key, create = true)
    }

    private fun open(file: File, key: TestKey, create: Boolean): SQLiteDatabase {
        val bytes = keyBytes(key)
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        val hook = if (bytes.isEmpty()) null else CipherCompatibilityHook
        return runCatching { SQLiteDatabase.openDatabase(file.path, bytes, null, flags, NoDeleteHandler, hook) }
            .onFailure { bytes.fill(0) }
            .getOrThrow()
            .also { connections += it to bytes }
    }

    /** Closes, then zeroes [key]: the open helper and a connection keep the array until close (security.md § 6.4). */
    private fun closeThenZero(key: ByteArray, close: () -> Unit): Throwable? =
        runCatching(close).exceptionOrNull().also { key.fill(0) }

    // The production NoDeleteErrorHandler arrives with RawConnections (T9); until then this copy matches
    // db-lifecycle.md § Open sequence.
    private object NoDeleteHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase, exception: SQLiteException) = Unit
    }
}

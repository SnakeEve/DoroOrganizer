package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.data.CipherCompatibilityHook
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.DefaultDatabaseHolder
import com.doro.organizer.data.NoDeleteErrorHandler
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.SqlcipherEngine
import com.doro.organizer.data.buildRoom
import com.doro.organizer.data.db.AppDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.rules.ExternalResource

/**
 * Test databases on real SQLCipher files (testing.md § 7). Each instance gets its own fresh directory, so two rules in
 * one process never touch each other's files; the rule closes every holder and connection it opened, zeroes its keys
 * and deletes its directory afterwards. Declare it as a `@get:Rule`. [keyBytes] turns a [TestKey] into the array
 * SQLCipher receives; a test replaces it only to watch those arrays.
 */
class TestDatabases(private val keyBytes: (TestKey) -> ByteArray = TestKey::bytes) : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connections = mutableListOf<Pair<SQLiteDatabase, ByteArray>>()
    private val rooms = mutableListOf<Pair<AppDatabase, ByteArray>>()
    private val holders = mutableListOf<DefaultDatabaseHolder>()
    private var created = 0

    /** This instance's own directory, empty when the test starts. */
    lateinit var directory: File
        private set

    /**
     * The database files of this instance: `databases/`, the `noBackupFilesDir` equivalent `nobackup/` (staging,
     * restore, the data-reset marker) and the `cacheDir` equivalent `cache/` (share files), all in [directory].
     */
    lateinit var files: DbFiles
        private set

    override fun before() {
        SqlcipherEngine.init() // the production process-wide setup: library, logger, SQLCipher 4 pin
        val root = File(context.noBackupFilesDir, "test-databases")
        check(root.isDirectory || root.mkdirs()) { "cannot create the test database root" }
        directory = Files.createTempDirectory(root.toPath(), "db").toFile()
        files = DbFiles(
            databasesDir = File(directory, "databases").apply { check(mkdir()) { "cannot create databases/" } },
            noBackupDir = File(directory, "nobackup"),
            cacheDir = File(directory, "cache"),
        )
    }

    /**
     * Closes every holder (which zeroes its key), then closes and zeroes everything else even when one close fails,
     * deletes [directory], then rethrows the first failure.
     */
    override fun after() {
        val holderFailures = holders.map { holder -> runCatching { runBlocking { holder.close() } }.exceptionOrNull() }
        // RoomDatabase.close() is idempotent and also releases an instance that was built but never opened.
        val failures = holderFailures +
            rooms.map { (db, key) -> closeThenZero(key, db::close) } +
            connections.map { (db, key) -> closeThenZero(key) { if (db.isOpen) db.close() } }
        holders.clear()
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
     * A valid, empty database of [key]'s mode at `files.live(generation)`, created by the production Room builder
     * and closed again, so that only a holder opens it afterwards.
     */
    fun createLive(generation: Long = GENERATION, key: TestKey = TestKey.Plain): File {
        val file = files.live(generation)
        check(!file.exists()) { "generation $generation exists" }
        room(file, key).run {
            openHelper.writableDatabase
            close()
        }
        return file
    }

    /**
     * The production `DatabaseHolder` over [files], still Closed, reporting to [events]; it opens Room on the IO
     * dispatcher of [dispatchers]. The rule closes it after the test.
     */
    fun newHolder(
        events: HolderEvents = HolderEvents(),
        dispatchers: AppDispatchers = AppDispatchers.production(),
    ): DefaultDatabaseHolder =
        DefaultDatabaseHolder(context, files, dispatchers, { events }, events).also { holders += it }

    /**
     * testing.md § 7 `holder(key)`: a database of [key]'s mode at `doro-1.db`, opened by the production holder
     * through its normal open and verify path. Repository tests use it, so they reach DAOs only through the holder.
     */
    fun holder(key: TestKey = TestKey.Plain, events: HolderEvents = HolderEvents()): DefaultDatabaseHolder {
        createLive(GENERATION, key)
        return newHolder(events).also { holder ->
            val result = runBlocking { holder.open(GENERATION, keyBytes(key)) }
            check(result == OpenResult.Opened) { "holder did not open: $result" }
        }
    }

    /**
     * A SQLCipher connection on the existing [file], opened as production's raw connections are (db-lifecycle.md
     * § Open sequence, `RawConnections.open`): never creating the file, never writing `android_metadata`, never
     * deleting on corruption. For pragmas, header reads and mutations; the rule closes it after the test.
     */
    fun raw(file: File, key: TestKey): SQLiteDatabase = open(file, key, SQLiteDatabase.OPEN_READWRITE)

    /** As [raw], but creates [file], which must not exist yet: for building fixtures. */
    fun createRaw(file: File, key: TestKey): SQLiteDatabase {
        check(!file.exists()) { "createRaw builds a new file; use raw for an existing one" }
        return open(file, key, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
    }

    /**
     * As [raw], but read-only: sqlcipher-android neither switches the journal mode nor writes `android_metadata` on
     * a read-only connection, so a check through it leaves [file] as it found it. The rule closes it after the test.
     */
    fun readOnly(file: File, key: TestKey): SQLiteDatabase = open(file, key, SQLiteDatabase.OPEN_READONLY)

    private fun open(file: File, key: TestKey, mode: Int): SQLiteDatabase {
        val bytes = keyBytes(key)
        val flags = mode or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        val hook = if (bytes.isEmpty()) null else CipherCompatibilityHook
        return runCatching { SQLiteDatabase.openDatabase(file.path, bytes, null, flags, NoDeleteErrorHandler, hook) }
            .onFailure { bytes.fill(0) }
            .getOrThrow()
            .also { connections += it to bytes }
    }

    /** Closes, then zeroes [key]: the open helper and a connection keep the array until close (security.md § 6.4). */
    private fun closeThenZero(key: ByteArray, close: () -> Unit): Throwable? =
        runCatching(close).exceptionOrNull().also { key.fill(0) }

    companion object {
        /** The generation [holder] creates and opens. */
        const val GENERATION = 1L
    }
}

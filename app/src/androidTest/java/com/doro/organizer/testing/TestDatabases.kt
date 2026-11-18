package com.doro.organizer.testing

import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.SqlcipherEngine
import java.io.File
import java.nio.file.Files
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.junit.rules.ExternalResource

/**
 * Test databases on real SQLCipher files (testing.md § 7). Each instance gets its own fresh directory, so two rules in
 * one process never touch each other's files; the rule closes every connection it opened and deletes its directory
 * afterwards. Declare it as a `@get:Rule`.
 */
class TestDatabases : ExternalResource() {
    private val opened = mutableListOf<SQLiteDatabase>()

    /** This instance's own directory, empty when the test starts. */
    lateinit var directory: File
        private set

    override fun before() {
        SqlcipherEngine.init() // the production process-wide setup: library, logger, SQLCipher 4 pin
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "test-databases")
        check(root.isDirectory || root.mkdirs()) { "cannot create the test database root" }
        directory = Files.createTempDirectory(root.toPath(), "db").toFile()
    }

    override fun after() {
        opened.filter(SQLiteDatabase::isOpen).forEach(SQLiteDatabase::close)
        opened.clear()
        directory.deleteRecursively()
    }

    /** A file named [name] in [directory]. */
    fun file(name: String): File = File(directory, name)

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
        val bytes = key.bytes()
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        val hook = if (bytes.isEmpty()) null else CompatibilityHook
        return SQLiteDatabase.openDatabase(file.path, bytes, null, flags, NoDeleteHandler, hook).also { opened += it }
    }

    // The production NoDeleteErrorHandler and CipherCompatibilityHook arrive with RawConnections (T9); until then
    // these copies match db-lifecycle.md § Open sequence and security.md § 3.4.
    private object NoDeleteHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase, exception: SQLiteException) = Unit
    }

    private object CompatibilityHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) =
            connection.executeRaw("PRAGMA cipher_compatibility = 4", null, null)
    }
}

package com.doro.organizer.testing

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class TestDatabasesTest {
    @get:Rule
    val databases = TestDatabases()

    @Test
    fun plain_writesStandardSqliteHeader() {
        val file = databases.file("plain.db")
        databases.createRaw(file, TestKey.Plain).use { it.execSQL("CREATE TABLE t (x INTEGER)") }

        assertArrayEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII), header(file))
    }

    @Test
    fun raw_reopensOnlyWithSameKey() {
        assertReopensOnlyWithSameKey(TestKey.Raw(ByteArray(32) { it.toByte() }), TestKey.Raw(ByteArray(32) { 7 }))
    }

    @Test
    fun passphrase_reopensOnlyWithSameKey() {
        assertReopensOnlyWithSameKey(TestKey.Passphrase("correct horse"), TestKey.Passphrase("battery staple"))
    }

    @Test
    fun raw_writesNoAndroidMetadata() {
        val file = databases.file("schema.db")
        databases.createRaw(file, TestKey.Plain).use { it.execSQL("CREATE TABLE t (x INTEGER)") }

        databases.raw(file, TestKey.Plain).use { it.execSQL("INSERT INTO t VALUES (1)") }

        val tables = databases.raw(file, TestKey.Plain).use { it.stringsOf("SELECT name FROM sqlite_master") }
        assertEquals(listOf("t"), tables)
    }

    @Test
    fun raw_missingFile_throwsAndCreatesNothing() {
        val file = databases.file("missing.db")

        assertThrows(SQLiteException::class.java) { databases.raw(file, TestKey.Plain) }
        assertFalse(file.exists())
    }

    @Test
    fun createRaw_existingFile_isRejected() {
        val file = databases.file("existing.db").apply { writeBytes(ByteArray(0)) }

        assertThrows(IllegalStateException::class.java) { databases.createRaw(file, TestKey.Plain) }
    }

    @Test
    fun raw_encodesDekAsSqlcipherRawKey() {
        val key = TestKey.Raw(ByteArray(32) { 0xAB.toByte() })

        assertEquals("x'" + "ab".repeat(32) + "'", String(key.bytes(), Charsets.US_ASCII))
    }

    @Test
    fun after_closesConnectionsAndDeletesOnlyItsOwnDirectory() {
        val outerFile = databases.file("outer.db")
        databases.createRaw(outerFile, TestKey.Plain).use { it.execSQL("CREATE TABLE t (x INTEGER)") }
        val rule = TestDatabases()
        lateinit var db: SQLiteDatabase
        lateinit var directory: File
        val statement = object : Statement() {
            override fun evaluate() {
                directory = rule.directory
                assertNotEquals(databases.directory, directory)
                db = rule.createRaw(rule.file("x.db"), TestKey.Plain)
                assertTrue(db.isOpen)
            }
        }

        rule.apply(statement, Description.EMPTY).evaluate()

        assertFalse(db.isOpen)
        assertFalse(directory.exists())
        assertTrue(outerFile.exists())
    }

    @Test
    fun create_eachCallBuildsItsOwnDatabase() {
        val first = databases.create().openHelper.writableDatabase
        val second = databases.create().openHelper.writableDatabase

        first.execSQL("INSERT INTO persons (name, created_at, updated_at) VALUES ('Ann', 1, 1)")

        assertEquals(1L, first.longOf("SELECT COUNT(*) FROM persons"))
        assertEquals(0L, second.longOf("SELECT COUNT(*) FROM persons"))
    }

    @Test
    fun after_closesRoomInstancesAndConnectionsThenZeroesTheirKeys() {
        val issued = mutableListOf<ByteArray>()
        val rule = TestDatabases(keyBytes = { key -> key.bytes().also(issued::add) })
        val key = TestKey.Raw(ByteArray(32) { 0x5A })
        lateinit var room: AppDatabase
        lateinit var never: AppDatabase
        lateinit var connection: SQLiteDatabase
        val statement = object : Statement() {
            override fun evaluate() {
                room = rule.room(rule.file("app.db"), key)
                room.openHelper.writableDatabase
                never = rule.room(rule.file("never.db"), key)
                connection = rule.createRaw(rule.file("raw.db"), key)
                assertTrue(room.isOpen && connection.isOpen)
                assertTrue(issued.none(::isZeroed))
            }
        }

        rule.apply(statement, Description.EMPTY).evaluate()

        assertFalse(room.isOpen)
        assertFalse(never.isOpen)
        assertFalse(connection.isOpen)
        assertEquals(3, issued.size)
        assertTrue(issued.all(::isZeroed))
    }

    @Test
    fun raw_rejectedKey_isZeroedAfterwards() {
        val file = databases.file("keyed.db")
        databases.createRaw(file, TestKey.Raw(ByteArray(32) { 1 })).use { it.execSQL("CREATE TABLE t (x INTEGER)") }
        val issued = mutableListOf<ByteArray>()
        val rule = TestDatabases(keyBytes = { key -> key.bytes().also(issued::add) })
        val statement = object : Statement() {
            override fun evaluate() {
                assertThrows(SQLiteNotADatabaseException::class.java) {
                    rule.raw(file, TestKey.Raw(ByteArray(32) { 2 })).longOf("SELECT count(*) FROM t")
                }
            }
        }

        rule.apply(statement, Description.EMPTY).evaluate()

        assertTrue(isZeroed(issued.single())) // whether the open or the first query rejected the key
    }

    private fun assertReopensOnlyWithSameKey(key: TestKey, otherKey: TestKey) {
        val file = databases.file("keyed.db")
        databases.createRaw(file, key).use { db ->
            db.execSQL("CREATE TABLE t (x INTEGER)")
            db.execSQL("INSERT INTO t VALUES (42)")
        }

        assertNotEquals("SQLite format 3", String(header(file), Charsets.US_ASCII).take(15))
        databases.raw(file, key).use { db -> assertEquals(42L, db.longOf("SELECT x FROM t")) }
        // db-lifecycle.md § Failure classification: a rejected key is SQLITE_NOTADB, never another SQLite error.
        assertThrows(SQLiteNotADatabaseException::class.java) {
            databases.raw(file, otherKey).use { db -> db.longOf("SELECT count(*) FROM sqlite_master") }
        }
    }

    private fun header(file: File): ByteArray = file.readBytes().copyOf(16)

    private fun isZeroed(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }
}

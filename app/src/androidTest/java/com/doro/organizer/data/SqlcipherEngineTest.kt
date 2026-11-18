package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlcipherEngineTest {
    private lateinit var dir: File

    @Before
    fun createDir() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cacheDir, "sqlcipher-engine-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
    }

    @After
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun init_twice_keyedDatabaseOpensWithSqlcipher4() {
        SqlcipherEngine.init()
        SqlcipherEngine.init()

        withKeyedDatabase { db ->
            val version = db.single("PRAGMA cipher_version")
            assertTrue("cipher_version=$version", version.startsWith("4."))
        }
    }

    // Library-upgrade guard (security.md § 3.4): SQLCipher 4.20's own defaults equal these values, so this cannot
    // prove that init() pins them. L-28 CipherSettingsTest covers the pin on DatabaseHolder and backup connections.
    @Test
    fun keyedDatabase_reportsSqlcipher4Parameters() {
        SqlcipherEngine.init()

        withKeyedDatabase { db ->
            assertEquals("cipher_page_size", "4096", db.single("PRAGMA cipher_page_size"))
            assertEquals("cipher_hmac_algorithm", "HMAC_SHA512", db.single("PRAGMA cipher_hmac_algorithm"))
            assertEquals("cipher_kdf_algorithm", "PBKDF2_HMAC_SHA512", db.single("PRAGMA cipher_kdf_algorithm"))
            assertEquals("kdf_iter", "256000", db.single("PRAGMA kdf_iter"))
        }
    }

    private fun withKeyedDatabase(block: (SQLiteDatabase) -> Unit) {
        val passphrase = ByteArray(32) { it.toByte() }
        val db = SQLiteDatabase.openOrCreateDatabase(File(dir, "keyed.db"), passphrase, null, null)
        try {
            db.execSQL("CREATE TABLE t (x INTEGER)")
            block(db)
        } finally {
            db.close()
            passphrase.fill(0)
        }
    }

    private fun SQLiteDatabase.single(statement: String): String =
        query(statement).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}

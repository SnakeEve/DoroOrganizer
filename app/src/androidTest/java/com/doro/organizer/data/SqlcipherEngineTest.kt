package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.testing.stringsOf
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
            val version = db.stringsOf("PRAGMA cipher_version").single()
            assertTrue("cipher_version=$version", version?.startsWith("4.") == true)
        }
    }

    // Library-upgrade guard (security.md § 3.4): SQLCipher 4.20's own defaults equal these values, so this cannot
    // prove that init() pins them. L-28 CipherSettingsTest covers the pin on DatabaseHolder and backup connections.
    @Test
    fun keyedDatabase_reportsSqlcipher4Parameters() {
        SqlcipherEngine.init()

        withKeyedDatabase { db ->
            val expected = mapOf(
                "cipher_page_size" to "4096",
                "cipher_hmac_algorithm" to "HMAC_SHA512",
                "cipher_kdf_algorithm" to "PBKDF2_HMAC_SHA512",
                "kdf_iter" to "256000",
            )
            assertEquals(expected, expected.mapValues { (pragma, _) -> db.stringsOf("PRAGMA $pragma").single() })
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
}

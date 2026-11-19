package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.PageSizeSmoke
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.assertExpectedPageSize
import com.doro.organizer.testing.stringsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-28, the live-database half (security.md § 3.4): a Device-mode database keyed with the raw key
 * and opened through `DatabaseHolder` reports the pinned SQLCipher 4 parameters on its own connection. The backup
 * half (a passphrase file, `kdf_iter` = 256000) comes with the export pipeline.
 */
@PageSizeSmoke
@RunWith(AndroidJUnit4::class)
class CipherSettingsTest {
    @get:Rule
    val databases = TestDatabases()

    @Before
    fun pageSize() = assertExpectedPageSize()

    @Test
    fun deviceModeDatabase_reportsThePinnedSqlcipher4Parameters() {
        val key = TestKey.Raw(ByteArray(32) { (it * 5 + 1).toByte() })
        databases.holder(key)

        val connection = databases.raw(databases.files.live(TestDatabases.GENERATION), key)
        val pinned = mapOf(
            "cipher_page_size" to "4096",
            "cipher_hmac_algorithm" to "HMAC_SHA512",
            "cipher_kdf_algorithm" to "PBKDF2_HMAC_SHA512",
        )

        assertEquals(pinned, pinned.mapValues { (pragma, _) -> connection.stringsOf("PRAGMA $pragma").single() })
        val version = connection.stringsOf("PRAGMA cipher_version").single()
        assertTrue("cipher_version=$version", version?.startsWith("4.") == true)
    }
}

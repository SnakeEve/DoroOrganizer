package com.doro.organizer.data

import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.PageSizeSmoke
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.assertExpectedPageSize
import com.doro.organizer.testing.stringsOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-28 (security.md § 3.4): a Device-mode database keyed with the raw key and opened through
 * `DatabaseHolder`, and a backup exported with a backup password, report the pinned SQLCipher 4 parameters on their
 * own connections; the passphrase file also pins `kdf_iter` = 256000.
 */
@PageSizeSmoke
@RunWith(AndroidJUnit4::class)
class CipherSettingsTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val pinned = mapOf(
        "cipher_page_size" to "4096",
        "cipher_hmac_algorithm" to "HMAC_SHA512",
        "cipher_kdf_algorithm" to "PBKDF2_HMAC_SHA512",
    )

    @Before
    fun pageSize() = assertExpectedPageSize()

    @Test
    fun deviceModeDatabase_reportsThePinnedSqlcipher4Parameters() {
        val key = TestKey.Raw(ByteArray(32) { (it * 5 + 1).toByte() })
        databases.holder(key)

        val connection = databases.raw(databases.files.live(TestDatabases.GENERATION), key)

        assertPinned(connection)
    }

    @Test
    fun aBackupWithABackupPassword_isAPassphraseFile_withThePinnedParameters() = runTest(timeout = 120.seconds) {
        val setup = MaintenanceSetup(databases, vaults, scope.scope)
        setup.start()
        val target = databases.file("backup-target.db")
        assertFalse(target.exists())

        val result = setup.maintenance.backupToDocument(Uri.fromFile(target), "backup password 2".toCharArray())

        assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), result)
        val connection = databases.raw(target, TestKey.Passphrase("backup password 2"))
        assertPinned(connection)
        assertEquals(listOf("256000"), connection.stringsOf("PRAGMA kdf_iter"))
    }

    private fun assertPinned(connection: SupportSQLiteDatabase) {
        assertEquals(pinned, pinned.mapValues { (pragma, _) -> connection.stringsOf("PRAGMA $pragma").single() })
        val version = connection.stringsOf("PRAGMA cipher_version").single()
        assertTrue("cipher_version=$version", version?.startsWith("4.") == true)
    }
}

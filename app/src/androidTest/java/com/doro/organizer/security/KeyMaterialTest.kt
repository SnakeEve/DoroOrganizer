package com.doro.organizer.security

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.DefaultAppTime
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.KeyVaultRule
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-04, device part (DEC-60, security.md § 3): the production vault of `DefaultKeyVault.create`, with the
 * real AndroidKeyStore, the real PBKDF2 and the real calibration on the device clock. It uses the production key-state
 * path, so the file and its `.new` are removed before and after every test.
 */
@RunWith(AndroidJUnit4::class)
class KeyMaterialTest {
    @get:Rule
    val rule = KeyVaultRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val stateFile get() = File(rule.context.noBackupFilesDir, DefaultKeyVault.STATE_FILE_NAME)
    private val password = "correct horse 42".toByteArray()

    private fun productionVault() =
        DefaultKeyVault.create(rule.context, FakeSettingsStore(), DefaultAppTime.system(scope), AppDispatchers.production())

    @Before
    fun noKeyState() = AtomicFile(stateFile).delete()

    @After
    fun release() {
        scope.cancel()
        AtomicFile(stateFile).delete()
    }

    @Test
    fun kek_isAes256Gcm_withoutUserAuthentication_inTheBestHardware() = runTest(timeout = 60.seconds) {
        productionVault().prepare(KeyMode.DEVICE, null)

        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEK_ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertEquals(256, info.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertFalse(info.isUserAuthenticationRequired)
        val strongBox = rule.context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        // Without the StrongBox feature the vault never asks for StrongBox (security.md § 3.2): the TEE holds the key.
        val expectedLevel = if (strongBox) KeyProperties.SECURITY_LEVEL_STRONGBOX else KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(expectedLevel, info.securityLevel)
        } else {
            @Suppress("DEPRECATION")
            assertTrue(info.isInsideSecureHardware)
        }
    }

    @Test
    fun passwordSlot_storesTheCalibratedIterations_andA16ByteSalt_andUnlocks() = runTest(timeout = 120.seconds) {
        val vault = productionVault()
        val prepared = vault.prepare(KeyMode.PASSWORD, password.copyOf())
        vault.commit(1, prepared.slot, null)

        val kdf = requireNotNull(requireNotNull(vault.readState()).slot.kdf)

        assertTrue("${kdf.iterations}", kdf.iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        assertEquals(0, kdf.iterations % 10_000)
        assertEquals(16, kdf.salt.size)
        assertArrayEquals(prepared.rawKey, (vault.unlock(password) as UnlockResult.Key).rawKey)
    }

    @Test
    fun rawKey_isXQuote64HexQuote() = runTest(timeout = 60.seconds) {
        val raw = productionVault().prepare(KeyMode.DEVICE, null).rawKey

        assertEquals(67, raw.size)
        assertTrue(Regex("x'[0-9a-f]{64}'").matches(String(raw, Charsets.US_ASCII)))
    }

    @Test
    fun noFileUnderTheDataDirectory_containsADek() = runTest(timeout = 120.seconds) {
        val vault = productionVault()
        val keys = listOf(
            vault.prepare(KeyMode.DEVICE, null).also { vault.commit(1, it.slot, null) },
            vault.prepare(KeyMode.PASSWORD, password.copyOf()).also { vault.commit(2, it.slot, null) },
        ).map { it.rawKey }
        assertTrue(stateFile.exists())

        val needles = keys.flatMap { raw ->
            val hex = raw.copyOfRange(2, 66)
            val dek = ByteArray(32) { String(hex, 2 * it, 2, Charsets.US_ASCII).toInt(16).toByte() }
            listOf(dek, hex)
        }
        val files = rule.context.dataDir.walkTopDown().filter { it.isFile && it.canRead() }.toList()

        for (file in files) {
            val content = file.readBytes()
            needles.forEach { assertFalse("${file.name} holds key material", content.contains(it)) }
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}

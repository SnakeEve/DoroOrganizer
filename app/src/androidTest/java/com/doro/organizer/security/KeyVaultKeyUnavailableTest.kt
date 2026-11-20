package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.FaultyKekKeystore
import com.doro.organizer.testing.KekOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import com.doro.organizer.testing.TestKdf
import com.doro.organizer.testing.assertThrowsSuspending
import com.doro.organizer.testing.testDispatchers
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The KeyVault side of testing.md L-05 (DEC-86, security.md § 5.2) in Device and Password mode: every case answers
 * `KeyFailed` (or an invalid state), never a wrong password; nothing counts toward the backoff, no PBKDF2 runs and
 * the key-state file is untouched. The gate and recovery halves come with SessionGate and DbMaintenance.
 */
@RunWith(AndroidJUnit4::class)
class KeyVaultKeyUnavailableTest {
    @get:Rule
    val rule = KeyVaultRule()

    private val time = FakeAppTime()
    private val settings = FakeSettingsStore()
    private val kdf = TestKdf(time)
    private val kek = FaultyKekKeystore()
    private val password = "correct horse 42".toByteArray()
    private val encrypted = listOf(KeyMode.DEVICE, KeyMode.PASSWORD)

    private fun TestScope.vault() = rule.vault(settings, time, testDispatchers(), kek, kdf = kdf)

    private fun runVaultTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 60.seconds) { body() }

    private suspend fun KeyVault.install(mode: KeyMode): PreparedKey =
        prepare(mode, if (mode == KeyMode.PASSWORD) password.copyOf() else null).also { commit(1, it.slot, null) }

    private suspend fun KeyVault.unlockAs(mode: KeyMode) = unlock(if (mode == KeyMode.PASSWORD) password else null)

    private fun editState(edit: JSONObject.() -> Unit) =
        rule.stateFile.writeText(JSONObject(rule.stateFile.readText()).apply(edit).toString())

    private fun JSONObject.slot(): JSONObject = getJSONObject("slot")

    /** Re-encodes the base64 field [name] of [obj] with one bit of byte [index] flipped. */
    private fun flipByte(obj: JSONObject, name: String, index: Int = 0) {
        val bytes = Base64.getDecoder().decode(obj.getString(name))
        bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        obj.put(name, Base64.getEncoder().encodeToString(bytes))
    }

    /**
     * Installs each encrypted mode, applies [damage], and asserts `KeyFailed` with nothing counted, derived or
     * written; [readable] says whether the damaged file still parses.
     */
    private suspend fun TestScope.assertKeyFailed(readable: Boolean, damage: suspend (KeyMode) -> Unit) {
        for (mode in encrypted) {
            val vault = vault()
            vault.install(mode)
            settings.setBackoff(failures = 2, retryAtElapsedMs = 0)
            damage(mode)
            val file = rule.stateFile.takeIf { it.exists() }?.readBytes()
            val derivations = kdf.derivations().size

            assertEquals("$mode", UnlockResult.KeyFailed, vault.unlockAs(mode))

            if (readable) {
                vault.readState() // still parses: the damage is past the parser
            } else if (file != null) {
                val e = assertThrowsSuspending<KeyVaultException> { vault.readState() }
                assertEquals(KeyFailure.InvalidState, e.failure)
            }
            assertEquals("$mode: no PBKDF2", derivations, kdf.derivations().size)
            assertEquals("$mode: not counted", 2, settings.snapshot().unlockFailures)
            file?.let { assertArrayEquals("$mode: the file is untouched", it, rule.stateFile.readBytes()) }
        }
    }

    @Test
    fun l05a_kekAliasDeleted() = runVaultTest {
        assertKeyFailed(readable = true) { rule.deleteKekAlias() }
    }

    @Test
    fun l05b_keyStateMissing() = runVaultTest {
        assertKeyFailed(readable = true) { rule.stateFile.delete() }
        assertNull(vault().readState())
    }

    @Test
    fun l05c_truncatedJson() = runVaultTest {
        assertKeyFailed(readable = false) { rule.stateFile.writeBytes(rule.stateFile.readBytes().copyOf(40)) }
    }

    @Test
    fun l05d_flippedOuterWrap() = runVaultTest {
        assertKeyFailed(readable = true) { editState { flipByte(slot(), "wrappedDek", index = 20) } }
    }

    @Test
    fun l05e_formatVersion2() = runVaultTest {
        assertKeyFailed(readable = false) { editState { put("formatVersion", 2) } }
    }

    @Test
    fun l05f_unknownMode() = runVaultTest {
        assertKeyFailed(readable = false) { editState { slot().put("mode", "BIOMETRIC") } }
    }

    @Test
    fun l05g_fieldMissingForTheMode() = runVaultTest {
        assertKeyFailed(readable = false) { editState { slot().remove("outerIv") } }
    }

    @Test
    fun l05h_wrappedDekOf47Bytes() = runVaultTest {
        assertKeyFailed(readable = false) {
            editState {
                val wrapped = Base64.getDecoder().decode(slot().getString("wrappedDek"))
                slot().put("wrappedDek", Base64.getEncoder().encodeToString(wrapped.copyOf(47)))
            }
        }
    }

    @Test
    fun l05i_switchedMode_inTheFile_isInvalid() = runVaultTest {
        assertKeyFailed(readable = false) { mode ->
            editState { slot().put("mode", if (mode == KeyMode.DEVICE) "PASSWORD" else "DEVICE") }
        }
    }

    @Test
    fun l05i_switchedMode_inMemory_failsTheOuterAad() = runVaultTest {
        val vault = vault()
        val device = vault.prepare(KeyMode.DEVICE, null).slot
        val pw = vault.prepare(KeyMode.PASSWORD, password.copyOf()).slot
        val derivations = kdf.derivations().size

        val asPassword = KeySlot(KeyMode.PASSWORD, device.outerIv, device.wrappedDek, pw.innerIv, pw.kdf)
        val asDevice = KeySlot(KeyMode.DEVICE, pw.outerIv, pw.wrappedDek, null, null)

        assertEquals(UnlockResult.KeyFailed, vault.openKey(asPassword, password))
        assertEquals(UnlockResult.KeyFailed, vault.openKey(asDevice, null))
        assertEquals(derivations, kdf.derivations().size)
        assertEquals(0, settings.snapshot().unlockFailures)
    }

    @Test
    fun l05j_anExtraUnknownField_stillOpens() = runVaultTest {
        for (mode in encrypted) {
            val vault = vault()
            val prepared = vault.install(mode)
            editState { put("addedByANewerApp", JSONObject().put("x", 1)); slot().put("hint", "none") }

            assertArrayEquals(prepared.rawKey, (vault.unlockAs(mode) as UnlockResult.Key).rawKey)
        }
    }

    @Test
    fun l05k_oneTransientKeyStoreException_isRetriedAfterOneSecond_andOpens() = runVaultTest {
        for (mode in encrypted) {
            val vault = vault()
            val prepared = vault.install(mode)
            kek.failNext(KekOp.InitDecrypt, KeyStoreException("injected"))
            val start = currentTime

            val result = vault.unlockAs(mode)

            assertArrayEquals("$mode", prepared.rawKey, (result as UnlockResult.Key).rawKey)
            assertEquals("$mode: retried after 1 s", 1_000L, currentTime - start)
        }
    }

    @Test
    fun l05l_flippedInnerIvSaltOrIterations_failTheOuterTag_andCountNothing() = runVaultTest {
        val damages = listOf<JSONObject.() -> Unit>(
            { flipByte(slot(), "innerIv") },
            { flipByte(slot().getJSONObject("kdf"), "salt") },
            { slot().getJSONObject("kdf").put("iterations", slot().getJSONObject("kdf").getInt("iterations") + 10_000) },
        )
        for (damage in damages) {
            val vault = vault()
            vault.install(KeyMode.PASSWORD)
            settings.setBackoff(failures = 2, retryAtElapsedMs = 0)
            editState(damage)
            val derivations = kdf.derivations().size

            assertEquals(UnlockResult.KeyFailed, vault.unlock(password))
            assertEquals(UnlockResult.KeyFailed, vault.verifyPassword(password))

            assertEquals("no PBKDF2", derivations, kdf.derivations().size)
            assertEquals("not counted", 2, settings.snapshot().unlockFailures)
        }
    }

    @Test
    fun l05m_illegalBlockSizeFromTheOuterCipher_isKeyFailed_afterOneRetry() = runVaultTest {
        assertKeyFailed(readable = true) {
            repeat(2) {
                kek.failNext(KekOp.DoFinal, IllegalBlockSizeException("injected").apply { initCause(KeyStoreException("ks")) })
            }
        }
    }

    @Test
    fun tagFailureOfTheOuterLayer_isNeverRetried() = runVaultTest {
        val vault = vault()
        vault.install(KeyMode.DEVICE)
        kek.failNext(KekOp.DoFinal, AEADBadTagException("injected"))
        val decrypts = kek.calls(KekOp.InitDecrypt)
        val start = currentTime

        assertEquals(UnlockResult.KeyFailed, vault.unlock(null))

        assertEquals(1, kek.calls(KekOp.InitDecrypt) - decrypts)
        assertEquals(0L, currentTime - start)
    }

    @Test
    fun l05n_aMalformedBioSlot_leavesThePasswordRoute() = runVaultTest {
        val vault = vault()
        val prepared = vault.install(KeyMode.PASSWORD)
        editState { put("bioSlot", JSONObject().put("keyAlias", TEST_SLOT_KEY_ALIAS).put("iv", "*").put("wrappedDek", "x")) }

        val state = requireNotNull(vault.readState())

        assertNull(state.bioSlot)
        assertTrue(state.deadBioSlot)
        assertArrayEquals(prepared.rawKey, (vault.unlock(password) as UnlockResult.Key).rawKey)
    }
}

package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.testing.GateTest
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate half of L-05 (KeyUnavailable, DEC-86): never "wrong password", never `Corrupt`, the files and counters
 * untouched; and (n) a malformed biometric slot never makes the key-state unavailable (security.md § 3.5). The lock
 * route over it is `KeyUnavailableLockTest` (L-44).
 */
@RunWith(AndroidJUnit4::class)
class KeyUnavailableTest : GateTest() {
    private suspend fun snapshot(): Pair<ByteArray, ByteArray> = scene.liveFile().readBytes() to vaults.stateFile.readBytes()

    @Test
    fun a_deviceMode_kekDeleted_isKeyUnavailable_withTheFilesAndCountersUntouched() = runBlocking {
        scene.plant(KeyMode.DEVICE)
        val (live, state) = snapshot()
        vaults.deleteKekAlias()
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable)
        assertArrayEquals(live, scene.liveFile().readBytes())
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(0, scene.settings.snapshot().unlockFailures)
    }

    @Test
    fun a_passwordMode_kekDeleted_withoutASlot_isKeyUnavailableAfterThePassword_neverAWrongPassword() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD)
        vaults.deleteKekAlias()
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.KeyUnavailable) // T23
        assertEquals(0, scene.settings.snapshot().unlockFailures)
    }

    @Test
    fun b_keyStateMissing_nextToEncryptedData_isKeyUnavailable_andNothingIsDeleted() = runBlocking {
        scene.plant(KeyMode.DEVICE)
        val live = scene.liveFile()
        val bytes = live.readBytes()
        assertTrue(vaults.stateFile.delete())
        scene.coldStart()
        scene.awaitGate(GateState.KeyUnavailable)
        assertArrayEquals(bytes, live.readBytes())
    }

    @Test
    fun n_passwordMode_bioSlotWithACharacterOutsideBase64_isThePasswordScreen() = runBlocking {
        passwordModeLockOn_malformedBioSlot_opensWithThePassword { put("iv", "*" + getString("iv").drop(1)) }
    }

    @Test
    fun n_passwordMode_bioSlotOfTheWrongLength_isThePasswordScreen() = runBlocking {
        passwordModeLockOn_malformedBioSlot_opensWithThePassword { put("wrappedDek", base64(47)) }
    }

    @Test
    fun n_passwordMode_bioSlotWithAnotherKeyAlias_isThePasswordScreen() = runBlocking {
        passwordModeLockOn_malformedBioSlot_opensWithThePassword { put("keyAlias", "doro_bio_v2") }
    }

    @Test
    fun n_deviceMode_withABioSlot_opensThroughTheSlotlessLock() = runBlocking {
        scene.plant(KeyMode.DEVICE, lock = true)
        val generation = checkNotNull(app.container.keyVault.readState()).generation
        editState { put("bioSlot", JSONObject().put("keyAlias", TEST_SLOT_KEY_ALIAS).put("iv", base64(12)).put("wrappedDek", base64(48))) }
        scene.gateway.succeed()
        scene.coldStart()
        assertEquals(GateState.Ready, scene.awaitGate { it == GateState.Ready || it == GateState.KeyUnavailable })
        assertFalse("the device-mode lock prompt has no CryptoObject", scene.gateway.prompts.single().withCrypto)
        assertEquals(generation, checkNotNull(app.container.keyVault.readState()).generation)
    }

    /**
     * L-05 (n) at the gate: `PASSWORD` mode with the lock on and a live slot, then the slot's JSON edited by [malform].
     * The parser drops the slot (`deadBioSlot`, cause `SlotMissing`), so the cold start shows the password screen with
     * reason none, never `KeyUnavailable`; the database is untouched and the password opens it without a failure.
     */
    private suspend fun passwordModeLockOn_malformedBioSlot_opensWithThePassword(malform: JSONObject.() -> Unit) {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        editState { getJSONObject("bioSlot").malform() }
        val generation = checkNotNull(app.container.keyVault.readState()).generation
        val live = scene.liveFile().readBytes()
        scene.coldStart()
        val state = scene.awaitGate { it is GateState.NeedsPassword || it == GateState.KeyUnavailable || it is GateState.Locked }
        assertTrue("the password screen", state is GateState.NeedsPassword)
        assertEquals(PasswordReason.None, (state as GateState.NeedsPassword).reason)
        assertArrayEquals(live, scene.liveFile().readBytes())
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        assertEquals(generation, checkNotNull(app.container.keyVault.readState()).generation)
        assertEquals(0, scene.settings.snapshot().unlockFailures)
        assertEquals("no slot prompt", 0, scene.gateway.prompts.count { it.withCrypto })
    }

    private fun editState(edit: JSONObject.() -> Unit) =
        vaults.stateFile.writeText(JSONObject(vaults.stateFile.readText()).apply(edit).toString())

    private fun base64(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { it.toByte() })

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

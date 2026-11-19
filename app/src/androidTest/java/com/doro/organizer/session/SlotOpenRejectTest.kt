package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.security.sqlcipherRawKey
import com.doro.organizer.testing.GateTest
import com.doro.organizer.testing.ScriptedVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-47 (a): a key released by the slot that the database file rejects at cold start is never `Corrupt` (DEC-60,
 * DEC-85): the alias is deleted and the password is asked with `FingerprintChanged`, without counting.
 */
@RunWith(AndroidJUnit4::class)
class SlotOpenRejectTest : GateTest() {
    @Test
    fun keyRejectedThroughTheSlot_asksThePassword_deletesTheAlias_andCountsNothing() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.wrapVault = { real ->
            ScriptedVault(real).apply { nextBiometricUnlock = UnlockResult.Key(sqlcipherRawKey(ByteArray(32) { 7 }, ::ByteArray)) }
        }
        val failures = scene.settings.snapshot().unlockFailures
        scene.gateway.succeed()
        scene.coldStart()
        val state = scene.awaitGate { it is GateState.NeedsPassword || it == GateState.Corrupt }
        assertNotEquals(GateState.Corrupt, state)
        assertEquals(PasswordReason.FingerprintChanged, (state as GateState.NeedsPassword).reason)
        assertFalse(vaults.bioKeyExists())
        assertEquals(failures, scene.settings.snapshot().unlockFailures)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

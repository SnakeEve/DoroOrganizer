package com.doro.organizer.session

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.StartupCompletion
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.testing.LockFixture
import com.doro.organizer.testing.unused
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 after T55: an `Opening(viaReset)` that ends before the open still spends, and so zeroes, the kept key (V16). */
class GateEffectsResetKeyTest {
    /** The kept key of `DefaultDbMaintenance.takeResetKey`: handed over for its generation, zeroed for any other. */
    private class KeptResetKey(private val completion: StartupCompletion, val key: ByteArray) : DbMaintenance by unused() {
        var kept: ByteArray? = key

        override suspend fun completeStartup() = completion

        override fun takeResetKey(generation: Long): ByteArray? {
            val taken = kept.also { kept = null }
            if (generation == GENERATION) return taken
            taken?.fill(0)
            return null
        }
    }

    @Test
    fun anE2EndingBeforeTheOpen_zeroesTheResetKey() = runTest {
        val cases = listOf(
            Triple(StartupCompletion.Transient, false, GateEvent.OpenFailed(OpenFailureKind.Transient)),
            Triple(StartupCompletion.KeyUnavailable, false, GateEvent.KeyFailed),
            Triple(StartupCompletion.Done, true, GateEvent.KeyFailed), // the key-state cannot be read
        )
        for ((completion, unreadable, expected) in cases) {
            val f = LockFixture(this)
            f.vault.commitMode(KeyMode.PASSWORD, generation = GENERATION)
            if (unreadable) f.vault.readFailure = KeyVaultException(KeyFailure.InvalidState)
            val maintenance = KeptResetKey(completion, ByteArray(67) { 7 })
            val effects = GateEffects(unused(), maintenance, f.vault, f.controller, f.settings, f.time)
            assertEquals("$completion", expected, effects.unlockAndOpen(password = null, cipher = null, resetKey = true))
            assertEquals(null, maintenance.kept)
            assertTrue("$completion", maintenance.key.all { it == 0.toByte() })
        }
    }

    private companion object {
        const val GENERATION = 4L
    }
}

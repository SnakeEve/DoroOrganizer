package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.OpenResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.seed
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DbMaintenance mechanics of testing.md L-34 (DEC-61, DEC-65, db-lifecycle.md § Key changes steps 10 and 13): the
 * seven flows of L-06 with the lock on and a live biometric slot. Every commit writes `bio = null` and runs
 * `DELETE_BIO_KEY`, so no slot from before a change survives it; a change that fails before its commit keeps the slot.
 * The re-creation offer (E5) and its screens are the gate's and Compose's halves of L-34.
 */
@RunWith(AndroidJUnit4::class)
class BiometricSlotKeyChangeTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runSlotTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    /** A seeded database with the biometric lock on; returns its content. */
    private suspend fun seededWithLockOn(): Map<String, List<List<String?>>> {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 13, notes = 60).generate())
        setup.settings.setBiometricLock(true)
        return setup.dump()
    }

    /** Arms a live slot from the live key, as the lock-on flow or the re-creation offer does; returns an unlock cipher. */
    private suspend fun armedSlot(): SlotCipher {
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        assertEquals(BioSlotStatus.Live, setup.vault.biometricSlotStatus())
        return setup.unlockCipher()
    }

    @Test
    fun theSevenFlows_dropTheSlotAndItsKey_andNoEarlierCipherReleasesAKey() = runSlotTest {
        val content = seededWithLockOn()
        for (flow in FLOWS) {
            val from = setup.state()
            // Only PASSWORD mode has a slot: the flows that start there run with a live one.
            val earlier = if (from.slot.mode == KeyMode.PASSWORD) armedSlot() else null

            setup.changeKey(flow.target, flow.current, flow.new)

            val after = setup.state()
            val case = "${from.slot.mode} -> ${flow.target}"
            assertEquals(case, from.generation + 1, after.generation)
            assertNull(case, after.bioSlot)
            assertFalse(case, after.deadBioSlot)
            assertFalse(case, vaults.bioKeyExists())
            assertEquals(case, BioSlotStatus.None, setup.vault.biometricSlotStatus())
            // A cold start of the new generation finds no fingerprint route.
            assertEquals(case, SlotCipherResult.NoSlot, setup.vault.biometricUnlockCipher())
            if (earlier != null) {
                val released = setup.vault.unlockWithBiometric(earlier)
                assertFalse(case, released is UnlockResult.Key)
            }
            assertTrue(case, setup.settings.snapshot().biometricLock) // the lock stays on
            assertEquals(case, content, setup.dump())
        }
    }

    @Test
    fun aKeyChangeFaultedBeforeItsCommit_keepsTheSlot_andTheFingerprintStillOpensTheOldGeneration() = runSlotTest {
        val content = seededWithLockOn()
        setup.changeKey(KeyMode.PASSWORD, new = P1)
        armedSlot()
        val state = setup.state()
        val stateBytes = vaults.stateFile.readBytes()
        setup.hook.throwAt(MaintenanceOp.KeyChange, PipelineStep.EXPORT, IOException("injected"))

        val result = setup.maintenance.changeKey(KeyMode.PASSWORD, P1.toCharArray(), P2.toCharArray())

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertArrayEquals(stateBytes, vaults.stateFile.readBytes())
        assertEquals(BioSlotStatus.Live, setup.vault.biometricSlotStatus())
        assertTrue(vaults.bioKeyExists())
        setup.hook.clear()
        // The next cold start opens the old generation with the fingerprint alone.
        setup.holder.close()
        setup.maintenance.reconcileAtStartup()
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
        val unlocked = setup.vault.unlockWithBiometric(setup.unlockCipher()) as UnlockResult.Key
        assertEquals(OpenResult.Opened, setup.holder.open(state.generation, unlocked.rawKey))
        assertEquals(content, setup.dump())
    }

    private class Flow(val target: KeyMode, val current: String?, val new: String?)

    private companion object {
        const val P1 = "first password 1"
        const val P2 = "second password 2"

        /** testing.md L-06, in its order. */
        val FLOWS = listOf(
            Flow(KeyMode.PASSWORD, current = null, new = P1),
            Flow(KeyMode.PLAIN, current = P1, new = null),
            Flow(KeyMode.DEVICE, current = null, new = null),
            Flow(KeyMode.PASSWORD, current = null, new = P1),
            Flow(KeyMode.PASSWORD, current = P1, new = P2),
            Flow(KeyMode.DEVICE, current = P2, new = null),
            Flow(KeyMode.PLAIN, current = null, new = null),
        )
    }
}

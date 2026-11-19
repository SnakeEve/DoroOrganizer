package com.doro.organizer.data.maintenance

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.debug.ReadBackFailingVault
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.BioOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.ScriptedVault
import com.doro.organizer.testing.TestDatabases
import java.io.IOException
import java.security.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slot-only key-state commits of db-lifecycle.md § Biometric slot commits (DEC-80; testing.md L-14 and L-30
 * maintenance parts): the same generation and key slot byte for byte, only the biometric slot added or removed;
 * creation under the operation mutex with the read-back and the one repair, removal key first and never `Busy`
 * (testing.md L-41).
 */
@RunWith(AndroidJUnit4::class)
class BiometricSlotCommitTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private var vault: ReadBackFailingVault? = null
    private var scripted: ScriptedVault? = null
    private val setup by lazy {
        MaintenanceSetup(databases, vaults, scope.scope, wrapVault = { real ->
            val readBack = ReadBackFailingVault(real) { KeyVaultException(KeyFailure.InvalidState) }.also { vault = it }
            ScriptedVault(readBack).also { scripted = it }
        })
    }

    private val commits get() = checkNotNull(scripted).commits

    private fun runSlotTest(body: suspend () -> Unit) = runTest(timeout = 120.seconds) { body() }

    private suspend fun passwordMode() {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = PASSWORD)
    }

    @Test
    fun creation_addsOnlyTheSlot_whichReleasesTheLiveKey() = runSlotTest {
        passwordMode()
        val before = setup.state()
        val settings = setup.settings.snapshot()
        val events = setup.listener.finished.size

        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))

        val after = setup.state()
        assertEquals(before.generation, after.generation)
        assertTrue(after.slot.sameAs(before.slot))
        assertNotNull(after.bioSlot)
        assertEquals(BioSlotStatus.Live, setup.vault.biometricSlotStatus())
        val released = setup.vault.unlockWithBiometric(setup.unlockCipher()) as UnlockResult.Key
        assertArrayEquals(setup.holder.copyLiveKey(), released.rawKey)
        assertEquals(settings, setup.settings.snapshot()) // backoff and passwordVerifiedAt untouched
        assertEquals(events, setup.listener.finished.size) // no Maintenance state, no gate event
    }

    @Test
    fun creation_outsidePasswordMode_writesNothing_andDeletesThePromptsKey() = runSlotTest {
        passwordMode()
        val cipher = setup.armCipher()
        setup.changeKey(KeyMode.DEVICE, current = PASSWORD) // a stale prompt after a key change
        val state = vaults.stateFile.readBytes()

        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(cipher))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun creation_withRoomClosed_isDatabaseUnavailable_andDeletesThePromptsKey() = runSlotTest {
        passwordMode()
        val cipher = setup.armCipher()
        setup.holder.close()
        val state = vaults.stateFile.readBytes()

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), setup.maintenance.createBiometricSlot(cipher))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun creation_whileAnOperationRuns_isBusy() = runSlotTest {
        passwordMode()
        setup.hook.blockAt(MaintenanceOp.Backup, PipelineStep.EXPORT)
        coroutineScope {
            val backup = async { setup.maintenance.backupForShare(BACKUP_PASSWORD.toCharArray()) }
            setup.hook.blocked.await()

            assertEquals(AppResult.Err(AppError.Busy), setup.maintenance.createBiometricSlot(setup.armCipher()))

            setup.hook.release()
            backup.await()
        }
        assertNull(setup.state().bioSlot)
    }

    @Test
    fun aHookFailureDuringCreation_isUnexpected_leavesTheKeyStateByteIdentical_andDeletesThePromptsKey() = runSlotTest {
        passwordMode()
        for (step in listOf(PipelineStep.PREPARE_BIO_SLOT, PipelineStep.COMMIT_BIO_SLOT)) {
            val state = vaults.stateFile.readBytes()
            setup.hook.throwAt(null, step, IOException("injected"))

            assertEquals("$step", AppResult.Err(AppError.Unexpected), setup.maintenance.createBiometricSlot(setup.armCipher()))

            assertArrayEquals("$step", state, vaults.stateFile.readBytes())
            assertFalse("$step", vaults.bioKeyExists())
            setup.hook.clear()
        }
    }

    @Test
    fun anUnknownReadBack_repairsTheFileWithoutTheSlot_andDeletesTheKey() = runSlotTest {
        passwordMode()
        val before = setup.state()
        checkNotNull(vault).failReadAfterNextCommit = true

        assertEquals(AppResult.Err(AppError.OutcomeUnknown), setup.maintenance.createBiometricSlot(setup.armCipher()))

        val after = setup.state()
        assertEquals(before.generation, after.generation)
        assertTrue(after.slot.sameAs(before.slot))
        assertNull(after.bioSlot)
        assertFalse(vaults.bioKeyExists())
        assertTrue(setup.holder.copyLiveKey() != null) // the database stays open, nothing restarts
    }

    @Test
    fun removal_deletesTheKey_thenRewritesTheFileWithoutTheSlot() = runSlotTest {
        passwordMode()
        setup.maintenance.createBiometricSlot(setup.armCipher())
        val before = setup.state()

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertFalse(vaults.bioKeyExists())
        val after = setup.state()
        assertEquals(before.generation, after.generation)
        assertTrue(after.slot.sameAs(before.slot))
        assertNull(after.bioSlot)
        assertFalse(after.deadBioSlot)
    }

    @Test
    fun removal_whileAnOperationHoldsTheMutex_deletesOnlyTheKey_andLeavesADeadSlot() = runSlotTest {
        passwordMode()
        setup.maintenance.createBiometricSlot(setup.armCipher())
        setup.hook.blockAt(MaintenanceOp.Backup, PipelineStep.EXPORT)
        coroutineScope {
            val backup = async { setup.maintenance.backupForShare(BACKUP_PASSWORD.toCharArray()) }
            setup.hook.blocked.await()
            val state = vaults.stateFile.readBytes()

            assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

            assertArrayEquals(state, vaults.stateFile.readBytes())
            assertFalse(vaults.bioKeyExists())
            assertEquals(BioSlotStatus.Dead, setup.vault.biometricSlotStatus())
            setup.hook.release()
            backup.await()
        }
        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot()) // the next removal drops it
        assertNull(setup.state().bioSlot)
    }

    @Test
    fun aHookFailureDuringRemoval_isStillOk_andTheRemovalContinues() = runSlotTest {
        passwordMode()
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        setup.hook.throwAt(null, PipelineStep.DELETE_BIO_KEY, IOException("injected"))

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertTrue(vaults.bioKeyExists()) // the failed delete is only logged
        assertNull(setup.state().bioSlot) // and the file is rewritten without the slot all the same
        assertFalse(setup.state().deadBioSlot)
        setup.hook.clear()
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        setup.hook.throwAt(null, PipelineStep.COMMIT_BIO_SLOT, IOException("injected"))

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertFalse(vaults.bioKeyExists())
        assertEquals(BioSlotStatus.Dead, setup.vault.biometricSlotStatus()) // the dead slot stays in the file
    }

    @Test
    fun creation_whenTheWrapFails_isAuthFailed_writesNothing_andLeavesNoKey() = runSlotTest {
        passwordMode()
        val cipher = setup.armCipher()
        setup.bio.failNext(BioOp.DoFinal, ProviderException("injected"))
        val state = vaults.stateFile.readBytes()

        assertEquals(AppResult.Err(AppError.AuthFailed), setup.maintenance.createBiometricSlot(cipher))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(0, commits)
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun creation_withACipherPreparedForAnotherGeneration_isAuthFailed_andWritesNothing() = runSlotTest {
        passwordMode()
        val cipher = setup.armCipher()
        setup.changeKey(KeyMode.PASSWORD, current = PASSWORD, new = OTHER_PASSWORD)
        val state = vaults.stateFile.readBytes()
        val committed = commits

        assertEquals(AppResult.Err(AppError.AuthFailed), setup.maintenance.createBiometricSlot(cipher))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(committed, commits)
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun creation_whoseWriteFindsNoSpace_isStorageFull_andLeavesTheFileAndNoKey() = runSlotTest {
        passwordMode()
        val cipher = setup.armCipher()
        val state = vaults.stateFile.readBytes()
        checkNotNull(scripted).failNextCommit = IOException("injected", ErrnoException("write", OsConstants.ENOSPC))

        assertEquals(AppResult.Err(AppError.StorageFull), setup.maintenance.createBiometricSlot(cipher))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun removal_withAMissingKeyState_writesNothing() = runSlotTest {
        passwordMode()
        setup.maintenance.createBiometricSlot(setup.armCipher())
        assertTrue(vaults.stateFile.delete())
        val committed = commits

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertFalse(vaults.stateFile.exists())
        assertEquals(committed, commits)
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun removal_withAnInvalidKeyState_writesNothing() = runSlotTest {
        passwordMode()
        setup.maintenance.createBiometricSlot(setup.armCipher())
        vaults.stateFile.writeText("{\"formatVersion\":")
        val state = vaults.stateFile.readBytes()
        val committed = commits

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(committed, commits)
        assertFalse(vaults.bioKeyExists())
    }

    @Test
    fun removal_withoutABiometricSlot_writesNothing() = runSlotTest {
        passwordMode()
        val state = vaults.stateFile.readBytes()
        val committed = commits

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(committed, commits)
    }

    @Test
    fun removal_ofAMalformedBiometricSlot_rewritesTheFileWithoutIt() = runSlotTest {
        passwordMode()
        setup.maintenance.createBiometricSlot(setup.armCipher())
        val json = JSONObject(vaults.stateFile.readText())
        json.getJSONObject("bioSlot").put("keyAlias", "doro_bio_v2")
        vaults.stateFile.writeText(json.toString())
        val before = setup.state()
        assertTrue(before.deadBioSlot) // the edit took effect
        val committed = commits

        assertEquals(AppResult.Ok(Unit), setup.maintenance.removeBiometricSlot())

        val after = setup.state()
        assertEquals(committed + 1, commits)
        assertEquals(before.generation, after.generation)
        assertTrue(after.slot.sameAs(before.slot))
        assertNull(after.bioSlot)
        assertFalse(after.deadBioSlot)
    }

    private companion object {
        const val PASSWORD = "startup password 1"
        const val OTHER_PASSWORD = "startup password 2"
        const val BACKUP_PASSWORD = "backup password 1"
    }
}

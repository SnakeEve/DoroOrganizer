package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.passwordBytesOf
import com.doro.organizer.testing.seed
import java.io.IOException
import java.io.RandomAccessFile
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
 * The fingerprint password reset (DEC-63, db-lifecycle.md § Fingerprint password reset, security.md § 4.3): a
 * `PASSWORD` → `PASSWORD` key change authorized by the biometric slot, from `NeedsPassword` (Room closed, the
 * slot-released key is the export source, the committed key waits for E2) and from `Ready` (Room open); failures
 * before the commit end `Aborted` with the old password and slot in force (testing.md L-14 for its step rows).
 */
@RunWith(AndroidJUnit4::class)
class FingerprintResetTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runResetTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    /** PASSWORD mode with OLD, a live biometric slot armed from the live key, and some notes. */
    private suspend fun passwordModeWithSlot(): Map<String, List<List<String?>>> {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 5, notes = 40).generate())
        setup.changeKey(KeyMode.PASSWORD, new = OLD)
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        return setup.dump()
    }

    @Test
    fun fromNeedsPassword_commitsTheNewPassword_andKeepsTheKeyOnlyForItsGeneration() = runResetTest {
        passwordModeWithSlot()
        val before = setup.state()
        setup.holder.close() // the cold-start password screen: Room was never opened

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.NeedsPassword, setup.unlockCipher(), NEW.toCharArray())

        assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.PASSWORD)), result)
        assertEquals(listOf(MaintenanceOp.KeyChange to true), setup.listener.started.takeLast(1))
        assertEquals(MaintenanceOutcome.OpenWithNewKey, setup.listener.finished.last())
        val after = setup.state()
        assertEquals(before.generation + 1, after.generation)
        assertNull(after.bioSlot)
        assertFalse(vaults.bioKeyExists())
        assertEquals(setOf(after.generation), setup.files.generationsOnDisk().keys)
        assertNull(setup.maintenance.takeResetKey(after.generation + 1)) // another generation: zeroed and dropped
        assertNull(setup.maintenance.takeResetKey(after.generation)) // so nothing is left for any generation
    }

    @Test
    fun fromNeedsPassword_theKeptKeyOpensTheNewGeneration_andOnlyTheNewPasswordUnlocks() = runResetTest {
        val content = passwordModeWithSlot()
        setup.holder.close()

        setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.NeedsPassword, setup.unlockCipher(), NEW.toCharArray())

        val state = setup.state()
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
        val key = checkNotNull(setup.maintenance.takeResetKey(state.generation))
        assertEquals(OpenResult.Opened, setup.holder.open(state.generation, key))
        assertEquals(content, setup.dump())
        assertNull(setup.maintenance.takeResetKey(state.generation)) // handed over once
        setup.holder.close()
        assertTrue(setup.vault.unlock(passwordBytesOf(OLD)) is UnlockResult.PasswordRejected)
        setup.start(NEW)
    }

    @Test
    fun fromReady_reopensOnTheNewGeneration() = runResetTest {
        val content = passwordModeWithSlot()
        val before = setup.state()

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, setup.unlockCipher(), NEW.toCharArray())

        assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.PASSWORD)), result)
        assertEquals(MaintenanceOutcome.Reopened, setup.listener.finished.last())
        assertEquals(before.generation + 1, (setup.holder.state.value as HolderState.Open).generation)
        assertEquals(content, setup.dump())
        assertNull(setup.state().bioSlot)
        setup.restart(NEW)
    }

    @Test
    fun aPolicyViolation_isRejectedBeforeAnyFingerprintIsUsed() = runResetTest {
        passwordModeWithSlot()
        val cipher = setup.unlockCipher()
        val state = vaults.stateFile.readBytes()

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, cipher, "short".toCharArray())

        assertEquals(AppResult.Err(AppError.InvalidInput(InputRule.PasswordTooShort)), result)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertTrue(setup.vault.unlockWithBiometric(cipher) is UnlockResult.Key) // the cipher was never used
    }

    @Test
    fun withoutASlot_isAuthFailed_beforeMaintenanceStarted() = runResetTest {
        passwordModeWithSlot()
        val cipher = setup.unlockCipher()
        setup.maintenance.removeBiometricSlot()
        val started = setup.listener.started.size

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, cipher, NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.AuthFailed), result)
        assertEquals(started, setup.listener.started.size)
    }

    @Test
    fun fromNeedsPassword_everyStepUpToTheCommitFails_withTheOldPasswordAndSlotInForce() = runResetTest {
        passwordModeWithSlot()
        setup.holder.close()
        val steps = listOf(
            PipelineStep.UNLOCK_BIOMETRIC, PipelineStep.PRECHECK, PipelineStep.PREPARE_KEY, PipelineStep.CHECK_SOURCE,
            PipelineStep.EXPORT, PipelineStep.VERIFY_TARGET, PipelineStep.RENAME, PipelineStep.COMMIT,
        )
        for (step in steps) for (error in listOf(IOException("injected"), SQLiteFullException("injected"))) {
            val state = vaults.stateFile.readBytes()
            val generations = setup.files.generationsOnDisk().keys
            val finished = setup.listener.finished.size
            setup.hook.throwAt(MaintenanceOp.KeyChange, step, error)

            val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.NeedsPassword, setup.unlockCipher(), NEW.toCharArray())

            val expected = when {
                error is SQLiteFullException -> AppError.StorageFull
                step == PipelineStep.RENAME || step == PipelineStep.COMMIT -> AppError.Unexpected
                else -> AppError.DatabaseUnavailable
            }
            assertEquals("$step $error", AppResult.Err(expected), result)
            assertArrayEquals("$step", state, vaults.stateFile.readBytes())
            assertEquals("$step", generations, setup.files.generationsOnDisk().keys)
            assertEquals("$step", BioSlotStatus.Live, setup.vault.biometricSlotStatus())
            val started = step != PipelineStep.UNLOCK_BIOMETRIC && step != PipelineStep.PRECHECK
            assertEquals("$step", finished + if (started) 1 else 0, setup.listener.finished.size)
            if (started) assertEquals("$step", MaintenanceOutcome.Aborted, setup.listener.finished.last())
            setup.hook.clear()
        }
        setup.start(OLD)
    }

    @Test
    fun fromReady_everyStepUpToTheCommitFails_isAborted_withRoomReopenedOnTheOldGeneration() = runResetTest {
        val content = passwordModeWithSlot()
        val generation = setup.state().generation
        val steps = listOf(
            PipelineStep.UNLOCK_BIOMETRIC, PipelineStep.PRECHECK, PipelineStep.PREPARE_KEY, PipelineStep.CLOSE_ROOM,
            PipelineStep.CHECK_SOURCE, PipelineStep.EXPORT, PipelineStep.VERIFY_TARGET, PipelineStep.RENAME,
            PipelineStep.COMMIT,
        )
        for (step in steps) for (error in listOf(IOException("injected"), SQLiteFullException("injected"))) {
            val state = vaults.stateFile.readBytes()
            val finished = setup.listener.finished.size
            setup.hook.throwAt(MaintenanceOp.KeyChange, step, error)

            val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, setup.unlockCipher(), NEW.toCharArray())

            val expected = when {
                error is SQLiteFullException -> AppError.StorageFull
                step == PipelineStep.RENAME || step == PipelineStep.COMMIT -> AppError.Unexpected
                else -> AppError.DatabaseUnavailable
            }
            assertEquals("$step $error", AppResult.Err(expected), result)
            assertArrayEquals("$step", state, vaults.stateFile.readBytes())
            assertEquals("$step", generation, (setup.holder.state.value as HolderState.Open).generation)
            assertEquals("$step", setOf(generation), setup.files.generationsOnDisk().keys)
            assertEquals("$step", content, setup.dump())
            assertEquals("$step", BioSlotStatus.Live, setup.vault.biometricSlotStatus())
            val started = step != PipelineStep.UNLOCK_BIOMETRIC && step != PipelineStep.PRECHECK
            assertEquals("$step", finished + if (started) 1 else 0, setup.listener.finished.size)
            if (started) assertEquals("$step", MaintenanceOutcome.Aborted, setup.listener.finished.last())
            setup.hook.clear()
        }
        setup.restart(OLD)
    }

    @Test
    fun fromNeedsPassword_anEmptyLiveFile_isDatabaseUnavailable_andChangesNothing() = runResetTest {
        passwordModeWithSlot()
        setup.holder.close()
        val live = setup.files.live(setup.state().generation)
        RandomAccessFile(live, "rw").use { it.setLength(0) }
        val state = vaults.stateFile.readBytes()

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.NeedsPassword, setup.unlockCipher(), NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertEquals(MaintenanceOutcome.Aborted, setup.listener.finished.last())
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(0L, live.length())
        assertEquals(BioSlotStatus.Live, setup.vault.biometricSlotStatus())
    }

    private companion object {
        const val OLD = "forgotten password"
        const val NEW = "the new password"
    }
}

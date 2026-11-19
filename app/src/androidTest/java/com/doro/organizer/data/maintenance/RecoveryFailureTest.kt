package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.ExclusiveScope
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.Reopen
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.debug.ReadBackFailingVault
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestDocument
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.enterCorrupt
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import com.doro.organizer.testing.seed
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-14 for Restore, Start fresh and Erase (db-lifecycle.md § Pipeline steps and fault hook): every step of
 * every step-table row (one per operation and origin) fails once with an `IOException` and once with a
 * `SQLiteFullException`. Up to `COMMIT` (Erase: up to `ERASE_KEY_STATE`) the previous state stays in force, keys
 * included; after it the commit stands and the next start finishes what the failed step left. Also the restore's two
 * key-source guards, the `Unknown` commit outcome and a holder that closes behind a restore from `Ready`. The T12
 * operations' sweeps are `MaintenanceFailureTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryFailureTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var vault: ReadBackFailingVault? = null
    private var copiedKey: (() -> ByteArray?)? = null
    private var closeBeforeExclusive = false
    private val setup by lazy {
        MaintenanceSetup(
            databases, vaults, scope.scope,
            wrapVault = { real -> ReadBackFailingVault(real) { KeyVaultException(KeyFailure.InvalidState) }.also { vault = it } },
            wrapHolder = { real -> ScriptedHolder(real) },
        )
    }

    private fun runFailureTest(body: suspend () -> Unit) = runTest(timeout = 600.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun aRestoreFromReady_failingUpToItsCommit_rollsBack_andAfterItTheCommitStands() = runFailureTest {
        val backup = seededBackup()
        backupThenChangeAgain()
        assertEquals(RESTORE_FROM_READY, stepsOf(MaintenanceOp.Restore) { restore(backup, MaintenanceOrigin.Ready) })
        val afterBackup = setup.dump()
        backupThenChangeAgain()
        sweepUpToCommit(MaintenanceOp.Restore, RESTORE_FROM_READY, MaintenanceOutcome.Reopened) {
            restore(backup, MaintenanceOrigin.Ready)
        }
        for (step in afterCommit(RESTORE_FROM_READY)) {
            val before = setup.state()
            setup.hook.throwAt(MaintenanceOp.Restore, step, IOException("injected"))

            val result = restore(backup, MaintenanceOrigin.Ready)

            assertTrue("$step $result", (result as AppResult.Ok).value is MaintenanceResult.Restored)
            assertTrue("$step", setup.state().generation > before.generation)
            assertEquals("$step", MaintenanceOutcome.Reopened, setup.listener.finished.last())
            setup.hook.clear()
            setup.restart() // the next startup finishes what the failed step left, the data reset included
            assertEquals("$step", afterBackup, setup.dump())
            assertEquals("$step", setOf(setup.state().generation), setup.files.generationsOnDisk().keys)
            assertFalse("$step", setup.files.resetMarker.exists())
        }
    }

    @Test
    fun aRestoreFromCorrupt_failingUpToItsCommit_keepsTheDamagedFiles_andAfterItTheCommitStands() = runFailureTest {
        val backup = seededBackup(KeyMode.DEVICE)
        val enter: suspend () -> Unit = { setup.enterCorrupt() }
        enter()
        val run: suspend () -> AppResult<MaintenanceResult> = { restore(backup, MaintenanceOrigin.Corrupt) }
        assertEquals(RESTORE_FROM_CORRUPT, stepsOf(MaintenanceOp.Restore) { run() })
        setup.start()
        enter()
        sweepUpToCommit(MaintenanceOp.Restore, RESTORE_FROM_CORRUPT, MaintenanceOutcome.Aborted, run)
        sweepAfterCommit(MaintenanceOp.Restore, RESTORE_FROM_CORRUPT, enter, run) { it is MaintenanceResult.Restored }
    }

    @Test
    fun aRestoreFromKeyUnavailable_failingUpToItsCommit_keepsBothKeys_andAfterItTheCommitStands() = runFailureTest {
        val backup = seededBackup()
        val enter: suspend () -> Unit = { keyUnavailableWithBothKeys() }
        enter()
        val run: suspend () -> AppResult<MaintenanceResult> = { restore(backup, MaintenanceOrigin.KeyUnavailable) }
        assertEquals(RESTORE_FROM_KEY_UNAVAILABLE, stepsOf(MaintenanceOp.Restore) { run() })
        setup.start()
        enter()
        sweepUpToCommit(MaintenanceOp.Restore, RESTORE_FROM_KEY_UNAVAILABLE, MaintenanceOutcome.Aborted, run)
        sweepAfterCommit(MaintenanceOp.Restore, RESTORE_FROM_KEY_UNAVAILABLE, enter, run) { it is MaintenanceResult.Restored }
    }

    @Test
    fun aStartFreshFromCorrupt_failingUpToItsCommit_keepsTheDamagedFiles_andAfterItTheCommitStands() = runFailureTest {
        setup.start()
        setup.changeKey(KeyMode.DEVICE)
        val enter: suspend () -> Unit = { setup.enterCorrupt() }
        enter()
        val run: suspend () -> AppResult<MaintenanceResult> = { setup.maintenance.startFresh(MaintenanceOrigin.Corrupt) }
        sweepUpToCommit(MaintenanceOp.StartFresh, START_FRESH, MaintenanceOutcome.Aborted, run)
        sweepAfterCommit(MaintenanceOp.StartFresh, START_FRESH, enter, run) { it == MaintenanceResult.StartedFresh }
    }

    @Test
    fun aStartFreshFromKeyUnavailable_failingUpToItsCommit_keepsBothKeys_andAfterItTheCommitStands() = runFailureTest {
        setup.start()
        val enter: suspend () -> Unit = { keyUnavailableWithBothKeys() }
        enter()
        val run: suspend () -> AppResult<MaintenanceResult> = { setup.maintenance.startFresh(MaintenanceOrigin.KeyUnavailable) }
        sweepUpToCommit(MaintenanceOp.StartFresh, START_FRESH, MaintenanceOutcome.Aborted, run)
        sweepAfterCommit(MaintenanceOp.StartFresh, START_FRESH, enter, run) { it == MaintenanceResult.StartedFresh }
    }

    @Test
    fun anErase_failingBeforeTheKeyStateGoes_erasesNothing_andAfterItTheNextStartDecides() = runFailureTest {
        setup.start()
        for (origin in listOf(MaintenanceOrigin.NeedsPassword, MaintenanceOrigin.Ready, MaintenanceOrigin.Corrupt)) {
            val steps = if (origin == MaintenanceOrigin.Ready) ERASE_FROM_READY else ERASE_CLOSED
            enterErase(origin)
            assertEquals("$origin", steps, stepsOf(MaintenanceOp.Erase) { setup.maintenance.erase(origin) })
            restartAfterErase()
            enterErase(origin)
            val rollback = if (origin == MaintenanceOrigin.Ready) MaintenanceOutcome.Reopened else MaintenanceOutcome.Aborted
            for (step in steps.takeWhile { it != PipelineStep.ERASE_FILES }) for (error in errors()) {
                val case = "$origin $step $error"
                val state = vaults.stateFile.readBytes()
                val generations = setup.files.generationsOnDisk().keys
                setup.hook.throwAt(MaintenanceOp.Erase, step, error)

                val result = setup.maintenance.erase(origin)

                val expected = if (error is SQLiteFullException) AppError.StorageFull else AppError.DatabaseUnavailable
                assertEquals(case, AppResult.Err(expected), result)
                assertEquals(case, rollback, setup.listener.finished.last())
                assertArrayEquals(case, state, vaults.stateFile.readBytes())
                assertEquals(case, generations, setup.files.generationsOnDisk().keys)
                assertTrue(case, vaults.kekExists())
                assertEquals(case, origin == MaintenanceOrigin.Ready, setup.holder.state.value is HolderState.Open)
                setup.hook.clear()
            }
            for (error in errors()) {
                setup.hook.throwAt(MaintenanceOp.Erase, PipelineStep.ERASE_FILES, error)

                assertEquals("$origin $error", AppResult.Err(AppError.OutcomeUnknown), setup.maintenance.erase(origin))
                assertEquals("$origin $error", MaintenanceOutcome.Restart, setup.listener.finished.last())
                assertFalse("$origin $error", vaults.stateFile.exists())
                setup.hook.clear()
                setup.holder.close()
                // Crash-safety row CS-25: the encrypted files are left without a key-state.
                assertEquals("$origin $error", StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())
                assertEquals(AppResult.Ok(MaintenanceResult.Erased), setup.maintenance.erase(MaintenanceOrigin.NeedsPassword))
                restartAfterErase()
                enterErase(origin)
            }
            for (error in errors()) {
                setup.settings.setScreenshotProtection(true)
                setup.hook.throwAt(MaintenanceOp.Erase, PipelineStep.RESET_SETTINGS, error)

                assertEquals("$origin $error", AppResult.Ok(MaintenanceResult.Erased), setup.maintenance.erase(origin))
                assertTrue("$origin $error", setup.settings.snapshot().screenshotProtection)
                setup.hook.clear()
                // The first-run reset of reconciliation completes it.
                assertEquals("$origin $error", StartupDecision.FirstRun, setup.maintenance.reconcileAtStartup())
                assertFalse("$origin $error", setup.settings.snapshot().screenshotProtection)
                restartAfterErase()
                enterErase(origin)
            }
            restartAfterErase(erase = origin)
        }
    }

    @Test
    fun anUnknownRestoreCommit_deletesNothing_keepsTheMarker_andRestarts() = runFailureTest {
        val backup = seededBackup()
        backupThenChangeAgain()
        val before = setup.state()
        val epoch = setup.settings.snapshot().dataEpoch
        checkNotNull(vault).failReadAfterNextCommit = true

        val result = restore(backup, MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.OutcomeUnknown), result)
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertEquals(HolderState.Closed, setup.holder.state.value)
        assertTrue(setup.files.resetMarker.exists())
        assertEquals(setOf(before.generation, before.generation + 1), setup.files.generationsOnDisk().keys)
        setup.start() // the commit landed: the marker applies the reset, the old generation goes
        assertEquals(epoch + 1, setup.settings.snapshot().dataEpoch)
        assertEquals(setOf(before.generation + 1), setup.files.generationsOnDisk().keys)
    }

    @Test
    fun aLiveKeyCopyThatIsNotTheLiveKey_failsTheDigestCheck_beforeAnythingIsMarked() = runFailureTest {
        val backup = seededBackup()
        backupThenChangeAgain()
        val unchanged = snapshot()
        copiedKey = { TestKey.Raw(Random(9).nextBytes(32)).bytes() }

        val result = restore(backup, MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.Unexpected), result)
        assertFalse(setup.files.resetMarker.exists())
        assertPreviousStateInForce("digest", unchanged, started = true, MaintenanceOutcome.Reopened)
    }

    @Test
    fun aRestoreFromReady_whoseHolderClosesBeforeTheKeyCopy_continuesAsFromCorrupt() = runFailureTest {
        val backup = seededBackup()
        backupThenChangeAgain()
        val before = setup.state()
        setup.hook.blockAt(MaintenanceOp.Restore, PipelineStep.OPEN_INPUT)

        val result = coroutineScope {
            val restore = async { restore(backup, MaintenanceOrigin.Ready) }
            setup.hook.blocked.await()
            setup.holder.close() // CorruptAtRuntime: E3 closes Room behind the restore dialogs
            setup.hook.release()
            restore.await()
        }

        assertTrue(result.toString(), (result as AppResult.Ok).value is MaintenanceResult.Restored)
        val steps = setup.hook.steps(MaintenanceOp.Restore)
        assertTrue(PipelineStep.UNLOCK_DEVICE_KEY in steps)
        assertFalse(PipelineStep.CLOSE_ROOM in steps)
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertEquals(setOf(before.generation + 1), setup.files.generationsOnDisk().keys)
    }

    @Test
    fun aConfirmedRestoreFromReady_whoseHolderClosesBeforeExclusiveMode_commitsAsFromCorrupt() = runFailureTest {
        val backup = seededBackup()
        val atBackup = setup.dump()
        backupThenChangeAgain()
        val before = setup.state()
        closeBeforeExclusive = true

        val result = restore(backup, MaintenanceOrigin.Ready)

        assertTrue(result.toString(), (result as AppResult.Ok).value is MaintenanceResult.Restored)
        assertEquals(listOf(false), setup.listener.startedFor(MaintenanceOp.Restore))
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        val after = setup.state()
        assertTrue(after.slot.sameAs(before.slot)) // the export was keyed for the unchanged slot
        assertEquals(setOf(after.generation), setup.files.generationsOnDisk().keys)
        setup.start()
        assertEquals(atBackup, setup.dump())
    }

    /**
     * Lends the real holder, except that [copiedKey], when set, replaces what `copyLiveKey` returns, and that
     * [closeBeforeExclusive] makes E3 close Room right before exclusive mode begins.
     */
    private inner class ScriptedHolder(private val real: DatabaseHolder) : DatabaseHolder by real {
        override suspend fun copyLiveKey(): ByteArray? = copiedKey?.invoke() ?: real.copyLiveKey()

        override suspend fun exclusive(block: suspend (ExclusiveScope) -> Reopen): OpenResult? {
            if (closeBeforeExclusive) real.close()
            return real.exclusive(block)
        }
    }

    /**
     * Every step of [steps] before `DATA_RESET`, once with each error: the result the step table names, the key-state,
     * the generations and both keys as before, no marker, nothing staged, and [rollback] once `MaintenanceStarted` ran.
     */
    private suspend fun sweepUpToCommit(
        op: MaintenanceOp,
        steps: List<PipelineStep>,
        rollback: MaintenanceOutcome,
        run: suspend () -> AppResult<MaintenanceResult>,
    ) {
        val startedAfter = if (op == MaintenanceOp.Restore) PipelineStep.AWAIT_CONFIRMATION else PipelineStep.PRECHECK
        for (step in steps.takeWhile { it != PipelineStep.DATA_RESET }) for (error in errors()) {
            val case = "$op $step $error"
            val unchanged = snapshot()
            val keys = vaults.kekExists() to vaults.bioKeyExists()
            setup.hook.throwAt(op, step, error)

            val result = run()

            val expected = when {
                error is SQLiteFullException -> AppError.StorageFull
                step == PipelineStep.COPY_INPUT -> AppError.FileAccess
                step == PipelineStep.RENAME || step == PipelineStep.COMMIT -> AppError.Unexpected // NotCommitted
                else -> AppError.DatabaseUnavailable
            }
            assertEquals(case, AppResult.Err(expected), result)
            assertFalse(case, setup.files.resetMarker.exists())
            assertEquals(case, keys, vaults.kekExists() to vaults.bioKeyExists())
            val started = steps.indexOf(step) > steps.indexOf(startedAfter)
            assertPreviousStateInForce(case, unchanged, started, rollback)
        }
    }

    /**
     * Every step of [steps] from `DATA_RESET` on fails once: the commit stands ([committed] result, `Restart`, a new
     * generation), and the next start leaves only that generation; [enter] then returns to the origin.
     */
    private suspend fun sweepAfterCommit(
        op: MaintenanceOp,
        steps: List<PipelineStep>,
        enter: suspend () -> Unit,
        run: suspend () -> AppResult<MaintenanceResult>,
        committed: (MaintenanceResult) -> Boolean,
    ) {
        for (step in afterCommit(steps)) {
            val before = checkNotNull(setup.files.generationsOnDisk().keys.maxOrNull())
            setup.hook.throwAt(op, step, IOException("injected"))

            val result = run()

            assertTrue("$op $step $result", committed((result as AppResult.Ok).value))
            assertEquals("$op $step", MaintenanceOutcome.Restart, setup.listener.finished.last())
            assertTrue("$op $step", setup.state().generation > before)
            setup.hook.clear()
            setup.start()
            assertEquals("$op $step", setOf(setup.state().generation), setup.files.generationsOnDisk().keys)
            assertFalse("$op $step", setup.files.resetMarker.exists())
            enter()
        }
    }

    /** Seeded data in [mode] and a plain backup of it at the current schema version; Room stays open. */
    private suspend fun seededBackup(mode: KeyMode = KeyMode.PLAIN): TestDocument {
        setup.start()
        if (mode != KeyMode.PLAIN) setup.changeKey(mode)
        setup.holder.openDatabase().seed(SeedGenerator(seed = 51, notes = 20).generate())
        return setup.backupTo(context, "failure.db")
    }

    /** A change that a restore must undo. */
    private fun backupThenChangeAgain() =
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM notes WHERE id % 3 = 0")

    /**
     * From an open `PLAIN` start: `PASSWORD` mode with the lock on and a live biometric slot, so the KEK and the
     * biometric key exist, then Room closed and the key-state made invalid: KeyUnavailable (DEC-86).
     */
    private suspend fun keyUnavailableWithBothKeys() {
        setup.changeKey(KeyMode.PASSWORD, new = PASSWORD)
        setup.settings.setBiometricLock(true)
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        setup.holder.close()
        vaults.stateFile.writeText("{ not a key-state")
    }

    /** From an open start: `PASSWORD` mode, then the screen Erase is offered on for [origin]. */
    private suspend fun enterErase(origin: MaintenanceOrigin) {
        setup.changeKey(KeyMode.PASSWORD, new = PASSWORD)
        when (origin) {
            MaintenanceOrigin.NeedsPassword -> setup.holder.close()
            MaintenanceOrigin.Corrupt -> setup.enterCorrupt()
            else -> Unit
        }
    }

    /** After a finished Erase (or, with [erase], after one more from that origin): the first run opens anew. */
    private suspend fun restartAfterErase(erase: MaintenanceOrigin? = null) {
        if (erase != null) assertEquals(AppResult.Ok(MaintenanceResult.Erased), setup.maintenance.erase(erase))
        setup.start()
    }

    private suspend fun restore(backup: TestDocument, origin: MaintenanceOrigin) =
        setup.maintenance.restoreAnswering(backup.uri, origin, confirm().ifAsked())

    private suspend fun stepsOf(op: MaintenanceOp, run: suspend () -> Unit): List<PipelineStep> {
        val before = setup.hook.steps(op).size
        run()
        return setup.hook.steps(op).drop(before)
    }

    private class Snapshot(val state: ByteArray, val generations: Set<Long>, val content: Any?, val finished: Int)

    /** The key-state, the generations, the live content when Room is open, and the events so far. */
    private fun snapshot() = Snapshot(
        vaults.stateFile.readBytes(),
        setup.files.generationsOnDisk().keys,
        if (setup.holder.state.value is HolderState.Open) setup.dump() else null,
        setup.listener.finished.size,
    )

    private fun assertPreviousStateInForce(case: String, before: Snapshot, started: Boolean, outcome: MaintenanceOutcome) {
        assertArrayEquals(case, before.state, vaults.stateFile.readBytes())
        assertEquals(case, before.generations, setup.files.generationsOnDisk().keys)
        if (before.content != null) assertEquals(case, before.content, setup.dump())
        assertEquals(case, emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        assertEquals(case, emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        val finished = setup.listener.finished
        if (started) {
            assertEquals(case, before.finished + 1, finished.size)
            assertEquals(case, outcome, finished.last())
        } else {
            assertEquals(case, before.finished, finished.size)
        }
        setup.hook.clear()
        copiedKey = null
    }

    private fun errors(): List<Throwable> = listOf(IOException("injected"), SQLiteFullException("injected"))

    private fun afterCommit(steps: List<PipelineStep>) = steps.dropWhile { it != PipelineStep.DATA_RESET }

    private companion object {
        const val PASSWORD = "startup password 19"

        /** The checks before the confirmation of a backup at the current schema version. */
        val STAGE = listOf(
            PipelineStep.PRECHECK, PipelineStep.COPY_INPUT, PipelineStep.OPEN_INPUT, PipelineStep.VALIDATE_INPUT,
            PipelineStep.CHECK_SCHEMA,
        )
        val EXPORT_AND_CONFIRM = listOf(PipelineStep.EXPORT, PipelineStep.VERIFY_TARGET, PipelineStep.AWAIT_CONFIRMATION)
        val COMMIT = listOf(PipelineStep.MARK_RESET, PipelineStep.RENAME, PipelineStep.COMMIT, PipelineStep.DATA_RESET)

        // The step-table rows of db-lifecycle.md § Pipeline steps and fault hook.
        val RESTORE_FROM_READY = STAGE + EXPORT_AND_CONFIRM + PipelineStep.CLOSE_ROOM + COMMIT +
            listOf(PipelineStep.REOPEN, PipelineStep.DELETE_OLD, PipelineStep.CLEANUP)
        val RESTORE_FROM_CORRUPT = STAGE + PipelineStep.UNLOCK_DEVICE_KEY + EXPORT_AND_CONFIRM + COMMIT +
            listOf(PipelineStep.DELETE_OLD, PipelineStep.CLEANUP)
        val RESTORE_FROM_KEY_UNAVAILABLE = STAGE + EXPORT_AND_CONFIRM + COMMIT +
            listOf(PipelineStep.DELETE_OLD, PipelineStep.DELETE_KEK, PipelineStep.DELETE_BIO_KEY, PipelineStep.CLEANUP)
        val START_FRESH = listOf(PipelineStep.PRECHECK, PipelineStep.CREATE_EMPTY, PipelineStep.VERIFY_TARGET) + COMMIT +
            listOf(PipelineStep.DELETE_OLD, PipelineStep.DELETE_KEK, PipelineStep.DELETE_BIO_KEY, PipelineStep.CLEANUP)
        val ERASE_CLOSED = listOf(
            PipelineStep.DELETE_OLD, PipelineStep.ERASE_KEY_STATE, PipelineStep.ERASE_FILES, PipelineStep.RESET_SETTINGS,
        )
        val ERASE_FROM_READY = listOf(
            PipelineStep.DELETE_OLD, PipelineStep.CLOSE_ROOM, PipelineStep.ERASE_KEY_STATE, PipelineStep.ERASE_FILES,
            PipelineStep.RESET_SETTINGS,
        )
    }
}

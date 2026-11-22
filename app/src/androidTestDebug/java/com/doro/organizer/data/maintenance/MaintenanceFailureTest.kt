package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteFullException
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.HolderState
import com.doro.organizer.debug.ReadBackFailingVault
import com.doro.organizer.debug.RecordingDocumentsProvider
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KEK_ALIAS
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.maintenanceFiles
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.seed
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Date
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * testing.md L-14 (db-lifecycle.md § Pipeline steps and fault hook, § Failure classification) for the operations of
 * this pipeline: every step of each step-table row, once with an `IOException` and once with a
 * `SQLiteFullException`. Before the commit point the previous state stays in force (key-state byte-identical, the
 * live content equal, nothing staged) and the result is classified by context; after it the commit stands.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceFailureTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files by lazy { maintenanceFiles(databases, vaults, context.cacheDir) }
    private var vault: ReadBackFailingVault? = null
    private val setup by lazy {
        MaintenanceSetup(databases, vaults, scope.scope, files = files, wrapVault = { real ->
            ReadBackFailingVault(real) { KeyVaultException(KeyFailure.InvalidState) }.also { vault = it }
        })
    }

    private fun runFailureTest(body: suspend () -> Unit) = runTest(timeout = 300.seconds) { body() }

    @Before
    fun cleanDocuments() {
        RecordingDocumentsProvider.directory(context).deleteRecursively()
        RecordingDocumentsProvider.failWrites = false
        files.shareDir.deleteRecursively()
    }

    @After
    fun removeDocuments() = cleanDocuments()

    @Test
    fun backupToADocument_everyStepFails_withTheDocumentRule_andNothingElseChanges() = runFailureTest {
        seeded()
        for (step in DOCUMENT_STEPS) for (error in errors()) {
            val document = document("created.db").apply { file.writeBytes(ByteArray(0)) }
            val unchanged = snapshot()
            setup.hook.throwAt(MaintenanceOp.Backup, step, error)

            val result = setup.maintenance.backupToDocument(document.uri, null)

            val expected = when {
                error is SQLiteFullException -> AppError.StorageFull
                step == PipelineStep.WRITE_DOCUMENT -> AppError.FileAccess
                else -> AppError.DatabaseUnavailable
            }
            assertEquals("$step $error", AppResult.Err(expected), result)
            assertFalse("$step: the created document stays", document.file.exists())
            assertPreviousStateInForce("$step", unchanged, startedAt = step != PipelineStep.PRECHECK)
        }
    }

    @Test
    fun backupOverAnExistingDocument_keepsItBeforeTheStream_andLosesItAfter() = runFailureTest {
        seeded()
        val old = ByteArray(10_000) { 0x42 }
        for (step in DOCUMENT_STEPS.takeWhile { it != PipelineStep.CLEANUP }) {
            val document = document("existing.db").apply { file.writeBytes(old) }
            setup.hook.throwAt(MaintenanceOp.Backup, step, IOException("injected"))

            setup.maintenance.backupToDocument(document.uri, null)

            assertArrayEquals("$step", old, document.file.readBytes())
            assertFalse("$step", finished().previousContentLost)
        }
        setup.hook.clear()
        val document = document("existing.db").apply { file.writeBytes(old) }
        RecordingDocumentsProvider.failWrites = true

        val result = setup.maintenance.backupToDocument(document.uri, null)

        assertEquals(AppResult.Err(AppError.FileAccess), result)
        assertFalse(document.file.exists())
        assertTrue(finished().previousContentLost)
    }

    @Test
    fun backupForSharing_everyStepFails_andNothingElseChanges() = runFailureTest {
        seeded()
        val steps = listOf(
            PipelineStep.PRECHECK, PipelineStep.CLOSE_ROOM, PipelineStep.CHECK_SOURCE, PipelineStep.EXPORT,
            PipelineStep.REOPEN, PipelineStep.VERIFY_TARGET, PipelineStep.PUBLISH_SHARE, PipelineStep.CLEANUP,
        )
        assertEquals(steps, stepsOf(MaintenanceOp.Backup) { setup.maintenance.backupForShare(null) })
        for (step in steps) for (error in errors()) {
            val unchanged = snapshot()
            setup.hook.throwAt(MaintenanceOp.Backup, step, error)

            val result = setup.maintenance.backupForShare(null)

            val expected = if (error is SQLiteFullException) AppError.StorageFull else AppError.DatabaseUnavailable
            assertEquals("$step $error", AppResult.Err(expected), result)
            assertPreviousStateInForce("$step", unchanged, startedAt = step != PipelineStep.PRECHECK)
        }
    }

    @Test
    fun aKeyChange_failingUpToItsCommit_rollsBack_andAfterItTheCommitStands() = runFailureTest {
        seeded()
        val steps = stepsOf(MaintenanceOp.KeyChange) { setup.maintenance.changeKey(KeyMode.DEVICE, null, null) }
        assertEquals(KEY_CHANGE_STEPS, steps)
        setup.changeKey(KeyMode.PLAIN)
        for (step in KEY_CHANGE_STEPS.takeWhile { it != PipelineStep.REOPEN }) for (error in errors()) {
            val unchanged = snapshot()
            setup.hook.throwAt(MaintenanceOp.KeyChange, step, error)

            val result = setup.maintenance.changeKey(KeyMode.DEVICE, null, null)

            val expected = when {
                error is SQLiteFullException -> AppError.StorageFull
                step == PipelineStep.RENAME || step == PipelineStep.COMMIT -> AppError.Unexpected // NotCommitted
                else -> AppError.DatabaseUnavailable
            }
            assertEquals("$step $error", AppResult.Err(expected), result)
            assertPreviousStateInForce("$step", unchanged, startedAt = step != PipelineStep.PRECHECK)
        }
        // Alternating targets, so that DELETE_KEK, which only a change to PLAIN runs, comes second.
        val afterCommit = listOf(
            PipelineStep.REOPEN, PipelineStep.DELETE_KEK, PipelineStep.DELETE_OLD, PipelineStep.DELETE_BIO_KEY, PipelineStep.CLEANUP,
        )
        for (step in afterCommit) {
            val before = setup.state()
            val content = setup.dump()
            val target = if (before.slot.mode == KeyMode.PLAIN) KeyMode.DEVICE else KeyMode.PLAIN
            setup.hook.throwAt(MaintenanceOp.KeyChange, step, IOException("injected"))

            val result = setup.maintenance.changeKey(target, null, null)

            assertEquals("$step", AppResult.Ok(MaintenanceResult.KeyChanged(before.slot.mode, target)), result)
            assertEquals(before.generation + 1, setup.state().generation)
            assertEquals(MaintenanceOutcome.Reopened, setup.listener.finished.last())
            assertEquals(content, setup.dump())
            setup.hook.clear()
            setup.restart() // the next startup finishes what the failed step left; a KEK left by DELETE_KEK stays (CS-13)
            assertEquals("$step", setOf(before.generation + 1), files.generationsOnDisk().keys)
        }
    }

    /**
     * The leftover KEK of crash-safety row CS-13, on the real AndroidKeyStore: a change to `PLAIN` whose `DELETE_KEK`
     * step fails still commits (db-lifecycle.md § Failure classification), the next start keeps the KEK, and the next
     * change to `PASSWORD` reuses it (security.md § 3.1: generated only when the alias is missing), so the password
     * unlocks the new slot after a restart. The after-commit sweep above follows the same failure with a change to
     * `DEVICE`.
     */
    @Test
    fun aKeyChangeToPassword_overTheKekAFailedDeleteKekLeft_reusesIt_andThePasswordUnlocks() = runFailureTest {
        seeded()
        val content = setup.dump()
        setup.changeKey(KeyMode.DEVICE)
        val device = setup.state() // its slot is wrapped with the KEK that this change generated
        val kekCreated = kekCreationDate()
        setup.hook.throwAt(MaintenanceOp.KeyChange, PipelineStep.DELETE_KEK, IOException("injected"))

        val toPlain = setup.maintenance.changeKey(KeyMode.PLAIN, null, null)

        assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.DEVICE, KeyMode.PLAIN)), toPlain)
        val plain = setup.state()
        assertEquals(KeyMode.PLAIN, plain.slot.mode)
        assertEquals(device.generation + 1, plain.generation)
        val steps = setup.hook.steps(MaintenanceOp.KeyChange) // the change to DEVICE runs no DELETE_KEK
        assertTrue("the change to PLAIN never reached DELETE_KEK", PipelineStep.DELETE_KEK in steps)
        assertTrue("the failed DELETE_KEK step deleted the KEK", vaults.kekExists())
        setup.hook.clear()
        setup.restart() // the cold start after the failure: CS-13 proceeds on PLAIN and leaves the KEK
        assertTrue("the next start deleted the KEK", vaults.kekExists())
        assertSameKek("after the next start", device.slot, kekCreated)

        val toPassword = setup.maintenance.changeKey(KeyMode.PASSWORD, null, KEY_PASSWORD.toCharArray())

        assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.PASSWORD)), toPassword)
        val committed = setup.state()
        assertEquals(KeyMode.PASSWORD, committed.slot.mode)
        assertEquals(plain.generation + 1, committed.generation)
        assertSameKek("after the change to PASSWORD", device.slot, kekCreated)
        assertEquals(content, setup.dump())

        setup.restart(KEY_PASSWORD) // E1 and E2: the password unlocks the committed slot, and its generation opens
        assertEquals(content, setup.dump())
    }

    @Test
    fun anUnknownCommitOutcome_deletesNothing_andRestarts() = runFailureTest {
        seeded()
        val before = setup.state()
        checkNotNull(vault).failReadAfterNextCommit = true

        val result = setup.maintenance.changeKey(KeyMode.DEVICE, null, null)

        assertEquals(AppResult.Err(AppError.OutcomeUnknown), result)
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertEquals(HolderState.Closed, setup.holder.state.value)
        assertEquals(setOf(before.generation, before.generation + 1), files.generationsOnDisk().keys)
        setup.start() // startup decides from the key-state on disk: the commit landed
        assertEquals(KeyMode.DEVICE, setup.state().slot.mode)
        assertEquals(setOf(before.generation + 1), files.generationsOnDisk().keys)
    }

    @Test
    fun aThrowableThatIsNotAnException_afterTheCommitStarted_isAnUnknownOutcome() = runFailureTest {
        val oom = MaintenanceSetup(databases, vaults, scope.scope, files = files, wrapVault = { real ->
            ReadBackFailingVault(real) { OutOfMemoryError("injected") }.also { vault = it }
        })
        oom.start()
        val before = oom.state()
        checkNotNull(vault).failReadAfterNextCommit = true

        val result = oom.maintenance.changeKey(KeyMode.DEVICE, null, null)

        assertEquals(AppResult.Err(AppError.OutcomeUnknown), result)
        assertEquals(MaintenanceOutcome.Restart, oom.listener.finished.last())
        assertEquals(setOf(before.generation, before.generation + 1), files.generationsOnDisk().keys)
    }

    @Test
    fun aSecondOperation_whileOneIsHeld_isBusy_andChangesNoFile() = runFailureTest {
        seeded()
        setup.hook.blockAt(MaintenanceOp.Backup, PipelineStep.EXPORT)
        coroutineScope {
            val first = async { setup.maintenance.backupForShare(null) }
            setup.hook.blocked.await()
            val names = files.databasesDir.list()!!.toSet()
            val state = vaults.stateFile.readBytes()

            assertEquals(AppResult.Err(AppError.Busy), setup.maintenance.changeKey(KeyMode.DEVICE, null, null))
            assertEquals(AppResult.Err(AppError.Busy), setup.maintenance.backupForShare(null))

            assertEquals(names, files.databasesDir.list()!!.toSet())
            assertArrayEquals(state, vaults.stateFile.readBytes())
            setup.hook.release()
            assertTrue(first.await() is AppResult.Ok)
        }
    }

    @Test
    fun completeStartup_failingUpToItsCommit_isTransient_andAfterItDone() = runFailureTest {
        val steps = listOf(PipelineStep.PRECHECK, PipelineStep.CREATE_EMPTY, PipelineStep.VERIFY_TARGET, PipelineStep.RENAME, PipelineStep.COMMIT)
        setup.maintenance.reconcileAtStartup()
        for (step in steps) for (error in errors()) {
            setup.hook.throwAt(null, step, error)
            assertEquals("$step $error", StartupCompletion.Transient, setup.maintenance.completeStartup())
            assertFalse("$step", vaults.stateFile.exists())
            assertEquals("$step", emptyList<String>(), files.stagingDir.list()!!.toList())
            assertEquals("$step", emptySet<Long>(), files.generationsOnDisk().keys)
        }
        setup.hook.throwAt(null, PipelineStep.DELETE_OLD, IOException("injected"))
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
        assertEquals(1L, setup.state().generation) // no failed attempt left a number behind
    }

    @Test
    fun aBackupToADocument_failingBeforeItsPipeline_deletesOnlyAnEmptyCreatedDocument() = runFailureTest {
        seeded()
        setup.settings.setEncryptBackups(true)
        val created = document("created.db").apply { file.writeBytes(ByteArray(0)) }
        val existing = document("existing.db").apply { file.writeBytes(byteArrayOf(1, 2, 3)) }

        // The backup password's own rules are checked before the pipeline looks at the document.
        val invalid = setup.maintenance.backupToDocument(created.uri, "short".toCharArray())

        assertEquals(AppResult.Err(AppError.InvalidInput(InputRule.PasswordTooShort)), invalid)
        assertFalse(created.file.exists())
        created.file.writeBytes(ByteArray(0))
        setup.hook.blockAt(MaintenanceOp.Backup, PipelineStep.EXPORT)
        coroutineScope {
            val held = async { setup.maintenance.backupForShare(BACKUP_PASSWORD.toCharArray()) }
            setup.hook.blocked.await()

            val busy = setup.maintenance.backupToDocument(created.uri, BACKUP_PASSWORD.toCharArray())
            val busyExisting = setup.maintenance.backupToDocument(existing.uri, BACKUP_PASSWORD.toCharArray())

            assertEquals(AppResult.Err(AppError.Busy), busy)
            assertEquals(AppResult.Err(AppError.Busy), busyExisting)
            assertFalse(created.file.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), existing.file.readBytes())
            setup.hook.release()
            assertTrue(held.await() is AppResult.Ok)
        }
    }

    @Test
    fun aListenerThatThrows_stillReleasesTheOperationMutex() = runFailureTest {
        seeded()
        setup.listener.failNextFinished = IllegalStateException("injected")

        assertTrue(runCatching { setup.maintenance.backupForShare(null) }.isFailure)

        scope.takeFailures() // the detached operation reported it to the application scope as well
        assertTrue(setup.maintenance.backupForShare(null) is AppResult.Ok) // not Busy
        setup.holder.close()
        setup.maintenance.reconcileAtStartup() // takes the mutex with withLock: it would wait for good
    }

    @Test
    fun aCancellationDuringExclusiveMode_rollsBack_andRoomReopensOnTheSameGeneration() = runFailureTest {
        seeded()
        val generation = setup.state().generation
        val content = setup.dump()
        setup.hook.blockAt(MaintenanceOp.Backup, PipelineStep.EXPORT)
        coroutineScope {
            val backup = async { runCatching { setup.maintenance.backupForShare(null) } }
            setup.hook.blocked.await()

            scope.scope.cancel() // production never cancels applicationScope; db-lifecycle.md § Cancellation policy
            setup.hook.release()
            backup.await() // returns once the frame has finished, Room's reopen included
        }
        assertEquals(generation, (setup.holder.state.value as HolderState.Open).generation)
        assertEquals(content, setup.dump())
    }

    /** The steps [op] runs when nothing fails, in order. */
    private suspend fun stepsOf(op: MaintenanceOp, run: suspend () -> Unit): List<PipelineStep> {
        val before = setup.hook.steps(op).size
        run()
        return setup.hook.steps(op).drop(before)
    }

    private suspend fun seeded() {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 11, notes = 50).generate())
    }

    private class Snapshot(val state: ByteArray, val generations: Set<Long>, val content: Any, val finished: Int)

    private fun snapshot() = Snapshot(vaults.stateFile.readBytes(), files.generationsOnDisk().keys, setup.dump(), setup.listener.finished.size)

    /** The Unchanged comparison of testing.md § 5, with the gate back at Ready through `Reopened`. */
    private fun assertPreviousStateInForce(case: String, before: Snapshot, startedAt: Boolean) {
        assertArrayEquals(case, before.state, vaults.stateFile.readBytes())
        assertEquals(case, before.generations, files.generationsOnDisk().keys)
        assertEquals(case, before.content, setup.dump())
        assertEquals(case, emptyList<String>(), files.stagingDir.list()!!.toList())
        val finished = setup.listener.finished
        if (startedAt) {
            assertEquals(case, before.finished + 1, finished.size)
            assertEquals(case, MaintenanceOutcome.Reopened, finished.last())
        } else {
            assertEquals(case, before.finished, finished.size)
        }
        setup.hook.clear()
    }

    private fun finished() = setup.maintenance.status.value as MaintenanceStatus.Finished

    /** The KEK's creation date, read under the production alias from the AndroidKeyStore `AndroidKekKeystore` uses. */
    private fun kekCreationDate(): Date {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return checkNotNull(keyStore.getCreationDate(KEK_ALIAS)) { "no KEK creation date" }
    }

    /**
     * The KEK under the alias is still the key that wrapped [deviceSlot]: only that key passes the GCM tag of the
     * slot's unwrap, checked first, so that a creation date that differs for another reason shows as such.
     */
    private suspend fun assertSameKek(case: String, deviceSlot: KeySlot, created: Date) {
        val unwrapped = setup.vault.openKey(deviceSlot, null)
        if (unwrapped is UnlockResult.Key) unwrapped.rawKey.fill(0)
        assertTrue("$case: the KEK no longer unwraps the DEVICE slot, $unwrapped", unwrapped is UnlockResult.Key)
        assertEquals("$case: the KEK has another creation date", created, kekCreationDate())
    }

    private fun errors(): List<Throwable> = listOf(IOException("injected"), SQLiteFullException("injected"))

    private class Document(val file: File, val uri: Uri)

    private fun document(name: String) =
        Document(RecordingDocumentsProvider.directory(context).resolve(name), RecordingDocumentsProvider.uri(context, name))

    private companion object {
        const val BACKUP_PASSWORD = "backup password 3"
        const val KEY_PASSWORD = "database password 4"

        val DOCUMENT_STEPS = listOf(
            PipelineStep.PRECHECK, PipelineStep.CLOSE_ROOM, PipelineStep.CHECK_SOURCE, PipelineStep.EXPORT,
            PipelineStep.REOPEN, PipelineStep.VERIFY_TARGET, PipelineStep.WRITE_DOCUMENT, PipelineStep.CLEANUP,
        )

        /** The step-table row of a key change to `DEVICE`: every step but `DELETE_KEK`, which only a change to `PLAIN` runs. */
        val KEY_CHANGE_STEPS = listOf(
            PipelineStep.PRECHECK, PipelineStep.PREPARE_KEY, PipelineStep.CLOSE_ROOM, PipelineStep.CHECK_SOURCE,
            PipelineStep.EXPORT, PipelineStep.VERIFY_TARGET, PipelineStep.RENAME, PipelineStep.COMMIT,
            PipelineStep.REOPEN, PipelineStep.DELETE_OLD, PipelineStep.DELETE_BIO_KEY, PipelineStep.CLEANUP,
        )
    }
}

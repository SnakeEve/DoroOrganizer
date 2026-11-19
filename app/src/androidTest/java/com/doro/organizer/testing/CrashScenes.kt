package com.doro.organizer.testing

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import androidx.core.util.AtomicFile
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.debug.CRASH_FAILURE_MARKER
import com.doro.organizer.debug.CRASH_RESTORE_INPUT
import com.doro.organizer.debug.CRASH_RUN_MARKER
import com.doro.organizer.debug.CrashOp
import com.doro.organizer.debug.CrashRequest
import com.doro.organizer.debug.CrashTestContainer
import com.doro.organizer.debug.CrashTestService
import com.doro.organizer.debug.awaitBackupInputs
import com.doro.organizer.debug.runCrashRequest
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.BIO_ALIAS
import com.doro.organizer.security.KEK_ALIAS
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.session.GateEvent
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Resume
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

// L-15 (testing.md § 5, "Process death"; db-lifecycle.md § Crash-safety matrix): the scenes the crash test plants on
// the production locations of the debug app, the expectation table keyed by the matrix rows CS-nn, and the check of
// the next start against the matrix's invariants.

/** What is on disk before the operation runs. */
enum class Plant(val mode: KeyMode?, val password: String?) {
    Empty(null, null), PlainFile(KeyMode.PLAIN, null), Plain(KeyMode.PLAIN, null), Device(KeyMode.DEVICE, null),
    Password(KeyMode.PASSWORD, CrashScene.P1), PasswordWithSlot(KeyMode.PASSWORD, CrashScene.P1),
    PasswordWithDeadSlot(KeyMode.PASSWORD, CrashScene.P1),
    CorruptPlain(KeyMode.PLAIN, null), CorruptPassword(KeyMode.PASSWORD, CrashScene.P1),
    KeyUnavailableDevice(KeyMode.DEVICE, null),
}

/** The state the next start must reach, per the matrix row. */
enum class Expect { Old, NewSameData, NewRestored, NewEmpty, Corrupt, KeyUnavailable, FirstRunEmpty }

/**
 * One operation from one origin over one plant; [target] is the key-change target. [encryptedBackup]: the backup it
 * writes, or the restore input it reads, is encrypted with [CrashScene.BACKUP_PASSWORD]. [readBackFails]: the read-back
 * of its commit fails although the commit landed, and the process dies after the operation (CS-14, CS-38).
 * [killAfterSlotCommit]: the process dies as soon as the commit of the new biometric slot returned (CS-30).
 */
data class CrashFlow(
    val op: CrashOp,
    val origin: MaintenanceOrigin,
    val plant: Plant,
    val target: KeyMode? = null,
    val encryptedBackup: Boolean = false,
    val readBackFails: Boolean = false,
    val killAfterSlotCommit: Boolean = false,
) {
    /** The fault strikes inside the vault, at no hook. */
    val faultInVault: Boolean get() = readBackFails || killAfterSlotCommit

    override fun toString() = "$op from $origin over $plant" + (target?.let { " to $it" } ?: "") +
        (if (encryptedBackup) ", encrypted backup" else "") + (if (readBackFails) ", read-back fails" else "") +
        (if (killAfterSlotCommit) ", killed after the slot commit" else "")
}

/** A kill at the hook of [step] in [flow] and what the matrix row [row] says the next start shows. */
data class CrashCase(val flow: CrashFlow, val step: PipelineStep, val row: String, val expect: Expect) {
    /**
     * The key-state with the new biometric slot is in force, so the next start opens with the fingerprint. Every kill
     * at a hook of a slot creation comes before its commit, and the repair after a failed read-back removes the slot
     * and deletes its key (CS-38); only the kill right after the commit returned leaves it (CS-30).
     */
    val slotCommitted: Boolean get() = flow.killAfterSlotCommit

    override fun toString() = "$row: $flow, " + when {
        flow.readBackFails -> "killed after it returned"
        flow.killAfterSlotCommit -> "killed after its slot commit returned"
        else -> "killed at $step"
    }
}

/** The expectations: every step of db-lifecycle.md's step table for every flow, keyed by its matrix row. */
object CrashMatrix {
    private fun MutableList<CrashCase>.flow(flow: CrashFlow, vararg steps: Triple<PipelineStep, String, Expect>) =
        steps.forEach { (step, row, expect) -> add(CrashCase(flow, step, row, expect)) }

    private fun s(step: PipelineStep, row: String, expect: Expect) = Triple(step, row, expect)

    private fun keyChange(flow: CrashFlow, newRow: String = "CS-12", after: Expect = Expect.NewSameData) =
        listOf(
            s(PipelineStep.PRECHECK, "CS-01", Expect.Old), s(PipelineStep.PREPARE_KEY, "CS-10", Expect.Old),
            s(PipelineStep.CLOSE_ROOM, "CS-10", Expect.Old), s(PipelineStep.CHECK_SOURCE, "CS-10", Expect.Old),
            s(PipelineStep.EXPORT, "CS-10", Expect.Old), s(PipelineStep.VERIFY_TARGET, "CS-10", Expect.Old),
            s(PipelineStep.RENAME, "CS-10", Expect.Old), s(PipelineStep.COMMIT, "CS-11", Expect.Old),
            s(PipelineStep.REOPEN, newRow, after), s(PipelineStep.DELETE_OLD, newRow, after),
        ) + (if (flow.target == KeyMode.PLAIN) listOf(s(PipelineStep.DELETE_KEK, "CS-13", after)) else emptyList()) +
            listOf(
                s(PipelineStep.DELETE_BIO_KEY, "CS-33", after),
                s(PipelineStep.CLEANUP, if (flow.target == KeyMode.PLAIN) "CS-13" else "CS-33", after),
            )

    /** The first schema version: `checkIdentity` rejects a `user_version` below it as Incompatible. */
    const val FIRST_SCHEMA_VERSION = 1

    /**
     * The checks a restore of the planted input passes. That input is a backup this build made, at
     * `AppDatabase.VERSION`, and `MIGRATE_INPUT` runs only for an older `user_version` (db-lifecycle.md § Restore,
     * step 7): while the schema is at [FIRST_SCHEMA_VERSION] no input can reach it. From the next version on,
     * `CrashSafetyCoverageTest` fails until every restore flow plants an older input and expects the step.
     */
    private val restoreChecks = listOf(
        PipelineStep.PRECHECK, PipelineStep.COPY_INPUT, PipelineStep.OPEN_INPUT, PipelineStep.VALIDATE_INPUT,
        PipelineStep.CHECK_SCHEMA,
    )

    private fun backup(last: PipelineStep, lastRow: String) = listOf(
        s(PipelineStep.PRECHECK, "CS-01", Expect.Old), s(PipelineStep.CLOSE_ROOM, "CS-07", Expect.Old),
        s(PipelineStep.CHECK_SOURCE, "CS-07", Expect.Old), s(PipelineStep.EXPORT, "CS-07", Expect.Old),
        s(PipelineStep.REOPEN, "CS-07", Expect.Old), s(PipelineStep.VERIFY_TARGET, "CS-07", Expect.Old),
        s(last, lastRow, Expect.Old), s(PipelineStep.CLEANUP, lastRow, Expect.Old),
    )

    private val restoreFromReady = restoreChecks.map { s(it, "CS-15", Expect.Old) } + listOf(
        s(PipelineStep.EXPORT, "CS-15", Expect.Old), s(PipelineStep.VERIFY_TARGET, "CS-15", Expect.Old),
        s(PipelineStep.AWAIT_CONFIRMATION, "CS-15", Expect.Old), s(PipelineStep.CLOSE_ROOM, "CS-15", Expect.Old),
        s(PipelineStep.MARK_RESET, "CS-16", Expect.Old), s(PipelineStep.RENAME, "CS-16", Expect.Old),
        s(PipelineStep.COMMIT, "CS-16", Expect.Old), s(PipelineStep.DATA_RESET, "CS-18", Expect.NewRestored),
        s(PipelineStep.REOPEN, "CS-19", Expect.NewRestored), s(PipelineStep.DELETE_OLD, "CS-19", Expect.NewRestored),
        s(PipelineStep.CLEANUP, "CS-19", Expect.NewRestored),
    )

    val cases: List<CrashCase> = buildList {
        flow(
            CrashFlow(CrashOp.Startup, MaintenanceOrigin.NeedsPassword, Plant.Empty),
            s(PipelineStep.PRECHECK, "CS-02", Expect.FirstRunEmpty), s(PipelineStep.CREATE_EMPTY, "CS-02", Expect.FirstRunEmpty),
            s(PipelineStep.VERIFY_TARGET, "CS-02", Expect.FirstRunEmpty), s(PipelineStep.RENAME, "CS-02", Expect.FirstRunEmpty),
            s(PipelineStep.COMMIT, "CS-03", Expect.FirstRunEmpty), s(PipelineStep.DELETE_OLD, "CS-04", Expect.FirstRunEmpty),
        )
        flow(
            CrashFlow(CrashOp.Startup, MaintenanceOrigin.NeedsPassword, Plant.PlainFile),
            s(PipelineStep.COMMIT, "CS-05", Expect.Old), s(PipelineStep.DELETE_OLD, "CS-06", Expect.Old),
        )
        for ((op, last) in listOf(CrashOp.BackupToDocument to PipelineStep.WRITE_DOCUMENT, CrashOp.BackupForShare to PipelineStep.PUBLISH_SHARE)) {
            val lastRow = if (op == CrashOp.BackupToDocument) "CS-08" else "CS-09"
            // Plaintext and encrypted backups pass the same steps (db-lifecycle.md's step table).
            for (encrypted in listOf(false, true)) {
                flow(CrashFlow(op, MaintenanceOrigin.Ready, Plant.Plain, encryptedBackup = encrypted), *backup(last, lastRow).toTypedArray())
            }
        }
        val keyChanges = listOf(
            Plant.Plain to KeyMode.DEVICE, Plant.Plain to KeyMode.PASSWORD, Plant.Device to KeyMode.PLAIN,
            Plant.Device to KeyMode.PASSWORD, Plant.Password to KeyMode.PLAIN, Plant.Password to KeyMode.DEVICE,
            Plant.Password to KeyMode.PASSWORD,
        )
        for ((plant, target) in keyChanges) {
            val flow = CrashFlow(CrashOp.KeyChange, MaintenanceOrigin.Ready, plant, target)
            flow(flow, *keyChange(flow).toTypedArray())
        }
        val resetFromReady = CrashFlow(CrashOp.FingerprintReset, MaintenanceOrigin.Ready, Plant.PasswordWithSlot, KeyMode.PASSWORD)
        flow(resetFromReady, s(PipelineStep.UNLOCK_BIOMETRIC, "CS-01", Expect.Old), *keyChange(resetFromReady).toTypedArray())
        flow(
            CrashFlow(CrashOp.FingerprintReset, MaintenanceOrigin.NeedsPassword, Plant.PasswordWithSlot, KeyMode.PASSWORD),
            s(PipelineStep.UNLOCK_BIOMETRIC, "CS-01", Expect.Old), s(PipelineStep.PRECHECK, "CS-01", Expect.Old),
            s(PipelineStep.PREPARE_KEY, "CS-35", Expect.Old), s(PipelineStep.CHECK_SOURCE, "CS-35", Expect.Old),
            s(PipelineStep.EXPORT, "CS-35", Expect.Old), s(PipelineStep.VERIFY_TARGET, "CS-35", Expect.Old),
            s(PipelineStep.RENAME, "CS-35", Expect.Old), s(PipelineStep.COMMIT, "CS-35", Expect.Old),
            s(PipelineStep.DELETE_OLD, "CS-36", Expect.NewSameData), s(PipelineStep.DELETE_BIO_KEY, "CS-36", Expect.NewSameData),
            s(PipelineStep.CLEANUP, "CS-36", Expect.NewSameData),
        )
        for (encrypted in listOf(false, true)) {
            flow(CrashFlow(CrashOp.Restore, MaintenanceOrigin.Ready, Plant.Plain, encryptedBackup = encrypted), *restoreFromReady.toTypedArray())
        }
        flow(
            CrashFlow(CrashOp.Restore, MaintenanceOrigin.Corrupt, Plant.CorruptPlain),
            *restoreChecks.map { s(it, "CS-15", Expect.Corrupt) }.toTypedArray(),
            s(PipelineStep.UNLOCK_DEVICE_KEY, "CS-15", Expect.Corrupt), s(PipelineStep.EXPORT, "CS-15", Expect.Corrupt),
            s(PipelineStep.VERIFY_TARGET, "CS-15", Expect.Corrupt), s(PipelineStep.AWAIT_CONFIRMATION, "CS-15", Expect.Corrupt),
            s(PipelineStep.MARK_RESET, "CS-16", Expect.Corrupt), s(PipelineStep.RENAME, "CS-16", Expect.Corrupt),
            s(PipelineStep.COMMIT, "CS-16", Expect.Corrupt), s(PipelineStep.DATA_RESET, "CS-18", Expect.NewRestored),
            s(PipelineStep.DELETE_OLD, "CS-19", Expect.NewRestored), s(PipelineStep.CLEANUP, "CS-19", Expect.NewRestored),
        )
        flow(
            CrashFlow(CrashOp.Restore, MaintenanceOrigin.KeyUnavailable, Plant.KeyUnavailableDevice),
            *restoreChecks.map { s(it, "CS-15", Expect.KeyUnavailable) }.toTypedArray(),
            s(PipelineStep.EXPORT, "CS-15", Expect.KeyUnavailable), s(PipelineStep.VERIFY_TARGET, "CS-15", Expect.KeyUnavailable),
            s(PipelineStep.AWAIT_CONFIRMATION, "CS-15", Expect.KeyUnavailable),
            s(PipelineStep.MARK_RESET, "CS-17", Expect.KeyUnavailable), s(PipelineStep.RENAME, "CS-17", Expect.KeyUnavailable),
            s(PipelineStep.COMMIT, "CS-17", Expect.KeyUnavailable), s(PipelineStep.DATA_RESET, "CS-18", Expect.NewRestored),
            s(PipelineStep.DELETE_OLD, "CS-19", Expect.NewRestored), s(PipelineStep.DELETE_KEK, "CS-20", Expect.NewRestored),
            s(PipelineStep.DELETE_BIO_KEY, "CS-20", Expect.NewRestored), s(PipelineStep.CLEANUP, "CS-19", Expect.NewRestored),
        )
        for ((origin, plant, before) in listOf(
            Triple(MaintenanceOrigin.Corrupt, Plant.CorruptPlain, Expect.Corrupt),
            Triple(MaintenanceOrigin.KeyUnavailable, Plant.KeyUnavailableDevice, Expect.KeyUnavailable),
        )) {
            flow(
                CrashFlow(CrashOp.StartFresh, origin, plant),
                s(PipelineStep.PRECHECK, "CS-01", before), s(PipelineStep.CREATE_EMPTY, "CS-21", before),
                s(PipelineStep.VERIFY_TARGET, "CS-21", before), s(PipelineStep.MARK_RESET, "CS-21", before),
                s(PipelineStep.RENAME, "CS-22", before), s(PipelineStep.COMMIT, "CS-22", before),
                s(PipelineStep.DATA_RESET, "CS-23", Expect.NewEmpty), s(PipelineStep.DELETE_OLD, "CS-23", Expect.NewEmpty),
                s(PipelineStep.DELETE_KEK, "CS-23", Expect.NewEmpty), s(PipelineStep.DELETE_BIO_KEY, "CS-23", Expect.NewEmpty),
                s(PipelineStep.CLEANUP, "CS-23", Expect.NewEmpty),
            )
        }
        for ((origin, plant, before) in listOf(
            Triple(MaintenanceOrigin.NeedsPassword, Plant.Password, Expect.Old),
            Triple(MaintenanceOrigin.Corrupt, Plant.CorruptPassword, Expect.Corrupt),
        )) {
            flow(
                CrashFlow(CrashOp.Erase, origin, plant),
                s(PipelineStep.DELETE_OLD, "CS-24", before), s(PipelineStep.ERASE_KEY_STATE, "CS-24", before),
                s(PipelineStep.ERASE_FILES, "CS-25", Expect.KeyUnavailable),
                s(PipelineStep.RESET_SETTINGS, "CS-26", Expect.FirstRunEmpty),
            )
        }
        flow(
            CrashFlow(CrashOp.Erase, MaintenanceOrigin.Ready, Plant.Password),
            s(PipelineStep.DELETE_OLD, "CS-37", Expect.Old), s(PipelineStep.CLOSE_ROOM, "CS-37", Expect.Old),
            s(PipelineStep.ERASE_KEY_STATE, "CS-37", Expect.Old), s(PipelineStep.ERASE_FILES, "CS-25", Expect.KeyUnavailable),
            s(PipelineStep.RESET_SETTINGS, "CS-26", Expect.FirstRunEmpty),
        )
        // Creation, and re-creation over a dead slot, after the lock-on flow wrote the setting: lock on, no slot.
        for (plant in listOf(Plant.Password, Plant.PasswordWithDeadSlot)) {
            flow(
                CrashFlow(CrashOp.CreateBioSlot, MaintenanceOrigin.Ready, plant),
                s(PipelineStep.PREPARE_BIO_SLOT, "CS-29", Expect.Old), s(PipelineStep.COMMIT_BIO_SLOT, "CS-29", Expect.Old),
            )
        }
        // Removal after the lock-off flow wrote the setting: lock off with a stale slot.
        flow(
            CrashFlow(CrashOp.RemoveBioSlot, MaintenanceOrigin.NeedsPassword, Plant.PasswordWithSlot),
            s(PipelineStep.DELETE_BIO_KEY, "CS-31", Expect.Old), s(PipelineStep.COMMIT_BIO_SLOT, "CS-31", Expect.Old),
        )
        // CommitOutcome.Unknown: the generation commit landed, its read-back failed, and the operation ended in
        // Restart. The next start follows the key-state on disk (CS-14): the new generation, with the data-reset
        // marker of a Restore or Start fresh applied (invariant 4).
        for ((flow, expect) in listOf(
            CrashFlow(CrashOp.KeyChange, MaintenanceOrigin.Ready, Plant.Plain, KeyMode.DEVICE, readBackFails = true) to Expect.NewSameData,
            CrashFlow(CrashOp.Restore, MaintenanceOrigin.Ready, Plant.Plain, readBackFails = true) to Expect.NewRestored,
            CrashFlow(CrashOp.Restore, MaintenanceOrigin.Corrupt, Plant.CorruptPlain, readBackFails = true) to Expect.NewRestored,
            CrashFlow(CrashOp.StartFresh, MaintenanceOrigin.Corrupt, Plant.CorruptPlain, readBackFails = true) to Expect.NewEmpty,
        )) {
            add(CrashCase(flow, PipelineStep.COMMIT, "CS-14", expect))
        }
        // A slot creation whose read-back failed repairs once: the key-state without the slot, its key deleted (CS-38).
        add(
            CrashCase(
                CrashFlow(CrashOp.CreateBioSlot, MaintenanceOrigin.Ready, Plant.Password, readBackFails = true),
                PipelineStep.COMMIT_BIO_SLOT, "CS-38", Expect.Old,
            ),
        )
        // Killed after the commit of the new slot returned, before its read-back: the slot is in force (CS-30).
        add(
            CrashCase(
                CrashFlow(CrashOp.CreateBioSlot, MaintenanceOrigin.Ready, Plant.Password, killAfterSlotCommit = true),
                PipelineStep.COMMIT_BIO_SLOT, "CS-30", Expect.Old,
            ),
        )
    }

    val flows: List<CrashFlow> get() = cases.map { it.flow }.distinct()

    /** The flows that die at a hook: every one but those whose fault the vault injects. */
    val killedAtAStep: List<CrashFlow> get() = flows.filterNot { it.faultInVault }
}

/**
 * Plants a [Plant] on the production locations of the debug app, runs a [CrashRequest] in the `:crashtest` process
 * until it dies, and checks the next start. Every container it builds in this process is closed before the next one,
 * because DataStore and the database files allow one user at a time (db-lifecycle.md § Concurrency rules, rule 9).
 */
class CrashScene {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    /** The data before the operation ([oldDump]) and that of the planted backup ([backupDump]). */
    class Planted(val oldDump: Map<String, List<List<String?>>>?, val backupDump: Map<String, List<List<String?>>>?, val dataEpoch: Int)

    /** Empties every location the app writes and both Keystore aliases. */
    fun wipe() {
        app.getDatabasePath("x").parentFile?.listFiles()?.filter { it.name.startsWith("doro-") }?.forEach { it.delete() }
        app.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        File(app.cacheDir, "share").deleteRecursively()
        File(app.cacheDir, "crashtest-out").deleteRecursively()
        app.preferencesDataStoreFile("settings").delete()
        androidKeyStore().run {
            deleteEntry(KEK_ALIAS)
            deleteEntry(BIO_ALIAS)
        }
    }

    /** The data, key-state and restore input [flow] starts from. */
    suspend fun plant(flow: CrashFlow): Planted {
        val plant = flow.plant
        if (plant == Plant.Empty) return Planted(null, null, 0)
        return withContainer { c ->
            val maintenance = c.dbMaintenance
            maintenance.reconcileAtStartup()
            maintenance.completeStartup()
            openLive(c, null)
            insertNote(c, "A")
            val backupDump = dump(c)
            val input = File(app.noBackupFilesDir, CRASH_RESTORE_INPUT).apply { parentFile?.mkdirs() }
            val backupPassword = if (flow.encryptedBackup) BACKUP_PASSWORD.toCharArray() else null
            awaitBackupInputs(c)
            check(maintenance.backupToDocument(Uri.fromFile(input), backupPassword) is AppResult.Ok) { "the planted backup" }
            insertNote(c, "B")
            when (plant) {
                Plant.Device, Plant.KeyUnavailableDevice -> changeKey(c, KeyMode.DEVICE, null)
                Plant.Password, Plant.PasswordWithSlot, Plant.PasswordWithDeadSlot, Plant.CorruptPassword ->
                    changeKey(c, KeyMode.PASSWORD, P1)
                else -> Unit
            }
            if (plant == Plant.PasswordWithSlot || plant == Plant.PasswordWithDeadSlot) {
                val cipher = (c.keyVault.biometricArmCipher() as SlotCipherResult.Ready).slotCipher
                check(maintenance.createBiometricSlot(cipher) == AppResult.Ok(Unit)) { "the planted slot" }
            }
            // The key behind the slot is gone, as after a new enrollment: the slot is Dead.
            if (plant == Plant.PasswordWithDeadSlot) androidKeyStore().deleteEntry(BIO_ALIAS)
            val oldDump = dump(c)
            val live = c.files.live(checkNotNull(c.keyVault.readState()).generation)
            c.databaseHolder.close()
            when (plant) {
                Plant.PlainFile -> AtomicFile(File(app.noBackupFilesDir, "db-state.json")).delete()
                Plant.CorruptPlain -> corruptPage(live, 2)
                Plant.CorruptPassword -> flipByteInPage(live, 2)
                Plant.KeyUnavailableDevice -> androidKeyStore().deleteEntry(KEK_ALIAS)
                else -> Unit
            }
            Planted(oldDump, backupDump, c.settingsStore.snapshot().dataEpoch)
        }
    }

    /** Binds the service with [request] and waits for its process to die at the requested step. */
    fun runUntilKilled(request: CrashRequest) {
        File(app.noBackupFilesDir, CRASH_RUN_MARKER).delete()
        val failure = File(app.noBackupFilesDir, CRASH_FAILURE_MARKER).apply { delete() }
        val died = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    binder.linkToDeath({ died.countDown() }, 0)
                } catch (e: RemoteException) {
                    died.countDown() // already dead
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = died.countDown()
        }
        val intent = request.into(Intent(app, CrashTestService::class.java))
        check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "cannot bind the crash-test service" }
        try {
            val dead = died.await(KILL_TIMEOUT_S, TimeUnit.SECONDS)
            if (failure.exists()) fail("$request: the run failed before its step: ${failure.readText()}")
            assertTrue("$request: the step was never reached", dead)
        } finally {
            app.unbindService(connection)
        }
    }

    /** Runs [request] in this process with [hook], for the coverage meta-test. */
    suspend fun runInProcess(request: CrashRequest, hook: MaintenanceFaultHook) = withContainer(hook) { c ->
        runCrashRequest(app, c, request, c.applicationScope)
        c.databaseHolder.close()
    }

    /**
     * The next start: the gate runs E1 and E2, the fingerprint lock screen gets a successful slot prompt, a password
     * screen gets the expected password; then the terminal state is checked.
     */
    suspend fun nextStart(case: CrashCase, planted: Planted) = withContainer { c ->
        GateScene.onMain { c.sessionGate.start() }
        var state = c.awaitTerminal()
        assertEquals("$case: the fingerprint route exactly when the slot was committed", case.slotCommitted, state == FINGERPRINT_LOCK)
        if (state == FINGERPRINT_LOCK) {
            c.biometricGateway.succeed()
            GateScene.onMain { c.appLockController.prompt() }
            state = c.awaitTerminal { it != FINGERPRINT_LOCK }
            assertTrue("$case: one slot prompt", c.biometricGateway.prompts.single().withCrypto)
        }
        if (state is GateState.NeedsPassword) {
            // A slot creation over a key-state without a slot leaves none in force: cause SlotMissing (CS-29, CS-38).
            if (case.flow.op == CrashOp.CreateBioSlot && case.flow.plant == Plant.Password) {
                assertEquals("$case: the password reason", PasswordReason.None, state.reason)
            }
            val password = checkNotNull(expectedPassword(case)) { "$case: unexpected password screen" }
            GateScene.onMain { c.sessionGate.dispatch(GateEvent.PasswordSubmitted(password.toCharArray())) }
            state = c.awaitTerminal { it !is GateState.NeedsPassword || it.failures > 0 }
        }
        assertExpectation(case, planted, c, state)
    }

    private suspend fun assertExpectation(case: CrashCase, planted: Planted, c: CheckContainer, state: GateState) {
        val expected = when (case.expect) {
            Expect.Corrupt -> GateState.Corrupt
            Expect.KeyUnavailable -> GateState.KeyUnavailable
            else -> GateState.Ready
        }
        assertEquals("$case", expected, state)
        // Invariant 1: a readable key-state never names a missing file.
        val committed = runCatching { c.keyVault.readState() }.getOrNull()
        committed?.let { assertTrue("$case: the key-state names a missing file", c.files.live(it.generation).isFile) }
        // Invariant 3: one generation and empty temporary directories, except in the KeyUnavailable rows.
        if (case.expect != Expect.KeyUnavailable) {
            assertEquals("$case: generations", 1, c.files.generationsOnDisk().size)
            for (dir in listOf(c.files.stagingDir, c.files.restoreDir, c.files.shareDir)) {
                assertTrue("$case: ${dir.name} is empty", dir.listFiles().isNullOrEmpty())
            }
        }
        if (state == GateState.Ready) {
            assertEquals("$case: key mode", expectedMode(case), committed?.slot?.mode)
            val dump = dump(c)
            when (case.expect) {
                Expect.Old, Expect.NewSameData -> assertEquals("$case: the data", planted.oldDump, dump)
                Expect.NewRestored -> assertEquals("$case: the restored data", planted.backupDump, dump)
                else -> assertTrue("$case: an empty database", dump["notes"].isNullOrEmpty())
            }
        }
        // The lock toggles wrote the setting before the slot change (DEC-65): CS-29 lock on, CS-31 lock off.
        when (case.flow.op) {
            CrashOp.CreateBioSlot -> assertTrue("$case: lock on", c.settingsStore.snapshot().biometricLock)
            CrashOp.RemoveBioSlot -> assertFalse("$case: lock off", c.settingsStore.snapshot().biometricLock)
            else -> Unit
        }
        // Invariant 2: nothing but temporary files and unreferenced generations was deleted: the data above, and the
        // committed generation's file exists.
        committed?.let { assertTrue("$case: the committed generation", c.files.live(it.generation).length() > 0) }
        // Invariant 4: a data reset exactly when a Restore or Start fresh was committed.
        if (case.flow.op == CrashOp.Restore || case.flow.op == CrashOp.StartFresh) {
            val resets = c.settingsStore.snapshot().dataEpoch - planted.dataEpoch
            val committedReset = case.expect == Expect.NewRestored || case.expect == Expect.NewEmpty
            assertEquals("$case: data reset", committedReset, resets > 0)
        }
    }

    private fun expectedMode(case: CrashCase): KeyMode = when (case.expect) {
        Expect.Old -> checkNotNull(case.flow.plant.mode)
        Expect.NewSameData -> checkNotNull(case.flow.target)
        Expect.NewRestored -> if (case.flow.origin == MaintenanceOrigin.KeyUnavailable) KeyMode.PLAIN else checkNotNull(case.flow.plant.mode)
        else -> KeyMode.PLAIN
    }

    private fun expectedPassword(case: CrashCase): String? = when (case.expect) {
        Expect.NewSameData -> if (case.flow.target == KeyMode.PASSWORD) P2 else null
        Expect.NewRestored, Expect.NewEmpty, Expect.FirstRunEmpty, Expect.KeyUnavailable -> null
        Expect.Old, Expect.Corrupt -> case.flow.plant.password
    }

    /** A container of this process over the production files; closed (holder, scope, DataStore) after [block]. */
    private suspend fun <T> withContainer(hook: MaintenanceFaultHook = MaintenanceFaultHook.None, block: suspend (CheckContainer) -> T): T {
        lateinit var container: CheckContainer
        instrumentation.runOnMainSync { container = CheckContainer(app, hook) }
        try {
            return block(container)
        } finally {
            container.databaseHolder.close()
            container.applicationScope.coroutineContext.job.cancelAndJoin()
        }
    }

    private suspend fun openLive(c: CheckContainer, password: String?) {
        val state = checkNotNull(c.keyVault.readState())
        val key = c.keyVault.unlock(password?.let(::passwordBytesOf)) as UnlockResult.Key
        c.databaseHolder.open(state.generation, key.rawKey)
    }

    private suspend fun changeKey(c: CheckContainer, target: KeyMode, new: String?) {
        val result = c.dbMaintenance.changeKey(target, null, new?.toCharArray())
        check(result is AppResult.Ok) { "the planted key change: $result" }
    }

    private suspend fun insertNote(c: CheckContainer, content: String) {
        c.databaseHolder.withDb {
            it.openHelper.writableDatabase.execSQL(
                "INSERT INTO notes(content, content_fold, date, checked, checked_at, created_at, updated_at) " +
                    "VALUES(?, ?, NULL, 0, NULL, 1, 1)",
                arrayOf(content, content.lowercase()),
            )
        }
    }

    private fun dump(c: CheckContainer) =
        canonicalDump(((c.databaseHolder.state.value as HolderState.Open).db).openHelper.writableDatabase)

    /** The crash-test wiring with a cancellable scope, a fake process lifecycle and a fake sensor. */
    private class CheckContainer(app: Application, hook: MaintenanceFaultHook) : CrashTestContainer(app, hook) {
        override val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        override val processLifecycle = TestProcessLifecycle().apply { moveTo(Lifecycle.State.RESUMED) }
        override val biometricGateway = FakeBiometricGateway()

        suspend fun awaitTerminal(accept: (GateState) -> Boolean = { true }): GateState = withTimeout(GateScene.TIMEOUT_MS) {
            sessionGate.state.first { state ->
                accept(state) && (state == GateState.Ready || state == GateState.Corrupt || state == GateState.KeyUnavailable ||
                    state is GateState.NeedsPassword || state == FINGERPRINT_LOCK)
            }
        }
    }

    companion object {
        const val P1 = "old password 1"
        const val P2 = "new password 2"
        const val BACKUP_PASSWORD = "backup password 3"
        private const val KILL_TIMEOUT_S = 60L

        /** The cold-start lock of a live slot in `PASSWORD` mode (T40). */
        private val FINGERPRINT_LOCK = GateState.Locked(Resume.Open)

        private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        /** The request that runs [flow] and is killed at [step]. */
        fun requestFor(flow: CrashFlow, step: PipelineStep): CrashRequest {
            val password = flow.plant.password?.toByteArray(Charsets.US_ASCII)
            val newPassword = when {
                flow.op == CrashOp.FingerprintReset -> P2
                flow.op == CrashOp.KeyChange && flow.target == KeyMode.PASSWORD -> P2
                else -> null
            }?.toByteArray(Charsets.US_ASCII)
            val backupPassword = if (flow.encryptedBackup) BACKUP_PASSWORD.toByteArray(Charsets.US_ASCII) else null
            return CrashRequest(
                flow.op, step, flow.origin, flow.target, password, newPassword, backupPassword, flow.readBackFails,
                flow.killAfterSlotCommit,
            )
        }
    }
}

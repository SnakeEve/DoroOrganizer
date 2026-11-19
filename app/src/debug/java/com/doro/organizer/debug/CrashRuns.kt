package com.doro.organizer.debug

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.doro.organizer.AppContainer
import com.doro.organizer.data.CorruptionListener
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DefaultDatabaseHolder
import com.doro.organizer.data.FlowProblemListener
import com.doro.organizer.data.SqlcipherEngine
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.DefaultDbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.data.maintenance.MaintenanceListener
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.UnlockResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the crash test runs; [hookOp] is the `op` the pipeline passes to its fault hook (null: startup, slot commits). */
enum class CrashOp(val hookOp: MaintenanceOp?) {
    Startup(null), BackupToDocument(MaintenanceOp.Backup), BackupForShare(MaintenanceOp.Backup),
    KeyChange(MaintenanceOp.KeyChange), FingerprintReset(MaintenanceOp.KeyChange), Restore(MaintenanceOp.Restore),
    StartFresh(MaintenanceOp.StartFresh), Erase(MaintenanceOp.Erase), CreateBioSlot(null), RemoveBioSlot(null),
}

/**
 * The bind `Intent`'s extras; passwords travel as ASCII `ByteArray`s, never as strings. [backupPassword] encrypts the
 * backup or opens the restore input. [readBackFails]: the read-back after the first commit fails although the commit
 * landed (`CommitOutcome.Unknown`, rows CS-14 and CS-38); the process is then killed after the operation returned.
 * [killAfterSlotCommit]: the process is killed as soon as the commit of a biometric slot returned, before its
 * read-back (row CS-30). In both, [step] only names where the fault strikes and no hook kills.
 */
class CrashRequest(
    val op: CrashOp,
    val step: PipelineStep,
    val origin: MaintenanceOrigin = MaintenanceOrigin.Ready,
    val target: KeyMode? = null,
    val password: ByteArray? = null,
    val newPassword: ByteArray? = null,
    val backupPassword: ByteArray? = null,
    val readBackFails: Boolean = false,
    val killAfterSlotCommit: Boolean = false,
) {
    init {
        require(!(readBackFails && killAfterSlotCommit)) { "one fault per request" }
    }

    /** The fault strikes at no hook: the vault injects it. */
    val faultInVault: Boolean get() = readBackFails || killAfterSlotCommit

    fun into(intent: Intent): Intent = intent
        .putExtra(OP, op.name).putExtra(STEP, step.name).putExtra(ORIGIN, origin.name).putExtra(TARGET, target?.name)
        .putExtra(PASSWORD, password).putExtra(NEW_PASSWORD, newPassword).putExtra(BACKUP_PASSWORD, backupPassword)
        .putExtra(READ_BACK_FAILS, readBackFails).putExtra(KILL_AFTER_SLOT_COMMIT, killAfterSlotCommit)

    override fun toString() =
        "CrashRequest(op=$op, step=$step, origin=$origin, target=$target, encrypted=${backupPassword != null}, " +
            "readBackFails=$readBackFails, killAfterSlotCommit=$killAfterSlotCommit)"

    companion object {
        private const val OP = "op"
        private const val STEP = "step"
        private const val ORIGIN = "origin"
        private const val TARGET = "target"
        private const val PASSWORD = "password"
        private const val NEW_PASSWORD = "newPassword"
        private const val BACKUP_PASSWORD = "backupPassword"
        private const val READ_BACK_FAILS = "readBackFails"
        private const val KILL_AFTER_SLOT_COMMIT = "killAfterSlotCommit"

        fun from(intent: Intent) = CrashRequest(
            op = CrashOp.valueOf(checkNotNull(intent.getStringExtra(OP))),
            step = PipelineStep.valueOf(checkNotNull(intent.getStringExtra(STEP))),
            origin = MaintenanceOrigin.valueOf(checkNotNull(intent.getStringExtra(ORIGIN))),
            target = intent.getStringExtra(TARGET)?.let(KeyMode::valueOf),
            password = intent.getByteArrayExtra(PASSWORD),
            newPassword = intent.getByteArrayExtra(NEW_PASSWORD),
            backupPassword = intent.getByteArrayExtra(BACKUP_PASSWORD),
            readBackFails = intent.getBooleanExtra(READ_BACK_FAILS, false),
            killAfterSlotCommit = intent.getBooleanExtra(KILL_AFTER_SLOT_COMMIT, false),
        )
    }
}

/**
 * Production members with [hook], the test spec of the biometric seam, and no gate behind the listeners: the crash
 * test runs DbMaintenance directly. With [readBackFails] the read right after the first commit fails once; with
 * [killAfterSlotCommit] the process dies as soon as the commit of a biometric slot returned. Open, so the
 * instrumented tests replace the scope and the process lifecycle.
 */
open class CrashTestContainer(
    app: Application,
    hook: MaintenanceFaultHook,
    private val readBackFails: Boolean = false,
    private val killAfterSlotCommit: Boolean = false,
) : AppContainer(app) {
    override val faultHook: MaintenanceFaultHook = hook
    override val keyVault: KeyVault by lazy {
        val vault = crashTestVault(app, settingsStore, appTime, dispatchers)
        when {
            readBackFails -> ReadBackFailingVault(vault) { KeyVaultException(KeyFailure.InvalidState) }
                .apply { failReadAfterNextCommit = true }
            killAfterSlotCommit -> SlotCommitKillingVault(vault)
            else -> vault
        }
    }
    override val databaseHolder: DatabaseHolder by lazy {
        DefaultDatabaseHolder(app, files, dispatchers, { CorruptionListener {} }, FlowProblemListener {})
    }
    override val dbMaintenance: DbMaintenance by lazy {
        DefaultDbMaintenance(
            app, files, keyVault, databaseHolder, settingsStore, appTime, dispatchers, applicationScope, faultHook,
            listener = { NoGate },
        )
    }

    private object NoGate : MaintenanceListener {
        override fun onMaintenanceStarted(op: MaintenanceOp, fingerprintReset: Boolean) = Unit

        override fun onMaintenanceFinished(outcome: MaintenanceOutcome) = Unit
    }
}

/** The restore input the test plants, relative to `noBackupFilesDir`. */
const val CRASH_RESTORE_INPUT = "crashtest-in/input.db"

/** Created when the service starts its one run, relative to `noBackupFilesDir`; the test deletes it. */
const val CRASH_RUN_MARKER = "crashtest-in/ran"

/** Holds the failure that ended a run before its step, relative to `noBackupFilesDir`; the test deletes it. */
const val CRASH_FAILURE_MARKER = "crashtest-in/failed"

/** The backup document target, relative to `cacheDir`, opened with "wt" like any document Uri. */
const val CRASH_BACKUP_TARGET = "crashtest-out/backup.db"

/** How long [awaitBackupInputs] waits; reading the settings file and the key-state takes milliseconds. */
const val BACKUP_INPUTS_TIMEOUT_MS = 20_000L

/**
 * Waits until both inputs of `backupRequiresPassword()` are known, the key mode and the settings, as they are when
 * the Settings screen offers a backup (ui.md § 9). While either is unknown the answer is true (DEC-94), so a plaintext
 * backup without a password would be a programming error (`Err(Unexpected)`). `reconcileAtStartup` sets the key mode
 * from a valid key-state; nothing else subscribes to the settings in a container without screens. Fails with a clear
 * message when either never loads.
 */
suspend fun awaitBackupInputs(container: AppContainer) {
    val loaded = withTimeoutOrNull(BACKUP_INPUTS_TIMEOUT_MS) {
        container.dbMaintenance.keyMode.first { it != null }
        container.settingsStore.settings.first { it != null }
    }
    checkNotNull(loaded) { "the key mode or the settings never loaded" }
}

/**
 * Runs [request] on [container] as the screens would: startup reconciliation first, the live database opened for an
 * operation from `Ready`, a restore answered with the planted passwords, "Use password" for the fingerprint and a
 * confirmation, and a slot creation or removal after the lock toggle wrote `security.app_lock` first (DEC-65; rows
 * CS-29, CS-31). [scope] hosts the restore's answering collector.
 */
suspend fun runCrashRequest(context: Context, container: AppContainer, request: CrashRequest, scope: CoroutineScope) {
    SqlcipherEngine.init()
    val maintenance = container.dbMaintenance
    val vault = container.keyVault
    maintenance.reconcileAtStartup()
    if (request.op == CrashOp.Startup) {
        maintenance.completeStartup()
        return
    }
    if (request.origin == MaintenanceOrigin.Ready) {
        maintenance.completeStartup()
        val state = checkNotNull(vault.readState())
        val key = vault.unlock(request.password?.copyOf()) as UnlockResult.Key
        container.databaseHolder.open(state.generation, key.rawKey)
    }
    if (request.op == CrashOp.BackupToDocument || request.op == CrashOp.BackupForShare) awaitBackupInputs(container)
    when (request.op) {
        CrashOp.BackupToDocument -> {
            val target = File(context.cacheDir, CRASH_BACKUP_TARGET).apply { parentFile?.mkdirs() }
            maintenance.backupToDocument(Uri.fromFile(target), request.backupPassword?.chars())
        }
        CrashOp.BackupForShare -> maintenance.backupForShare(request.backupPassword?.chars())
        CrashOp.KeyChange -> maintenance.changeKey(checkNotNull(request.target), request.password?.chars(), request.newPassword?.chars())
        CrashOp.FingerprintReset -> {
            val cipher = (vault.biometricUnlockCipher() as SlotCipherResult.Ready).slotCipher
            maintenance.resetPasswordWithBiometric(request.origin, cipher, checkNotNull(request.newPassword).chars())
        }
        CrashOp.Restore -> {
            val answering = answerRestoreQuestions(maintenance, request, scope)
            maintenance.restore(Uri.fromFile(File(context.noBackupFilesDir, CRASH_RESTORE_INPUT)), request.origin)
            answering.cancel()
        }
        CrashOp.StartFresh -> maintenance.startFresh(request.origin)
        CrashOp.Erase -> maintenance.erase(request.origin)
        CrashOp.CreateBioSlot -> {
            container.settingsStore.setBiometricLock(true)
            val cipher = (vault.biometricArmCipher() as SlotCipherResult.Ready).slotCipher
            maintenance.createBiometricSlot(cipher)
        }
        CrashOp.RemoveBioSlot -> {
            container.settingsStore.setBiometricLock(false)
            maintenance.removeBiometricSlot()
        }
        CrashOp.Startup -> Unit
    }
}

private fun answerRestoreQuestions(maintenance: DbMaintenance, request: CrashRequest, scope: CoroutineScope): Job =
    scope.launch {
        maintenance.status.collect { status ->
            when (status) {
                is MaintenanceStatus.NeedsBackupPassword -> maintenance.answerPassword(request.backupPassword?.chars())
                is MaintenanceStatus.NeedsDatabasePassword -> maintenance.answerPassword(request.password?.chars())
                is MaintenanceStatus.NeedsBiometricUnlock -> maintenance.answerBiometric(null)
                is MaintenanceStatus.NeedsConfirmation -> maintenance.answerConfirmation(true)
                else -> Unit
            }
        }
    }

/** The service's operations take passwords as `CharArray`s; the test passes ASCII passwords. */
private fun ByteArray.chars() = CharArray(size) { this[it].toInt().toChar() }

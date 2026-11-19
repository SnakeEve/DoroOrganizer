package com.doro.organizer.data.maintenance

import android.content.Context
import android.net.Uri
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.coroutines.runDetached
import com.doro.organizer.core.password.PasswordCheck
import com.doro.organizer.core.password.checkPassword
import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.settings.SettingsStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The production [DbMaintenance]. Every user operation runs the operation frame of db-lifecycle.md § Operation frame:
 * detached in [applicationScope] on the IO dispatcher, `opMutex.tryLock()` (`Busy` otherwise), the temporary
 * directories emptied, the origin checked against the holder, the operation's steps, then (not cancellable) cleanup,
 * zeroing, `Finished` and `MaintenanceFinished`, and the mutex released last. [listener] is a provider, resolved at
 * each event, because SessionGate depends on this class.
 */
class DefaultDbMaintenance(
    context: Context,
    private val files: DbFiles,
    keyVault: KeyVault,
    private val holder: DatabaseHolder,
    private val settingsStore: SettingsStore,
    appTime: AppTime,
    private val dispatchers: AppDispatchers,
    private val applicationScope: CoroutineScope,
    faultHook: MaintenanceFaultHook,
    private val listener: () -> MaintenanceListener,
) : DbMaintenance {
    private val deps = MaintenanceDeps(
        context.applicationContext, files, keyVault, holder, settingsStore, appTime, faultHook,
    )
    private val documents = SafDocuments(context.applicationContext.contentResolver)
    private val share = ShareFiles(context.applicationContext, files, appTime, dispatchers, applicationScope)
    private val backups = BackupPipeline(deps, documents, share)
    private val keyChanges = KeyChangePipeline(deps, share, ::keepResetKey)
    private val slotCommits = BiometricSlotCommits(deps)
    private val startup = StartupReconciler(deps, share)

    /** Every operation, the startup functions and every key-state write hold it (db-lifecycle.md § Concurrency rules). */
    private val opMutex = Mutex()

    private val mutableStatus = MutableStateFlow<MaintenanceStatus>(MaintenanceStatus.Idle)
    override val status: StateFlow<MaintenanceStatus> = mutableStatus.asStateFlow()
    override val keyMode: StateFlow<KeyMode?> = deps.keyMode.asStateFlow()

    /** The committed key of a fingerprint reset from `NeedsPassword`, waiting for E2 (memory only, DEC-64). */
    private val resetKeyLock = Any()
    private var resetKey: Pair<Long, ByteArray>? = null

    override suspend fun reconcileAtStartup(): StartupDecision = withContext(dispatchers.io) {
        opMutex.withLock {
            discardResetKey()
            startup.reconcile()
        }
    }

    override suspend fun completeStartup(): StartupCompletion = withContext(dispatchers.io) {
        opMutex.withLock { withContext(NonCancellable) { startup.complete() } }
    }

    override fun takeResetKey(generation: Long): ByteArray? = synchronized(resetKeyLock) {
        val kept = resetKey
        resetKey = null
        if (kept?.first == generation) return kept.second
        kept?.second?.fill(0)
        null
    }

    override fun backupRequiresPassword(): Boolean =
        deps.keyMode.value != KeyMode.PLAIN || settingsStore.settings.value?.encryptBackups != false

    override suspend fun discardDocument(target: Uri) = withContext(dispatchers.io) {
        if (documents.size(target) == 0L) documents.delete(target)
    }

    override suspend fun backupToDocument(target: Uri, backupPassword: CharArray?): AppResult<MaintenanceResult> =
        applicationScope.runDetached {
            var pipelineRan = false
            val result = operation(MaintenanceOp.Backup, MaintenanceOrigin.Ready, backupPassword) { run ->
                withBackupKey(run, backupPassword) { key ->
                    pipelineRan = true
                    backups.toDocument(run, target, key)
                }
            }
            // The partial-document rule before the pipeline (Busy, the frame, the password check); the pipeline
            // applies it from there on. Only an empty document, which the picker has just created, goes.
            if (result is AppResult.Err && !pipelineRan) discardDocument(target)
            result
        }

    override suspend fun backupForShare(backupPassword: CharArray?): AppResult<MaintenanceResult> =
        operation(MaintenanceOp.Backup, MaintenanceOrigin.Ready, backupPassword) { run ->
            withBackupKey(run, backupPassword) { key -> backups.forShare(run, key) }
        }

    override suspend fun changeKey(
        target: KeyMode,
        currentPassword: CharArray?,
        newPassword: CharArray?,
    ): AppResult<MaintenanceResult> =
        operation(MaintenanceOp.KeyChange, MaintenanceOrigin.Ready, currentPassword, newPassword) { run ->
            require((newPassword != null) == (target == KeyMode.PASSWORD)) { "a new password only for PASSWORD" }
            if (newPassword == null) {
                keyChanges.changeKey(run, target, currentBytes(run, currentPassword), null)
            } else {
                withChecked(run, newPassword) { keyChanges.changeKey(run, target, currentBytes(run, currentPassword), it) }
            }
        }

    override suspend fun resetPasswordWithBiometric(
        origin: MaintenanceOrigin,
        cipher: SlotCipher,
        newPassword: CharArray,
    ): AppResult<MaintenanceResult> = operation(MaintenanceOp.KeyChange, origin, newPassword) { run ->
        // Never over Corrupt (DEC-63): any other origin is a programming error.
        require(origin == MaintenanceOrigin.NeedsPassword || origin == MaintenanceOrigin.Ready) { "reset origin" }
        withChecked(run, newPassword) { keyChanges.resetPassword(run, origin, cipher, it) }
    }

    override suspend fun createBiometricSlot(cipher: SlotCipher): AppResult<Unit> = applicationScope.runDetached {
        withContext(dispatchers.io) {
            if (!opMutex.tryLock()) return@withContext AppResult.Err(AppError.Busy)
            try {
                discardResetKey()
                slotCommits.create(cipher)
            } finally {
                opMutex.unlock()
            }
        }
    }

    override suspend fun removeBiometricSlot(): AppResult<Unit> = applicationScope.runDetached {
        withContext(dispatchers.io) {
            slotCommits.remove { if (opMutex.tryLock()) ({ opMutex.unlock() }) else null }
        }
    }

    override fun acknowledge() = mutableStatus.update { if (it is MaintenanceStatus.Finished) MaintenanceStatus.Idle else it }

    override suspend fun sweepShareFiles() = withContext(dispatchers.io) { share.sweep() }

    /**
     * The operation frame. [passwords] are the caller's arrays, which this operation owns from the call on: they are
     * zeroed in every case, `Busy` included.
     */
    private suspend fun operation(
        op: MaintenanceOp,
        origin: MaintenanceOrigin,
        vararg passwords: CharArray?,
        body: suspend (OperationRun) -> AppResult<MaintenanceResult>,
    ): AppResult<MaintenanceResult> = applicationScope.runDetached {
        withContext(dispatchers.io) {
            if (!opMutex.tryLock()) {
                passwords.forEach { it?.fill('\u0000') }
                return@withContext AppResult.Err(AppError.Busy)
            }
            val run = OperationRun(op, deps.faultHook, { mutableStatus.value = it }) { fingerprintReset ->
                withContext(dispatchers.main) { listener().onMaintenanceStarted(op, fingerprintReset) }
            }
            var result: AppResult<MaintenanceResult> = AppResult.Err(AppError.Unexpected)
            try {
                discardResetKey()
                result = frameError(origin) ?: body(run)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // With the commit started the key-state on disk may already name the new generation; before it, the
                // previous state is in force and the failure is classified by the DbMaintenance context.
                result = if (run.commitStarted) {
                    run.outcome = MaintenanceOutcome.Restart
                    AppResult.Err(AppError.OutcomeUnknown)
                } else {
                    AppResult.Err(pipelineError(t))
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        val unknown = run.outcome == MaintenanceOutcome.Restart && run.commitStarted
                        if (!unknown) deps.bestEffort("cleanup") { files.clearDir(files.stagingDir) } // else: for startup
                        passwords.forEach { it?.fill('\u0000') }
                        run.zeroSecrets()
                        mutableStatus.value = MaintenanceStatus.Finished(op, result, run.previousContentLost)
                        if (run.started) {
                            val outcome = run.outcome ?: defaultOutcome(origin)
                            withContext(dispatchers.main) { listener().onMaintenanceFinished(outcome) }
                        }
                    } finally {
                        opMutex.unlock() // last, whatever failed before it: a leaked mutex would be Busy for good
                    }
                }
            }
            result
        }
    }

    /**
     * Frame steps 3 and 4: the temporary directories are emptied (no other operation can be using them), and the
     * origin the screen passed is checked against the holder. `Ready` with a holder that is no longer open means the
     * gate left `Ready` meanwhile; any other mismatch is a programming error.
     */
    private fun frameError(origin: MaintenanceOrigin): AppResult.Err? {
        if (!files.clearDir(files.stagingDir) || !files.clearDir(files.restoreDir)) {
            return AppResult.Err(AppError.DatabaseUnavailable)
        }
        val open = holder.state.value is HolderState.Open
        return when (origin) {
            MaintenanceOrigin.Ready -> if (open) null else AppResult.Err(AppError.DatabaseUnavailable)
            else -> {
                check(holder.state.value == HolderState.Closed) { "recovery origin with an open database" }
                null
            }
        }
    }

    private fun defaultOutcome(origin: MaintenanceOrigin): MaintenanceOutcome =
        if (origin == MaintenanceOrigin.Ready) MaintenanceOutcome.Reopened else MaintenanceOutcome.Aborted

    /**
     * The backup key (db-lifecycle.md § Password rules): the passphrase bytes of [password], or `ByteArray(0)` for a
     * plaintext backup. A password is required exactly when [backupRequiresPassword] says so.
     */
    private inline fun withBackupKey(
        run: OperationRun,
        password: CharArray?,
        block: (ByteArray) -> AppResult<MaintenanceResult>,
    ): AppResult<MaintenanceResult> {
        require(password != null || !backupRequiresPassword()) { "a backup password is required" }
        return if (password == null) block(ByteArray(0)) else withChecked(run, password, block)
    }

    /**
     * The new password's own rules (security.md § 6.1: malformed, too short) before anything else runs; the dialog has
     * already compared both entries and the acknowledgement with `checkNewPassword`. The bytes are owned by [run].
     */
    private inline fun withChecked(
        run: OperationRun,
        password: CharArray,
        block: (ByteArray) -> AppResult<MaintenanceResult>,
    ): AppResult<MaintenanceResult> = when (val check = checkPassword(password)) {
        is PasswordCheck.Rejected -> AppResult.Err(AppError.InvalidInput(check.rule))
        is PasswordCheck.Ok -> block(run.own(check.bytes))
    }

    /** A malformed current password can never have been set: `KeyVault` rejects the empty array uncounted (§ 6.3). */
    private fun currentBytes(run: OperationRun, password: CharArray?): ByteArray? =
        password?.let { run.own(passwordBytes(it) ?: ByteArray(0)) }

    private fun keepResetKey(generation: Long, rawKey: ByteArray) = synchronized(resetKeyLock) {
        resetKey?.second?.fill(0)
        resetKey = generation to rawKey
    }

    /** An untaken reset key never outlives the next maintenance action (db-lifecycle.md § Interface and status). */
    private fun discardResetKey() = synchronized(resetKeyLock) {
        resetKey?.second?.fill(0)
        resetKey = null
    }

    override fun toString() = "DefaultDbMaintenance"
}

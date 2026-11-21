package com.doro.organizer.data.maintenance

import com.doro.organizer.core.password.constantTimeEquals
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.Reopen
import com.doro.organizer.data.isCorruption
import com.doro.organizer.data.nextGeneration
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.PreparedKey
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.UnlockResult
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * Key changes (DEC-61, db-lifecycle.md § Key changes, security.md § 4) and the fingerprint password reset (DEC-63,
 * § Fingerprint password reset): a new DEK, an export into a new generation, the commit with `bio = null`. Every byte
 * array handed in is owned by the frame through [OperationRun.own].
 */
internal class KeyChangePipeline(
    private val deps: MaintenanceDeps,
    private val share: ShareFiles,
    private val keepResetKey: (generation: Long, rawKey: ByteArray) -> Unit,
) {
    private val files get() = deps.files
    private val keyVault get() = deps.keyVault

    /** `changeKey`, origin `Ready`; [newPassword] is checked bytes for target `PASSWORD`, else null. */
    suspend fun changeKey(
        run: OperationRun,
        target: KeyMode,
        currentPassword: ByteArray?,
        newPassword: ByteArray?,
    ): AppResult<MaintenanceResult> {
        val state = deps.committedState() ?: return AppResult.Err(AppError.KeyUnavailable)
        val from = state.slot.mode
        require(target != from || target == KeyMode.PASSWORD) { "no key change" }
        if (from == KeyMode.PASSWORD) {
            // security.md § 4 step 2: KeyVault counts and persists the backoff; an empty array is malformed input.
            val verified = keyVault.openKey(state.slot, currentPassword ?: ByteArray(0))
            if (verified is UnlockResult.Key) verified.rawKey.fill(0) // the export uses the holder's live key
            passwordError(verified)?.let { return AppResult.Err(it) }
        }
        run.step(PipelineStep.PRECHECK)
        if (!deps.hasFreeSpace(deps.liveLength(state.generation))) return AppResult.Err(AppError.StorageFull)
        run.start()
        run.outcome = MaintenanceOutcome.Reopened
        val prepared = prepare(run, target, newPassword) ?: return AppResult.Err(AppError.KeyUnavailable)
        return exportAndCommitOpen(run, from, target, prepared)
    }

    /** The fingerprint reset's authorization: the committed state and the slot-released key, or the error. */
    private sealed interface ResetSource {
        class Unlocked(val state: KeyState, val key: ByteArray) : ResetSource {
            override fun toString() = "Unlocked"
        }

        class Refused(val error: AppError) : ResetSource
    }

    /**
     * Step 3, `UNLOCK_BIOMETRIC`, before `MaintenanceStarted`: the one authorized `doFinal` through the biometric
     * slot. The released key is owned by the frame.
     */
    private suspend fun resetSource(run: OperationRun, cipher: SlotCipher): ResetSource {
        run.step(PipelineStep.UNLOCK_BIOMETRIC)
        val state = deps.committedState() ?: return ResetSource.Refused(AppError.KeyUnavailable)
        if (state.slot.mode != KeyMode.PASSWORD || state.bioSlot == null) return ResetSource.Refused(AppError.AuthFailed)
        // A fingerprint is not a password attempt: no backoff, no WrongPassword (security.md § 5.3).
        return when (val unlocked = keyVault.unlockWithBiometric(cipher)) {
            is UnlockResult.Key -> ResetSource.Unlocked(state, run.own(unlocked.rawKey))
            is UnlockResult.SlotFailed -> ResetSource.Refused(AppError.AuthFailed)
            else -> ResetSource.Refused(AppError.KeyUnavailable)
        }
    }

    /** `resetPasswordWithBiometric`: [newPassword] is checked bytes; the frame owns it and the cipher's key. */
    suspend fun resetPassword(
        run: OperationRun,
        origin: MaintenanceOrigin,
        cipher: SlotCipher,
        newPassword: ByteArray,
    ): AppResult<MaintenanceResult> {
        val source = when (val unlocked = resetSource(run, cipher)) {
            is ResetSource.Refused -> return AppResult.Err(unlocked.error)
            is ResetSource.Unlocked -> unlocked
        }
        val state = source.state
        val sourceKey = source.key
        if (origin == MaintenanceOrigin.Ready) {
            // The slot is armed from the live key of its generation: a GCM-verified slot DEK that differs is a bug,
            // never a Keystore failure (security.md § 4.3, DEC-86).
            val live = deps.holder.copyLiveKey() ?: return AppResult.Err(AppError.DatabaseUnavailable)
            val equal = constantTimeEquals(sourceKey, live)
            live.fill(0)
            sourceKey.fill(0)
            if (!equal) return AppResult.Err(AppError.Unexpected)
        }
        run.step(PipelineStep.PRECHECK)
        if (!deps.hasFreeSpace(deps.liveLength(state.generation))) return AppResult.Err(AppError.StorageFull)
        run.start(fingerprintReset = true)
        // A failure before the commit returns the gate to the password screen the reset started from (T56, T58).
        run.outcome = MaintenanceOutcome.Aborted
        val prepared = prepare(run, KeyMode.PASSWORD, newPassword) ?: return AppResult.Err(AppError.KeyUnavailable)
        return if (origin == MaintenanceOrigin.Ready) {
            exportAndCommitOpen(run, KeyMode.PASSWORD, KeyMode.PASSWORD, prepared)
        } else {
            exportAndCommitClosed(run, state, sourceKey, prepared)
        }
    }

    /** PREPARE_KEY; null for a Keystore or password-layer failure. Zeroes [newPassword] once the KDF has run. */
    private suspend fun prepare(run: OperationRun, target: KeyMode, newPassword: ByteArray?): PreparedKey? {
        run.step(PipelineStep.PREPARE_KEY)
        val prepared = try {
            keyVault.prepare(target, newPassword)
        } catch (e: KeyVaultException) {
            return null
        } finally {
            newPassword?.fill(0)
        }
        run.own(prepared.rawKey)
        return prepared
    }

    /**
     * Key changes steps 6–15 with Room open (origin `Ready`): exclusive mode, export from the lent live key, commit,
     * reopen on the new generation. A failure before the commit keeps the outcome the caller set: `Reopened` for
     * `changeKey`, `Aborted` for the fingerprint reset.
     */
    private suspend fun exportAndCommitOpen(
        run: OperationRun,
        from: KeyMode,
        target: KeyMode,
        prepared: PreparedKey,
    ): AppResult<MaintenanceResult> {
        var oldGeneration = 0L
        var newGeneration = 0L
        var commit: CommitResult? = null
        val exported = deps.exportUnderExclusive(
            run,
            prepared.rawKey,
            target = { generation ->
                oldGeneration = generation
                newGeneration = nextGeneration(generation, files.generationNumbersInUse())
                File(files.stagingDir, "doro-$newGeneration.db")
            },
        ) { _, staged ->
            run.step(PipelineStep.VERIFY_TARGET)
            verifyTarget(staged, prepared.rawKey)
            run.commitStarted = true
            val result = commitGeneration(files, keyVault, staged, newGeneration, prepared.slot, null, run::step)
            commit = result
            when (result.outcome) {
                CommitOutcome.Committed -> {
                    deps.keyMode.value = target
                    deps.bestEffort("reopen hook") { run.step(PipelineStep.REOPEN) }
                    run.handOver(prepared.rawKey) // the holder owns it from here on
                    Reopen.Generation(newGeneration, prepared.rawKey, newData = false)
                }
                CommitOutcome.NotCommitted -> Reopen.Same
                // The key-state on disk may already name the new generation: never reopen the old one.
                CommitOutcome.Unknown -> Reopen.StayClosed
            }
        }
        val reopened = when (exported) {
            ExclusiveExport.NotRun, ExclusiveExport.Corrupt -> return AppResult.Err(AppError.DatabaseUnavailable)
            ExclusiveExport.Unknown -> return outcomeUnknown(run)
            is ExclusiveExport.Failed -> return AppResult.Err(pipelineError(exported.cause))
            is ExclusiveExport.Done -> exported.reopened
        }
        val result = checkNotNull(commit) { "commit result" }
        when (result.outcome) {
            CommitOutcome.Unknown -> return outcomeUnknown(run)
            CommitOutcome.NotCommitted -> return AppResult.Err(notCommittedError(result.cause))
            CommitOutcome.Committed -> if (reopened) run.outcome = MaintenanceOutcome.Reopened
        }
        // After a failed reopen the old generation stays: it may be the only copy known to be readable.
        afterCommit(run, target, reopened, deleteOld = setOf(oldGeneration))
        return AppResult.Ok(MaintenanceResult.KeyChanged(from, target))
    }

    /**
     * The fingerprint reset with Room closed (origin `NeedsPassword`, step 8): the slot-released key is the export
     * source, and `CHECK_SOURCE` decides whether it is the database's key at all.
     */
    private suspend fun exportAndCommitClosed(
        run: OperationRun,
        state: KeyState,
        sourceKey: ByteArray,
        prepared: PreparedKey,
    ): AppResult<MaintenanceResult> {
        val live = files.live(state.generation)
        var checkingSource = true
        var newGeneration = 0L
        val staged = try {
            run.step(PipelineStep.CHECK_SOURCE)
            // A missing or empty live file is corrupt (§ Open sequence step 2), never an empty database to export.
            if (!live.isFile || live.length() == 0L) return AppResult.Err(AppError.DatabaseUnavailable)
            // A key that is not empty never fits a plaintext file: a slot failure, not corruption (security.md § 3.7.3).
            if (RawConnections.readsAsPlaintext(live)) return slotKeyRejected()
            MaintenanceConnection.open(live, sourceKey).use { c ->
                if (!c.quickCheckOk()) return AppResult.Err(AppError.DatabaseUnavailable)
                checkingSource = false
                newGeneration = nextGeneration(state.generation, files.generationNumbersInUse())
                File(files.stagingDir, "doro-$newGeneration.db").also { file ->
                    run.step(PipelineStep.EXPORT)
                    c.exportTo(file, prepared.rawKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return when {
                checkingSource && e is SQLiteNotADatabaseException -> slotKeyRejected()
                e.isCorruption() -> AppResult.Err(AppError.DatabaseUnavailable)
                else -> AppResult.Err(pipelineError(e))
            }
        }
        sourceKey.fill(0) // its connection is closed
        try {
            run.step(PipelineStep.VERIFY_TARGET)
            verifyTarget(staged, prepared.rawKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return AppResult.Err(pipelineError(e))
        }
        run.commitStarted = true
        val result = commitGeneration(files, keyVault, staged, newGeneration, prepared.slot, null, run::step)
        return when (result.outcome) {
            CommitOutcome.NotCommitted -> AppResult.Err(notCommittedError(result.cause))
            CommitOutcome.Unknown -> outcomeUnknown(run)
            CommitOutcome.Committed -> {
                deps.keyMode.value = KeyMode.PASSWORD
                run.outcome = MaintenanceOutcome.OpenWithNewKey
                // No reopen precedes the deletion: every other set is unreferenced from the commit on.
                afterCommit(run, KeyMode.PASSWORD, reopened = true, deleteOld = files.generationsOnDisk().keys - newGeneration)
                // E2 of Opening(viaReset) opens generation n with it, so the live Room instance is still built only
                // in Opening (DEC-90); it never enters a gate state or event (DEC-64).
                run.handOver(prepared.rawKey)
                keepResetKey(newGeneration, prepared.rawKey)
                AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.PASSWORD))
            }
        }
    }

    /** The slot-released key does not open the database: the slot is dead from now on, the password path remains. */
    private suspend fun slotKeyRejected(): AppResult<MaintenanceResult> {
        keyVault.deleteBiometricKey()
        return AppResult.Err(AppError.AuthFailed)
    }

    /**
     * Steps 12–14 after `Committed`: they never undo the commit and their failures are left to startup. The biometric
     * key unwraps only the old DEK, and earlier share copies were made under the replaced key.
     */
    private suspend fun afterCommit(run: OperationRun, target: KeyMode, reopened: Boolean, deleteOld: Set<Long>) {
        if (reopened) {
            deps.bestEffort("delete old generation") {
                run.step(PipelineStep.DELETE_OLD)
                deleteOld.forEach { files.deleteGeneration(it) }
            }
        }
        if (target == KeyMode.PLAIN && reopened) {
            deps.bestEffort("delete KEK") {
                run.step(PipelineStep.DELETE_KEK)
                keyVault.deleteKek()
            }
        }
        deps.bestEffort("delete biometric key") {
            run.step(PipelineStep.DELETE_BIO_KEY)
            keyVault.deleteBiometricKey()
        }
        deps.bestEffort("cleanup") {
            run.step(PipelineStep.CLEANUP)
            files.clearDir(files.stagingDir)
            share.clear()
        }
    }

    /** security.md § 4 step 2 and architecture.md § Error model: the `UnlockResult` of the current password. */
    private fun passwordError(result: UnlockResult): AppError? = when (result) {
        is UnlockResult.Key -> null
        is UnlockResult.PasswordRejected -> AppError.WrongPassword
        is UnlockResult.Throttled -> AppError.Throttled(result.retryAtElapsedMs)
        is UnlockResult.StorageFull -> AppError.StorageFull // not checked: its attempt could not be counted (DEC-75)
        else -> AppError.KeyUnavailable
    }
}

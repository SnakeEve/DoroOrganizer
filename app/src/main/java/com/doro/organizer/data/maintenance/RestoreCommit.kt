package com.doro.organizer.data.maintenance

import com.doro.organizer.core.password.constantTimeEquals
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.Reopen
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.sha256
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Restore steps 1–12 done: a verified export at [file], keyed for the device, which the user has confirmed. */
internal class StagedRestore(
    val origin: MaintenanceOrigin,
    val state: KeyState?,
    val generation: Long,
    val file: File,
    val preview: RestorePreview,
    val keyDigest: ByteArray?,
) {
    override fun toString() = "StagedRestore(origin=$origin, generation=$generation)"
}

/**
 * Restore steps 13–21 (db-lifecycle.md § Restore, § Commit and reset): `MaintenanceStarted`, then the generation
 * commit of the confirmed export. The origin is determined again first (frame step 6).
 */
internal class RestoreCommit(private val deps: MaintenanceDeps, private val marker: DataResetMarker) {
    private val files get() = deps.files

    suspend fun swap(run: OperationRun, staged: StagedRestore): AppResult<MaintenanceResult> {
        val reopenable = staged.origin == MaintenanceOrigin.Ready && deps.holder.state.value is HolderState.Open
        run.start()
        return if (reopenable) swapOpen(run, staged) else swapClosed(run, staged)
    }

    /** Origin `Ready`: the commit runs in exclusive mode and Room reopens on the new generation with the same DEK. */
    private suspend fun swapOpen(run: OperationRun, staged: StagedRestore): AppResult<MaintenanceResult> {
        val state = checkNotNull(staged.state)
        run.outcome = MaintenanceOutcome.Reopened
        run.step(PipelineStep.CLOSE_ROOM)
        var blockRan = false
        var unknown = false
        var failure: Throwable? = null
        var commit: CommitResult? = null
        val reopened = deps.exclusive(run) { scope ->
            withContext(NonCancellable) {
                blockRan = true
                try {
                    // Step 13 (DEC-93): an export keyed with anything but the live DEK never reaches the commit.
                    check(constantTimeEquals(sha256(scope.liveKey), checkNotNull(staged.keyDigest))) { "restore key" }
                    marker.mark(run, staged.generation)
                    run.commitStarted = true
                    val result = commitGeneration(files, deps.keyVault, staged.file, staged.generation, state.slot, state.bioSlot, run::step)
                    commit = result
                    when (result.outcome) {
                        CommitOutcome.Committed -> {
                            marker.dataReset(run)
                            deps.bestEffort("reopen hook") { run.step(PipelineStep.REOPEN) }
                            Reopen.Generation(staged.generation, newKey = null, newData = true)
                        }
                        CommitOutcome.NotCommitted -> {
                            marker.clear()
                            Reopen.Same
                        }
                        // The key-state on disk may already name the new generation: never reopen the old one.
                        CommitOutcome.Unknown -> Reopen.StayClosed
                    }
                } catch (t: Throwable) {
                    if (run.commitStarted) {
                        unknown = true
                        Reopen.StayClosed
                    } else {
                        failure = t
                        Reopen.Same
                    }
                }
            }
        }
        // Frame step 6: the gate went to Corrupt between the origin check and exclusive mode, so Room is closed and
        // nothing was marked; the export is keyed for the unchanged slot and the restore continues as from Corrupt.
        if (!blockRan) return swapClosed(run, staged)
        if (reopened is OpenResult.Failed) run.outcome = MaintenanceOutcome.ReopenFailed(reopened.kind)
        if (unknown) return outcomeUnknown(run)
        failure?.let { return AppResult.Err(pipelineError(it)) }
        val result = checkNotNull(commit) { "commit result" }
        when (result.outcome) {
            CommitOutcome.Unknown -> return outcomeUnknown(run)
            CommitOutcome.NotCommitted -> return AppResult.Err(notCommittedError(result.cause))
            CommitOutcome.Committed -> Unit
        }
        // Step 18: after a failed reopen the old generation is left for startup reconciliation.
        if (reopened == OpenResult.Opened) {
            deps.bestEffort("delete old generation") {
                run.step(PipelineStep.DELETE_OLD)
                files.deleteGeneration(state.generation)
            }
        }
        deps.cleanup(run)
        return AppResult.Ok(staged.restored())
    }

    /**
     * Origins `Corrupt` and `KeyUnavailable`: Room is closed throughout. `Corrupt` keeps the slot set (same DEK);
     * `KeyUnavailable` commits a `PLAIN` slot, the one restore that changes the encryption mode (DEC-84, DEC-86).
     */
    private suspend fun swapClosed(run: OperationRun, staged: StagedRestore): AppResult<MaintenanceResult> {
        run.outcome = MaintenanceOutcome.Aborted
        val keyUnavailable = staged.origin == MaintenanceOrigin.KeyUnavailable
        val slot: KeySlot = staged.state?.slot ?: deps.keyVault.prepare(KeyMode.PLAIN, null).slot
        val bio = staged.state?.bioSlot
        marker.mark(run, staged.generation)
        run.commitStarted = true
        val result = commitGeneration(files, deps.keyVault, staged.file, staged.generation, slot, bio, run::step)
        when (result.outcome) {
            CommitOutcome.NotCommitted -> {
                marker.clear()
                return AppResult.Err(notCommittedError(result.cause))
            }
            CommitOutcome.Unknown -> return outcomeUnknown(run)
            CommitOutcome.Committed -> Unit
        }
        run.outcome = MaintenanceOutcome.Restart
        deps.keyMode.value = slot.mode
        marker.dataReset(run)
        deps.deleteGenerationsExcept(run, staged.generation)
        if (keyUnavailable) deps.deleteReplacedKeys(run)
        deps.cleanup(run)
        return AppResult.Ok(staged.restored())
    }

    private fun StagedRestore.restored() =
        MaintenanceResult.Restored(preview.notes, preview.persons, preview.projects, preview.encryptionTurnsOff)

    override fun toString() = "RestoreCommit"
}

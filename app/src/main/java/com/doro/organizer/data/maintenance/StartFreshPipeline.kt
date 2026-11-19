package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.nextGeneration
import com.doro.organizer.model.KeyMode
import java.io.File

/**
 * Start fresh (DEC-85, DEC-86, db-lifecycle.md § Start fresh) from the Corrupt or KeyUnavailable screen, Room closed:
 * an empty `PLAIN` generation is committed first, and only then are the damaged or unreadable files, the KEK and the
 * biometric key deleted. Every setting but the encryption mode and the two backoff values stays (the `PLAIN` commit
 * resets those).
 */
internal class StartFreshPipeline(private val deps: MaintenanceDeps, private val marker: DataResetMarker) {
    private val files get() = deps.files

    suspend fun startFresh(run: OperationRun): AppResult<MaintenanceResult> {
        files.ensureDirs()
        run.step(PipelineStep.PRECHECK)
        if (!deps.hasFreeSpace(0)) return AppResult.Err(AppError.StorageFull)
        run.start()
        run.outcome = MaintenanceOutcome.Aborted
        run.step(PipelineStep.CREATE_EMPTY)
        // A Missing or Invalid key-state numbers from 0 (§ Generation numbers).
        val generation = nextGeneration(deps.committedState()?.generation ?: 0, files.generationNumbersInUse())
        val staged = File(files.stagingDir, "doro-$generation.db")
        createEmptyDatabase(deps.context, staged)
        run.step(PipelineStep.VERIFY_TARGET)
        verifyTarget(staged, ByteArray(0))
        val slot = deps.keyVault.prepare(KeyMode.PLAIN, null).slot
        marker.mark(run, generation)
        run.commitStarted = true
        // This rewrites a missing or invalid key-state too: the user's explicit action (security.md § 3.5). Any
        // biometric slot goes with the old key slot.
        val result = commitGeneration(files, deps.keyVault, staged, generation, slot, null, run::step)
        when (result.outcome) {
            CommitOutcome.NotCommitted -> {
                marker.clear()
                return AppResult.Err(notCommittedError(result.cause))
            }
            CommitOutcome.Unknown -> return outcomeUnknown(run)
            CommitOutcome.Committed -> Unit
        }
        run.outcome = MaintenanceOutcome.Restart
        deps.keyMode.value = KeyMode.PLAIN
        marker.dataReset(run)
        // The deletion of the corrupted or unreadable database that spec § 5 asks for, done only now.
        deps.deleteGenerationsExcept(run, generation)
        deps.deleteReplacedKeys(run)
        deps.cleanup(run)
        return AppResult.Ok(MaintenanceResult.StartedFresh)
    }

    override fun toString() = "StartFreshPipeline"
}

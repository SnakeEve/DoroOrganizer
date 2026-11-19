package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.Reopen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Erase all data (DEC-63, security.md § 5.4, db-lifecycle.md § Erase): the key-state first, which makes the data
 * unreadable at once, then every file, then the security settings. It never runs against a live Room instance: from
 * origin `Ready` Room is closed in exclusive mode first and stays closed. There is no rollback once
 * `ERASE_KEY_STATE` started; before it, nothing was erased.
 */
internal class ErasePipeline(private val deps: MaintenanceDeps) {
    private val files get() = deps.files

    /** [origin] passed the frame's check: `Ready` with Room open, `NeedsPassword` or `Corrupt` with Room closed. */
    suspend fun erase(run: OperationRun, origin: MaintenanceOrigin): AppResult<MaintenanceResult> {
        val committed = if (origin == MaintenanceOrigin.Ready) {
            (deps.holder.state.value as? HolderState.Open)?.generation ?: return AppResult.Err(AppError.DatabaseUnavailable)
        } else {
            deps.committedState()?.generation // null (Missing or Invalid): no generation set is referenced
        }
        run.start()
        run.outcome = if (origin == MaintenanceOrigin.Ready) MaintenanceOutcome.Reopened else MaintenanceOutcome.Aborted
        // Step 2, while the key-state still names the committed generation: a leftover unreferenced plaintext
        // generation would otherwise survive an Erase that died after deleting the committed one, and be adopted.
        run.step(PipelineStep.DELETE_OLD)
        val unreferenced = files.generationsOnDisk().keys - setOfNotNull(committed)
        unreferenced.forEach { files.deleteGeneration(it) }
        if (files.generationsOnDisk().keys.any { it in unreferenced }) return AppResult.Err(AppError.DatabaseUnavailable)
        val erased = when (origin) {
            MaintenanceOrigin.Ready -> eraseUnderExclusive(run, committed)
            else -> eraseClosed(run, committed)
        }
        if (erased != null) return erased
        // Step 6: an Erase that stops before this point is completed by the first-run reset of reconciliation.
        deps.bestEffort("reset settings") {
            run.step(PipelineStep.RESET_SETTINGS)
            deps.settingsStore.resetSecurity()
            deps.settingsStore.onDataReplaced()
        }
        return AppResult.Ok(MaintenanceResult.Erased)
    }

    /** Origins `NeedsPassword` and `Corrupt`: Room was never opened or E3 closed it. Null when steps 4 and 5 ran. */
    private suspend fun eraseClosed(run: OperationRun, committed: Long?): AppResult<MaintenanceResult>? {
        val erase = Erase(run, committed)
        return try {
            withContext(NonCancellable) { erase.execute() } // no rollback once the key-state deletion started
            null
        } catch (t: Throwable) {
            if (!erase.started) throw t // nothing erased: rolled back and classified by the frame
            erase.failed()
        }
    }

    /** Origin `Ready` (step 3): Room closes in exclusive mode and stays closed once `ERASE_KEY_STATE` started. */
    private suspend fun eraseUnderExclusive(run: OperationRun, committed: Long?): AppResult<MaintenanceResult>? {
        run.step(PipelineStep.CLOSE_ROOM)
        val erase = Erase(run, committed)
        var blockRan = false
        var failure: Throwable? = null
        val reopened = deps.exclusive(run) {
            withContext(NonCancellable) {
                blockRan = true
                try {
                    erase.execute()
                    Reopen.StayClosed
                } catch (t: Throwable) {
                    failure = t
                    // The key-state may already be gone: nothing may reopen the old generation.
                    if (erase.started) Reopen.StayClosed else Reopen.Same
                }
            }
        }
        if (!blockRan) {
            // The gate left Ready for Corrupt meanwhile: nothing erased; SessionGate ignores the events (T35).
            run.outcome = MaintenanceOutcome.ReopenFailed(OpenFailureKind.Transient)
            return AppResult.Err(AppError.DatabaseUnavailable)
        }
        if (reopened is OpenResult.Failed) run.outcome = MaintenanceOutcome.ReopenFailed(reopened.kind)
        val error = failure ?: return null
        return if (erase.started) erase.failed() else AppResult.Err(pipelineError(error))
    }

    /**
     * Steps 4 and 5; [started] is set immediately before `KeyVault.eraseState()`, after the step's fault hook.
     * [committed] is the generation the key-state named, deleted last; null for a Missing or Invalid key-state.
     */
    private inner class Erase(private val operation: OperationRun, private val committed: Long?) {
        var started = false
            private set

        suspend fun execute() {
            operation.step(PipelineStep.ERASE_KEY_STATE)
            started = true
            operation.outcome = MaintenanceOutcome.Restart
            deps.keyMode.value = null
            deps.keyVault.eraseState() // the key-state, then the KEK, then the biometric key
            operation.step(PipelineStep.ERASE_FILES)
            files.deleteEverything(committed)
        }

        /**
         * A failure after the key-state deletion started: what is left is decided by the next start (crash-safety rows
         * CS-25, CS-26), so the result must not claim either outcome.
         */
        fun failed(): AppResult<MaintenanceResult> = AppResult.Err(AppError.OutcomeUnknown)

        override fun toString() = "Erase(started=$started)"
    }

    override fun toString() = "ErasePipeline"
}

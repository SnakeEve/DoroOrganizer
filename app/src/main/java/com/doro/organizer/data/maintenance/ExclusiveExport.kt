package com.doro.organizer.data.maintenance

import com.doro.organizer.data.ExclusiveScope
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.Reopen
import com.doro.organizer.data.isCorruption
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How an export of the live generation under exclusive mode ended ([exportUnderExclusive]). */
internal sealed interface ExclusiveExport {
    /** The holder was already closed, so the block never ran; outcome `ReopenFailed(Transient)`. */
    data object NotRun : ExclusiveExport

    /** The source failed `quick_check` or threw a corruption class while it was read; Room stays closed. */
    data object Corrupt : ExclusiveExport

    /** A `Throwable` escaped after `commitStarted`: Room stays closed and the caller reports the outcome unknown. */
    data object Unknown : ExclusiveExport

    /** A failure before the commit: rolled back, Room reopened on the old generation unless that reopen failed. */
    class Failed(val cause: Throwable) : ExclusiveExport {
        override fun toString() = "Failed"
    }

    /** The export and the caller's continuation ran; [reopened]: Room is open again. */
    data class Done(val reopened: Boolean) : ExclusiveExport {
        override fun toString() = "Done(reopened=$reopened)"
    }
}

/**
 * `CLOSE_ROOM` to `REOPEN` of db-lifecycle.md § Exclusive mode around an export of the live generation, keyed with
 * [exportKey], into the file [target] names for the live generation; [afterExport] continues inside exclusive mode
 * and chooses the [Reopen]. The block runs in `NonCancellable` (§ Cancellation policy): a cancellation that still
 * arrives is a failure before the commit, rolled back and rethrown by [pipelineError]. A source that is corrupt is
 * never exported and never reopened (DEC-83). Sets the outcome for [ExclusiveExport.NotRun],
 * [ExclusiveExport.Corrupt] and a failed reopen; every other outcome is the caller's.
 */
internal suspend fun MaintenanceDeps.exportUnderExclusive(
    run: OperationRun,
    exportKey: ByteArray,
    target: (generation: Long) -> File,
    afterExport: suspend (scope: ExclusiveScope, exported: File) -> Reopen,
): ExclusiveExport {
    run.step(PipelineStep.CLOSE_ROOM)
    var blockRan = false
    var corrupt = false
    var unknown = false
    var failure: Throwable? = null
    val reopened = exclusive(run) { scope ->
        withContext(NonCancellable) {
            blockRan = true
            var readingSource = true
            try {
                run.step(PipelineStep.CHECK_SOURCE)
                val exported = MaintenanceConnection.open(files.live(scope.generation), scope.liveKey).use { c ->
                    if (!c.quickCheckOk()) return@use null
                    val file = target(scope.generation)
                    run.step(PipelineStep.EXPORT)
                    c.exportTo(file, exportKey)
                    file
                }
                readingSource = false
                if (exported == null) {
                    corrupt = true
                    Reopen.StayClosed
                } else {
                    afterExport(scope, exported)
                }
            } catch (e: Throwable) {
                when {
                    // The key-state on disk may already name the new generation: never reopen the old one.
                    run.commitStarted -> {
                        unknown = true
                        Reopen.StayClosed
                    }
                    readingSource && e.isCorruption() -> {
                        corrupt = true
                        Reopen.StayClosed
                    }
                    else -> {
                        failure = e
                        Reopen.Same
                    }
                }
            }
        }
    }
    if (reopened is OpenResult.Failed) run.outcome = MaintenanceOutcome.ReopenFailed(reopened.kind)
    return when {
        !blockRan -> {
            run.outcome = MaintenanceOutcome.ReopenFailed(OpenFailureKind.Transient)
            ExclusiveExport.NotRun
        }
        corrupt -> {
            run.outcome = MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt)
            ExclusiveExport.Corrupt
        }
        unknown -> ExclusiveExport.Unknown
        else -> failure?.let { ExclusiveExport.Failed(it) } ?: ExclusiveExport.Done(reopened == OpenResult.Opened)
    }
}

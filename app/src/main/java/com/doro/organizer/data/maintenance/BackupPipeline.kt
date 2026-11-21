package com.doro.organizer.data.maintenance

import android.net.Uri
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.Reopen
import com.doro.organizer.model.backupFileName
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Backups (DEC-83, db-lifecycle.md § Backup), origin `Ready` only: always an export of the live generation, never a
 * byte copy, verified before it leaves `noBackupFilesDir/staging/`. [key] is the backup key: the passphrase bytes, or
 * `ByteArray(0)` for a plaintext backup; the frame owns and zeroes it.
 */
internal class BackupPipeline(
    private val deps: MaintenanceDeps,
    private val documents: SafDocuments,
    private val share: ShareFiles,
) {
    private val backupFile get() = File(deps.files.stagingDir, "backup.db")

    suspend fun toDocument(run: OperationRun, target: Uri, key: ByteArray): AppResult<MaintenanceResult> {
        // Only a document the picker has just created is known to be empty; anything else is never deleted before
        // the write has begun, because it may hold the user's only intact backup.
        val createdEmpty = documents.size(target) == 0L
        var streamOpened = false
        var writing = false
        val result = try {
            when (val exported = export(run, key)) {
                is AppResult.Err -> exported
                is AppResult.Ok -> {
                    writing = true
                    run.step(PipelineStep.WRITE_DOCUMENT)
                    documents.write(target, backupFile, onOpened = { streamOpened = true }) { fraction ->
                        run.progress(PipelineStep.WRITE_DOCUMENT, fraction)
                    }
                    writing = false
                    run.step(PipelineStep.CLEANUP)
                    AppResult.Ok(MaintenanceResult.BackupSaved)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Err(if (writing) documentError(e) else pipelineError(e))
        }
        if (result is AppResult.Err) {
            // The partial-document rule: once "wt" returned a stream the old content is gone and what remains is
            // partial, so the document always goes; before that, only an empty document the picker created.
            if (streamOpened || createdEmpty) documents.delete(target)
            run.previousContentLost = streamOpened && !createdEmpty
        }
        return result
    }

    suspend fun forShare(run: OperationRun, key: ByteArray): AppResult<MaintenanceResult> = try {
        when (val exported = export(run, key)) {
            is AppResult.Err -> exported
            is AppResult.Ok -> {
                run.step(PipelineStep.PUBLISH_SHARE)
                val name = backupFileName(deps.appTime.today())
                val uri = share.publish(backupFile, name)
                run.step(PipelineStep.CLEANUP)
                AppResult.Ok(MaintenanceResult.BackupShared(uri, name))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Err(pipelineError(e))
    }

    /**
     * PRECHECK to VERIFY_TARGET: the verified export at [backupFile]. Room is closed only for CHECK_SOURCE and
     * EXPORT and reopens before the verification, so the database is closed for the shortest time. A source that
     * fails `quick_check` stays closed and is reported as `ReopenFailed(Corrupt)`: no backup is ever made from it.
     */
    private suspend fun export(run: OperationRun, key: ByteArray): AppResult<Unit> {
        val generation = (deps.holder.state.value as? HolderState.Open)?.generation
            ?: return AppResult.Err(AppError.DatabaseUnavailable)
        run.step(PipelineStep.PRECHECK)
        if (!deps.hasFreeSpace(deps.liveLength(generation))) return AppResult.Err(AppError.StorageFull)
        run.start()
        run.outcome = MaintenanceOutcome.Reopened
        deps.bestEffort("share sweep") { share.clear() } // earlier share files go at the next export (DEC-83)
        val exported = deps.exportUnderExclusive(run, key, target = { backupFile }) { _, _ ->
            run.step(PipelineStep.REOPEN)
            Reopen.Same
        }
        when (exported) {
            // Unknown needs a started commit, which a backup never has.
            ExclusiveExport.NotRun, ExclusiveExport.Corrupt, ExclusiveExport.Unknown ->
                return AppResult.Err(AppError.DatabaseUnavailable)
            is ExclusiveExport.Failed -> return AppResult.Err(pipelineError(exported.cause))
            // The export is complete: a failed reopen does not stop the backup, it only changes the outcome.
            is ExclusiveExport.Done -> Unit
        }
        run.step(PipelineStep.VERIFY_TARGET)
        verifyTarget(backupFile, key)
        key.fill(0)
        return AppResult.Ok(Unit)
    }

    override fun toString() = "BackupPipeline"
}

package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.CancellationSignal
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.isCorruption
import com.doro.organizer.data.nextGeneration
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.sha256
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Restore (DEC-84, db-lifecycle.md § Restore): the picked document is copied into `restore/`, validated, migrated,
 * exported into the device's current key configuration and verified while the gate keeps its state; only after the
 * user confirmed does `MaintenanceStarted` follow and the generation commit replace the data. A rejection,
 * cancellation or failure before `Committed` leaves the live files and the key-state untouched.
 */
internal class RestorePipeline(
    private val deps: MaintenanceDeps,
    private val documents: SafDocuments,
    private val questions: RestoreQuestions,
    marker: DataResetMarker,
) {
    private val files get() = deps.files
    private val candidates = RestoreCandidates(deps, questions)
    private val deviceKeys = RestoreKeyUnlock(deps, questions)
    private val commit = RestoreCommit(deps, marker)

    suspend fun restore(run: OperationRun, source: Uri, origin: MaintenanceOrigin): AppResult<MaintenanceResult> {
        questions.begin()
        val staged = try {
            stage(run, source, origin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Step 3: whatever the user's cancel made fail (a closed descriptor, a cancelled statement, a bound that
            // raced it) is the cancel, whichever way it surfaced.
            if (questions.cancelled) return AppResult.Ok(MaintenanceResult.RestoreCancelled)
            return when (e) {
                is RestoreEnd -> e.result
                is CandidateRejected -> AppResult.Err(AppError.InvalidBackup(e.reason))
                else -> throw e
            }
        } finally {
            questions.end()
        }
        return commit.swap(run, staged)
    }

    /** Steps 1–12: nothing the user could lose changes, and only `restore/` and `staging/` are written. */
    private suspend fun stage(run: OperationRun, source: Uri, requested: MaintenanceOrigin): StagedRestore {
        // Frame step 4: Ready with a holder that is no longer open means the gate went to Corrupt meanwhile.
        var origin = requested
        if (origin == MaintenanceOrigin.Ready && deps.holder.state.value !is HolderState.Open) origin = MaintenanceOrigin.Corrupt
        // Step 1: the slot set the commit keeps; neither origin can legitimately meet a missing or invalid one.
        val state = if (origin == MaintenanceOrigin.KeyUnavailable) null else deps.committedState() ?: end(AppError.KeyUnavailable)
        files.ensureDirs()
        questions.step(run, PipelineStep.PRECHECK)
        val size = restoreInputSize(documents.size(source))
        if (size != null && !deps.hasFreeSpace(3 * size)) end(AppError.StorageFull) // the copy, the journal, the export
        copyInput(run, source, size)
        val signal = CancellationSignal().also { signal -> questions.onCancel { signal.cancel() } }
        val candidate = candidates.open(run, signal)
        val counts = candidates.validate(run, candidate, signal)
        // Step 9: the device key. A null copy means the holder closed meanwhile: the gate went to Corrupt.
        val liveOrEmpty = when (origin) {
            MaintenanceOrigin.KeyUnavailable -> ByteArray(0)
            MaintenanceOrigin.Ready -> deps.holder.copyLiveKey()?.let(run::own)
            else -> null
        }
        if (liveOrEmpty == null) origin = MaintenanceOrigin.Corrupt
        val deviceKey = liveOrEmpty ?: unlockDeviceKey(run, checkNotNull(state))
        // Step 10: the export. A Missing or Invalid key-state numbers from 0 (§ Generation numbers).
        questions.step(run, PipelineStep.EXPORT)
        val committed = state?.generation ?: deps.committedState()?.generation ?: 0
        val generation = nextGeneration(committed, files.generationNumbersInUse())
        val staged = File(files.stagingDir, "doro-$generation.db")
        export(candidate, staged, deviceKey)
        candidate.key.fill(0) // its last use
        // Step 11. Verification uses the very key the export used, so only the digest can catch a wrong source of it.
        questions.step(run, PipelineStep.VERIFY_TARGET)
        verifyTarget(staged, deviceKey)
        val keyDigest = if (origin == MaintenanceOrigin.Ready) sha256(deviceKey) else null
        deviceKey.fill(0)
        // Step 12.
        questions.step(run, PipelineStep.AWAIT_CONFIRMATION)
        val preview = RestorePreview(
            counts.notes, counts.persons, counts.projects,
            resultingMode = state?.slot?.mode ?: KeyMode.PLAIN,
            encryptionTurnsOff = origin == MaintenanceOrigin.KeyUnavailable && replacesEncryptedFiles(),
        )
        questions.confirmation { run.show(MaintenanceStatus.NeedsConfirmation(preview)) }
        // No question outlives its answer: the confirmation dialog closes before the gate enters Maintenance.
        run.show(MaintenanceStatus.Running(MaintenanceOp.Restore, PipelineStep.AWAIT_CONFIRMATION))
        return StagedRestore(origin, state, generation, staged, preview, keyDigest)
    }

    private suspend fun unlockDeviceKey(run: OperationRun, state: KeyState): ByteArray {
        questions.step(run, PipelineStep.UNLOCK_DEVICE_KEY)
        return deviceKeys.deviceKey(run, state)
    }

    /** COPY_INPUT (step 4, check 1): the private copy that alone is ever opened, bounded by size and free space. */
    private suspend fun copyInput(run: OperationRun, source: Uri, size: Long?) {
        try {
            questions.step(run, PipelineStep.COPY_INPUT)
            val copied = documents.openInput(source) { descriptor -> questions.onCancel { descriptor.close() } }.use { input ->
                candidates.incoming.outputStream().use { output ->
                    copyWithProgress(input, output, size ?: 0, { run.progress(PipelineStep.COPY_INPUT, it) }, COPY_BLOCK) {
                        questions.throwIfCancelled()
                        copyBlockError(it, size, deps::hasFreeSpace)?.let(::end)
                    }
                }
            }
            questions.throwIfCancelled() // a closed descriptor may also read as the end of the stream
            copyEndError(copied, size, deps::hasFreeSpace)?.let(::end)
        } catch (e: RestoreEnd) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            questions.throwIfCancelled()
            end(documentError(e))
        }
    }

    /**
     * EXPORT (step 10) on the maintenance connection over the candidate. The target is an empty file this build just
     * created, so a corruption class can only come from reading the candidate: it is the backup's (`Damaged`), like
     * every other read of it; anything else is classified by the DbMaintenance context.
     */
    private fun export(candidate: RestoreCandidates.Candidate, target: File, deviceKey: ByteArray) {
        try {
            MaintenanceConnection.open(candidates.incoming, candidate.key).use { it.exportTo(target, deviceKey) }
        } catch (e: SQLiteException) {
            if (e.isCorruption()) throw CandidateRejected(BackupRejection.Damaged, e)
            throw e
        }
    }

    /** Step 12: a generation set the restore replaces has a main file that is not plaintext. */
    private fun replacesEncryptedFiles(): Boolean = files.generationsOnDisk().keys.any {
        files.hasMainFile(it) && !RawConnections.isPlaintext(files.live(it))
    }

    private fun end(error: AppError): Nothing = throw RestoreEnd(AppResult.Err(error))

    override fun toString() = "RestorePipeline"

    private companion object {
        /** COPY_INPUT copies in 64 KiB blocks and checks the cancel flag and its bounds between them. */
        const val COPY_BLOCK = 64 * 1024
    }
}

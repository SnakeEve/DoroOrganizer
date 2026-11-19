package com.doro.organizer.data.maintenance

import androidx.core.util.AtomicFile
import com.doro.organizer.core.io.syncDirectory
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The data-reset marker `noBackupFilesDir/data-reset.pending` of db-lifecycle.md § Data-reset marker: the reset of
 * navigation and remembered selections happens if and only if a Restore or Start fresh was committed.
 */
internal class DataResetMarker(private val deps: MaintenanceDeps) {
    private val marker get() = AtomicFile(deps.files.resetMarker)

    /**
     * `MARK_RESET`, right before the commit: the marker names [generation] and is durable before `RENAME` and
     * `COMMIT` (AtomicFile syncs the file, not the directory entry its rename creates). A failure leaves no marker.
     */
    suspend fun mark(run: OperationRun, generation: Long) {
        run.step(PipelineStep.MARK_RESET)
        val file = marker
        try {
            val out = file.startWrite()
            try {
                out.write(generation.toString().toByteArray(Charsets.US_ASCII))
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
            syncDirectory(deps.files.noBackupDir)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** After `NotCommitted`: nothing was committed, so nothing is reset. A lost delete only repeats a harmless reset. */
    fun clear() = marker.delete()

    /** `DATA_RESET` after `Committed`; a failure leaves the marker, and startup applies it. */
    suspend fun dataReset(run: OperationRun) = deps.bestEffort("data reset") {
        run.step(PipelineStep.DATA_RESET)
        deps.settingsStore.onDataReplaced()
        clear()
    }

    /**
     * Startup reconciliation step 5: resets when the Valid key-state names the marker's generation or the marker
     * cannot be read or parsed (the safe side, DEC-94), then deletes it. Missing or Invalid: nothing was committed.
     */
    suspend fun applyAtStartup(read: KeyStateRead) {
        val file = marker
        val content = try {
            file.readFully().toString(Charsets.US_ASCII)
        } catch (e: FileNotFoundException) {
            return
        } catch (e: IOException) {
            ""
        }
        if (markerResets(read, content)) deps.settingsStore.onDataReplaced()
        file.delete()
    }

    override fun toString() = "DataResetMarker"
}

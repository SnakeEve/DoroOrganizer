package com.doro.organizer.data.maintenance

import android.annotation.SuppressLint
import android.content.Context
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DbFailure
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.ExclusiveScope
import com.doro.organizer.data.OpenResult
import com.doro.organizer.data.Reopen
import com.doro.organizer.data.classify
import com.doro.organizer.data.isStorageFull
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.settings.SettingsStore
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/** What every part of DbMaintenance shares: its collaborators and the committed key mode it publishes. */
internal class MaintenanceDeps(
    val context: Context,
    val files: DbFiles,
    val keyVault: KeyVault,
    val holder: DatabaseHolder,
    val settingsStore: SettingsStore,
    val appTime: AppTime,
    val faultHook: MaintenanceFaultHook,
) {
    val keyMode = MutableStateFlow<KeyMode?>(null)

    /** The committed key-state; null when it is missing or invalid (both are KeyUnavailable for an operation). */
    suspend fun committedState(): KeyState? = try {
        keyVault.readState()
    } catch (e: KeyVaultException) {
        null
    }

    /**
     * PRECHECK (db-lifecycle.md § Free-space precheck): [needed] bytes plus [MARGIN_BYTES] free on the filesystem of
     * `noBackupFilesDir`, which also holds `databases/` and `cacheDir`.
     */
    // usableSpace as the doc prescribes: an early, clear failure; running out later is still rolled back.
    @SuppressLint("UsableSpace")
    fun hasFreeSpace(needed: Long): Boolean = files.noBackupDir.usableSpace >= needed + MARGIN_BYTES

    /** The length of generation [generation]'s main file plus its `-wal`: what a second copy needs. */
    fun liveLength(generation: Long): Long {
        val live = files.live(generation)
        return live.length() + File(live.path + "-wal").length()
    }

    /**
     * `DatabaseHolder.exclusive` for [run]. A throw means the holder could not tell whether a commit happened, so it
     * stayed closed: the outcome is Restart and startup decides from the key-state on disk.
     */
    suspend fun exclusive(run: OperationRun, block: suspend (ExclusiveScope) -> Reopen): OpenResult? = try {
        holder.exclusive(block)
    } catch (t: Throwable) {
        run.outcome = MaintenanceOutcome.Restart
        throw t
    }

    /** Best effort after a commit or as cleanup: a failure is logged by class only and left to startup (DEC-70). */
    inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.d("maintenance: $what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Keeps the device from being driven to zero free space. */
        const val MARGIN_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Context DbMaintenance of db-lifecycle.md § Classification table, for a failure before the commit returned
 * `Committed`: storage full, a Transient class (nothing changed, trying again may work), a Keystore failure, or a
 * programming fault after the rollback.
 */
internal fun pipelineError(e: Throwable): AppError = when {
    e is CancellationException -> throw e
    e is KeyVaultException -> AppError.KeyUnavailable
    e.isStorageFull() -> AppError.StorageFull
    classify(e) == DbFailure.Transient -> AppError.DatabaseUnavailable
    else -> AppError.Unexpected
}

/**
 * `CommitOutcome.Unknown`, or a failure after `commitStarted`: the key-state on disk may already name the new
 * generation, so nothing is deleted, the marker stays and startup decides (db-lifecycle.md § Generation commit).
 */
internal fun outcomeUnknown(run: OperationRun): AppResult<MaintenanceResult> {
    run.outcome = MaintenanceOutcome.Restart
    return AppResult.Err(AppError.OutcomeUnknown)
}

/** `CommitOutcome.NotCommitted` (and a failed `RENAME`): storage full if its cause says so, else a programming fault. */
internal fun notCommittedError(cause: Throwable?): AppError =
    if (cause?.isStorageFull() == true) AppError.StorageFull else AppError.Unexpected

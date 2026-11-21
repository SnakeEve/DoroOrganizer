package com.doro.organizer.ui.common

import android.net.Uri
import androidx.annotation.StringRes
import com.doro.organizer.R
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode

/** Where a `MaintenanceStatusHost` is composed (ui.md § 8.12). */
enum class HostPlace {
    /** The shell in `Ready`: every status, with snackbars. */
    Shell,

    /** The Password, Forgot password, Corrupt and KeyUnavailable screens of an unlocked gate: dialogs only. */
    Screen,

    /** A password screen over a `Locked` gate: only the failure dialogs of a fingerprint reset or an Erase (DEC-66). */
    Locked,
}

/** What the host renders for one status (ui.md § 8.7, § 8.12, § 9.6); [isDialog] views cover the screen below. */
sealed interface HostView {
    val isDialog: Boolean get() = false

    /** Nothing here: idle, a running operation, or a status that waits for another host. */
    data object None : HostView

    /** `Ok(RestoreCancelled)`, `Err(Busy)` and `Ok(Erased)`: acknowledged without a message. */
    data object Acknowledge : HostView

    /** "Checking the backup…" before the restore's confirmation; [fraction] while the copy length is known. */
    data class Checking(val fraction: Float?) : HostView {
        override val isDialog get() = true

        override fun toString() = "Checking(fraction=$fraction)"
    }

    data class BackupPassword(val retry: Boolean) : HostView {
        override val isDialog get() = true

        override fun toString() = "BackupPassword(retry=$retry)"
    }

    data class DatabasePassword(val error: AppError?) : HostView {
        override val isDialog get() = true

        override fun toString() = "DatabasePassword(error=$error)"
    }

    /** No dialog of its own: the host asks the fingerprint through `AppLockController.slotPrompt(RestoreKey)`. */
    data class Fingerprint(val afterKekFailure: Boolean) : HostView {
        override fun toString() = "Fingerprint(afterKekFailure=$afterKekFailure)"
    }

    data class Confirm(val preview: RestorePreview) : HostView {
        override val isDialog get() = true

        override fun toString() = "Confirm($preview)"
    }

    /** A Short snackbar, then `acknowledge()`. */
    data class Snackbar(val text: UiText) : HostView {
        override fun toString() = "Snackbar($text)"
    }

    /** The snackbar of a key change, chosen with the fingerprint-reset marker when it is shown (ui.md § 9.6). */
    data class KeyChanged(val from: KeyMode, val to: KeyMode) : HostView {
        override fun toString() = "KeyChanged(from=$from, to=$to)"
    }

    /** The share sheet of a share copy, then `acknowledge()` (ui.md § 9.2). */
    class Share(val uri: Uri, val displayName: String) : HostView {
        override fun toString() = "Share" // no URI or name (DEC-70)
    }

    /** A notice with "OK" that acknowledges: Start fresh, and a restore that turned encryption off. */
    data class Notice(@param:StringRes val title: Int, @param:StringRes val body: Int) : HostView {
        override val isDialog get() = true

        override fun toString() = "Notice(title=$title, body=$body)"
    }

    /** A failure dialog with "OK" that acknowledges; [paragraphs] are the message and, for a restore, its coda. */
    data class Failure(@param:StringRes val title: Int, val paragraphs: List<UiText>) : HostView {
        override val isDialog get() = true

        override fun toString() = "Failure(title=$title, paragraphs=$paragraphs)"
    }
}

/**
 * The view of [status] for a host at [place] (ui.md § 8.12). [gateInMaintenance]: the gate shows the overlay or the
 * maintenance screen, which renders a running operation itself. [gateCorrupt]: the gate shows the Corrupt screen, so a
 * `DatabaseUnavailable` of a backup or key change means the live database failed its check (ui.md § 9.6).
 */
fun hostViewOf(
    status: MaintenanceStatus,
    place: HostPlace,
    gateInMaintenance: Boolean,
    gateCorrupt: Boolean,
): HostView {
    // While locked, the restore's questions would show counts or start a prompt over the lock: they wait (DEC-66).
    val asks = place != HostPlace.Locked
    return when (status) {
        MaintenanceStatus.Idle -> HostView.None
        is MaintenanceStatus.Running ->
            if (asks && status.op == MaintenanceOp.Restore && !gateInMaintenance) {
                HostView.Checking(status.fraction)
            } else {
                HostView.None
            }
        is MaintenanceStatus.NeedsBackupPassword -> if (asks) HostView.BackupPassword(status.retry) else HostView.None
        is MaintenanceStatus.NeedsDatabasePassword -> if (asks) HostView.DatabasePassword(status.error) else HostView.None
        is MaintenanceStatus.NeedsBiometricUnlock -> if (asks) HostView.Fingerprint(status.afterKekFailure) else HostView.None
        is MaintenanceStatus.NeedsConfirmation -> if (asks) HostView.Confirm(status.preview) else HostView.None
        is MaintenanceStatus.Finished -> finishedView(status, place, gateCorrupt)
    }
}

private fun finishedView(status: MaintenanceStatus.Finished, place: HostPlace, gateCorrupt: Boolean): HostView {
    val result = status.result
    val error = (result as? AppResult.Err)?.error
    val lockModeOp = status.op == MaintenanceOp.KeyChange || status.op == MaintenanceOp.Erase
    return when {
        // The key-change dialog shows these inline and acknowledges them itself (ui.md § 8.8).
        status.op == MaintenanceOp.KeyChange && (error == AppError.WrongPassword || error is AppError.Throttled) ->
            HostView.None
        result == AppResult.Ok(MaintenanceResult.RestoreCancelled) || error == AppError.Busy ->
            if (place != HostPlace.Locked || lockModeOp) HostView.Acknowledge else HostView.None
        error != null ->
            if (place != HostPlace.Locked || lockModeOp) failureView(status, error, gateCorrupt) else HostView.None
        // Every Ok waits for the shell, which shows it once the app is in Ready (ui.md § 9.6).
        place != HostPlace.Shell -> HostView.None
        else -> successView((result as AppResult.Ok).value)
    }
}

private fun successView(result: MaintenanceResult): HostView = when (result) {
    MaintenanceResult.BackupSaved -> HostView.Snackbar(UiText.Res(R.string.backup_done))
    is MaintenanceResult.BackupShared -> HostView.Share(result.uri, result.displayName)
    is MaintenanceResult.KeyChanged -> HostView.KeyChanged(result.from, result.to)
    is MaintenanceResult.Restored -> if (result.encryptionTurnedOff) {
        HostView.Notice(R.string.restore_done, R.string.restore_encryption_off_body)
    } else {
        HostView.Snackbar(UiText.Res(R.string.restore_done))
    }
    MaintenanceResult.StartedFresh -> HostView.Notice(R.string.start_fresh_done_title, R.string.start_fresh_done_body)
    MaintenanceResult.RestoreCancelled, MaintenanceResult.Erased -> HostView.Acknowledge
}

private fun failureView(status: MaintenanceStatus.Finished, error: AppError, gateCorrupt: Boolean): HostView.Failure {
    // The commit may have happened, so no "not changed" text may appear with it (ui.md § 9.6).
    if (error == AppError.OutcomeUnknown) {
        return HostView.Failure(R.string.outcome_unknown_title, listOf(UiText.Res(R.string.error_outcome_unknown)))
    }
    return when (status.op) {
        MaintenanceOp.Backup -> HostView.Failure(
            R.string.backup_failed_title,
            listOf(
                when {
                    status.previousContentLost -> UiText.Res(R.string.backup_error_overwritten)
                    error == AppError.FileAccess -> UiText.Res(R.string.backup_error_file_access)
                    error == AppError.DatabaseUnavailable && gateCorrupt -> UiText.Res(R.string.backup_error_damaged)
                    else -> operationText(error)
                },
            ),
        )
        MaintenanceOp.Restore -> HostView.Failure(
            R.string.restore_error_title,
            listOf(restoreErrorText(error), UiText.Res(R.string.restore_error_unchanged)),
        )
        MaintenanceOp.KeyChange -> HostView.Failure(
            R.string.key_change_failed_title,
            listOf(
                if (error == AppError.DatabaseUnavailable && gateCorrupt) {
                    UiText.Res(R.string.key_change_error_damaged)
                } else {
                    operationText(error)
                },
            ),
        )
        MaintenanceOp.StartFresh -> HostView.Failure(R.string.start_fresh_failed_title, listOf(operationText(error)))
        MaintenanceOp.Erase -> HostView.Failure(R.string.erase_failed_title, listOf(operationText(error)))
    }
}

/** The restore error message (ui.md § 8.7); `InvalidBackup` carries one text per `BackupRejection`. */
fun restoreErrorText(error: AppError): UiText = when (error) {
    AppError.FileAccess -> UiText.Res(R.string.restore_error_file_access)
    else -> operationText(error)
}

/** A maintenance failure's text: `DatabaseUnavailable` has its own (ui.md § 9.6), everything else the `AppError` text. */
private fun operationText(error: AppError): UiText =
    if (error == AppError.DatabaseUnavailable) UiText.Res(R.string.maintenance_unavailable) else UiText.Error(error)

/**
 * The snackbar of `KeyChanged(from, to)` (ui.md § 9.6): the target alone cannot tell "set" from "changed", nor
 * "turned on" from "password removed"; [fingerprintReset] is the consumed marker of a fingerprint reset (DEC-63).
 */
@StringRes
fun keyChangedText(from: KeyMode, to: KeyMode, fingerprintReset: Boolean): Int = when (to) {
    KeyMode.PLAIN -> R.string.enc_off_done
    KeyMode.DEVICE -> if (from == KeyMode.PASSWORD) R.string.pw_removed_done else R.string.enc_on_done
    KeyMode.PASSWORD -> when {
        from != KeyMode.PASSWORD -> R.string.pw_set_done
        fingerprintReset -> R.string.pw_reset_done
        else -> R.string.pw_changed_done
    }
}

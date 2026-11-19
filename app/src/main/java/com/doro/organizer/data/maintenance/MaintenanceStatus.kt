package com.doro.organizer.data.maintenance

import android.net.Uri
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode

/**
 * What DbMaintenance is doing (db-lifecycle.md § Interface and status). It survives rotation and screen changes; none
 * of these types holds user text, a password or a key.
 */
sealed interface MaintenanceStatus {
    data object Idle : MaintenanceStatus

    /** [fraction]: 0..1 while a copy of known length runs, else null. */
    data class Running(val op: MaintenanceOp, val step: PipelineStep, val fraction: Float? = null) : MaintenanceStatus {
        override fun toString() = "Running(op=$op, step=$step)"
    }

    /** A restore of an encrypted backup asks for the backup password; [retry]: the previous answer was wrong. */
    data class NeedsBackupPassword(val retry: Boolean) : MaintenanceStatus {
        override fun toString() = "NeedsBackupPassword(retry=$retry)"
    }

    /** A restore from `Corrupt` in `PASSWORD` mode asks for the database password; [error]: why it asks again. */
    data class NeedsDatabasePassword(val error: AppError?) : MaintenanceStatus {
        override fun toString() = "NeedsDatabasePassword(error=$error)"
    }

    /**
     * A restore from `Corrupt` in `PASSWORD` mode with a biometric slot: fingerprint first ([afterKekFailure] false),
     * or the DEC-86 fallback after the database-password question failed in the Keystore (KEK) layer while the slot
     * is live ([afterKekFailure] true; at most once per restore).
     */
    data class NeedsBiometricUnlock(val afterKekFailure: Boolean = false) : MaintenanceStatus {
        override fun toString() = "NeedsBiometricUnlock(afterKekFailure=$afterKekFailure)"
    }

    /** Everything that can fail on the backup's content has run; only the swap remains (DEC-84). */
    data class NeedsConfirmation(val preview: RestorePreview) : MaintenanceStatus {
        override fun toString() = "NeedsConfirmation($preview)"
    }

    /**
     * Stays until `acknowledge()` or the next operation. [previousContentLost]: a backup to a document that already
     * existed failed after `openOutputStream` had returned a stream, so its old content is gone.
     */
    data class Finished(
        val op: MaintenanceOp,
        val result: AppResult<MaintenanceResult>,
        val previousContentLost: Boolean = false,
    ) : MaintenanceStatus {
        override fun toString() = "Finished(op=$op, result=$result, previousContentLost=$previousContentLost)"
    }
}

/**
 * What the restore confirmation states (db-lifecycle.md § Confirmation): the backup's counts after migration, the
 * resulting encryption mode and [encryptionTurnsOff], true when the origin is `KeyUnavailable` and a generation file
 * the restore replaces is not plaintext; the same value as [MaintenanceResult.Restored.encryptionTurnedOff].
 */
data class RestorePreview(
    val notes: Int,
    val persons: Int,
    val projects: Int,
    val resultingMode: KeyMode,
    val encryptionTurnsOff: Boolean,
) {
    override fun toString() =
        "RestorePreview(notes=$notes, persons=$persons, projects=$projects, resultingMode=$resultingMode, " +
            "encryptionTurnsOff=$encryptionTurnsOff)"
}

sealed interface MaintenanceResult {
    data object BackupSaved : MaintenanceResult

    class BackupShared(val uri: Uri, val displayName: String) : MaintenanceResult {
        override fun toString() = "BackupShared" // no URI or name in logs (DEC-70)
    }

    /**
     * [from]: the committed mode before, [to]: the target; ui.md § 9.6 chooses the snackbar from the pair. The
     * fingerprint reset returns (PASSWORD, PASSWORD) too.
     */
    data class KeyChanged(val from: KeyMode, val to: KeyMode) : MaintenanceResult {
        override fun toString() = "KeyChanged(from=$from, to=$to)"
    }

    data class Restored(val notes: Int, val persons: Int, val projects: Int, val encryptionTurnedOff: Boolean) :
        MaintenanceResult {
        override fun toString() =
            "Restored(notes=$notes, persons=$persons, projects=$projects, encryptionTurnedOff=$encryptionTurnedOff)"
    }

    /** The user cancelled before the confirmation; the UI shows nothing. */
    data object RestoreCancelled : MaintenanceResult

    /** The UI always shows the encryption-off notice (DEC-85). */
    data object StartedFresh : MaintenanceResult

    data object Erased : MaintenanceResult
}

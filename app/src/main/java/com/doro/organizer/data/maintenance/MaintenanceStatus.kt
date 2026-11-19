package com.doro.organizer.data.maintenance

import android.net.Uri
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
}

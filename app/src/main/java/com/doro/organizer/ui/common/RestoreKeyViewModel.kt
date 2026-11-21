package com.doro.organizer.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.ui.GateGraph
import kotlinx.coroutines.launch

/**
 * The fingerprint question of a restore from the Corrupt screen (ui.md § 8.7, DEC-84, DEC-86): one prompt per
 * `NeedsBiometricUnlock` value, answered through `DbMaintenance.answerBiometric`. A ViewModel, so that the prompt and
 * its answer survive a rotation: a cancelled caller would dismiss the prompt, and the restore would wait forever. It
 * holds no secret beyond the running call; the cipher goes straight to `DbMaintenance`.
 */
class RestoreKeyViewModel(
    private val lock: AppLockController,
    private val maintenance: DbMaintenance,
) : ViewModel() {
    private var answered: MaintenanceStatus.NeedsBiometricUnlock? = null

    /**
     * Asks the fingerprint for [question] unless this very status value was asked already. After a KEK failure of
     * the database password, `reportPasswordKekFailure()` decides first whether a live slot can replace it; when it
     * cannot, the restore ends without a prompt.
     */
    fun answer(question: MaintenanceStatus.NeedsBiometricUnlock) {
        if (answered === question) return
        answered = question
        viewModelScope.launch {
            if (question.afterKekFailure && !lock.reportPasswordKekFailure()) {
                maintenance.answerBiometric(null)
                return@launch
            }
            // RestoreKey answers every failure with Ok(null): the restore then asks the password, or ends (DEC-86).
            val prompt = lock.slotPrompt(AuthPurpose.RestoreKey)
            maintenance.answerBiometric((prompt as? AppResult.Ok)?.value)
        }
    }
}

/** Built from the [GateGraph] only: the host runs on gate screens too (architecture.md § Lifetimes). */
fun restoreKeyFactory(graph: GateGraph) = viewModelFactory {
    initializer { RestoreKeyViewModel(graph.appLockController, graph.dbMaintenance) }
}

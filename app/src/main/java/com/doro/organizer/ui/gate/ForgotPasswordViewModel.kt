package com.doro.organizer.ui.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.core.messages.AppMessages
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.ui.GateGraph
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The state of the Forgot password screen: exactly these three things (DEC-90, ui.md § 8.15). */
data class ForgotPasswordUiState(
    /** The answer of `AppLockController.fingerprintUnlockAvailable()`; null until it has answered. */
    val fingerprintAvailable: Boolean? = null,
    /** True from Confirm until `slotPrompt` and, if called, `resetPasswordWithBiometric` have returned. */
    val busy: Boolean = false,
    /** The local `Err(AuthFailed)` of the prompt; every other error is reported through `DbMaintenance.status`. */
    val error: AppError? = null,
) {
    override fun toString() = "ForgotPasswordUiState(fingerprintAvailable=$fingerprintAvailable, busy=$busy, " +
        "error=${error?.let { it::class.simpleName }})"
}

/**
 * The one ViewModel of a gate screen (DEC-90, architecture.md § SessionGate invariant 2): activity-scoped, so the
 * fingerprint password reset's prompt and call survive a rotation while the system prompt is shown (ui.md § 8.15).
 * It reads no repository and keeps no password in a property or flow: the new password is a local of the running
 * call, handed to `DbMaintenance` or zeroed (security.md § 6.2).
 */
class ForgotPasswordViewModel(
    private val lock: AppLockController,
    private val maintenance: DbMaintenance,
    private val messages: AppMessages,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = mutableState.asStateFlow()

    private val startedResets = Channel<Unit>(Channel.CONFLATED)

    /**
     * One element each time a fingerprint reset starts (the cipher is handed to `DbMaintenance`). The Forgot password
     * screen closes on it, because the gate states that also close it can pass between two frames (ui.md § 10.3). It
     * is buffered, so a screen recreated by a rotation while the prompt was shown still receives it.
     */
    val resetStarted: Flow<Unit> = startedResets.receiveAsFlow()

    /**
     * The Forgot password screen opened (not recreated): a reset start no earlier screen received is dropped, because
     * that screen had already left, and the availability is asked again (ui.md § 10.3).
     */
    fun onScreenOpened() {
        startedResets.tryReceive()
        refreshAvailability()
    }

    /** Asks the controller again; each time the screen opens and after a prompt that did not authorize. */
    fun refreshAvailability() {
        if (mutableState.value.busy) return // the running reset asks again itself when it ends without a cipher
        mutableState.update { it.copy(fingerprintAvailable = null) }
        viewModelScope.launch {
            val available = lock.fingerprintUnlockAvailable()
            mutableState.update { it.copy(fingerprintAvailable = available) }
        }
    }

    /**
     * Steps 2 to 4 of ui.md § 8.15: the fingerprint authorizes the slot, then `DbMaintenance` owns the cipher and
     * [newPassword]. A prompt that does not authorize zeroes the password here and changes nothing. Unless the reset
     * succeeded, the availability is asked again: the slot may have become unusable (ui.md § 10.3). A call while
     * [ForgotPasswordUiState.busy] only zeroes [newPassword].
     */
    fun resetPassword(origin: MaintenanceOrigin, newPassword: CharArray) {
        if (mutableState.value.busy) { // a second Confirm within the same frame: one prompt at a time
            newPassword.fill('\u0000')
            return
        }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            var handedOver = false
            var result: AppResult<MaintenanceResult>? = null
            try {
                when (val prompt = lock.slotPrompt(AuthPurpose.ResetPassword)) {
                    is AppResult.Ok -> prompt.value?.let { cipher ->
                        messages.markFingerprintReset()
                        handedOver = true
                        startedResets.trySend(Unit)
                        result = maintenance.resetPasswordWithBiometric(origin, cipher, newPassword)
                    }
                    is AppResult.Err -> mutableState.update { it.copy(error = prompt.error) }
                }
            } finally {
                if (!handedOver) newPassword.fill('\u0000')
                mutableState.update { it.copy(busy = false) }
            }
            if (result !is AppResult.Ok) refreshAvailability()
        }
    }

    /** The user closed the "Encryption was not changed" dialog. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}

/** Built from the [GateGraph] only: no repository, no settings (architecture.md § Lifetimes). */
fun forgotPasswordFactory(graph: GateGraph) = viewModelFactory {
    initializer { ForgotPasswordViewModel(graph.appLockController, graph.dbMaintenance, graph.appMessages) }
}

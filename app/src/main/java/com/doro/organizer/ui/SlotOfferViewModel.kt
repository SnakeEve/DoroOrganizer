package com.doro.organizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.R
import com.doro.organizer.core.messages.AppMessages
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.security.AppLockController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Set up" of the fingerprint set-up offer (ui.md § 8.13). A ViewModel, so that the prompt `createSlot()` shows
 * survives a rotation; [busy] keeps the dialog open with its buttons disabled meanwhile. The outcome reaches the user
 * through `AppMessages`, which the shell shows. Holds no secret: the offer follows a password entry (DEC-65).
 */
class SlotOfferViewModel(
    private val lock: AppLockController,
    private val messages: AppMessages,
) : ViewModel() {
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    fun setUp() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                when (val result = lock.createSlot()) {
                    is AppResult.Ok -> if (result.value) {
                        messages.post(UserMessage.of(UiText.Res(R.string.slot_created), isError = false))
                    }
                    is AppResult.Err -> slotErrorMessage(result.error)?.let(messages::post)
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }
}

/**
 * The message of a failed slot creation (ui.md § 8.13, § 8.16): none for `Busy`; `OutcomeUnknown` has its own text,
 * because the slot-only commit never restarts the app, so the generic one would be false; every other error its
 * `AppError` text. Errors show Long.
 */
fun slotErrorMessage(error: AppError): UserMessage? = when (error) {
    AppError.Busy -> null
    AppError.OutcomeUnknown -> UserMessage.of(UiText.Res(R.string.slot_outcome_unknown), isError = true)
    else -> UserMessage.error(error)
}

fun slotOfferFactory(graph: UiGraph) = viewModelFactory {
    initializer { SlotOfferViewModel(graph.appLockController, graph.appMessages) }
}

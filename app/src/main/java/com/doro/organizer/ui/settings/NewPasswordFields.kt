package com.doro.organizer.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import com.doro.organizer.R
import com.doro.organizer.core.password.PasswordCheck
import com.doro.organizer.core.password.checkNewPassword
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.ui.common.FieldMessage
import com.doro.organizer.ui.common.PasswordField
import com.doro.organizer.ui.common.messageRes
import com.doro.organizer.ui.common.takePassword

/**
 * A new password typed twice plus the acknowledgement "I understand this cannot be recovered" (ui.md § 8.1, DEC-64).
 * The fields live in `remember`, never in saved state, so recreation empties them; only the checkbox is saveable.
 */
@Stable
internal class NewPasswordState(
    val first: TextFieldState,
    val second: TextFieldState,
    private val acknowledgement: MutableState<Boolean>,
) {
    var acknowledged: Boolean
        get() = acknowledgement.value
        set(value) {
            acknowledgement.value = value
        }

    /** The policy violation shown under the field it concerns, until the next confirm. */
    var violation by mutableStateOf<InputRule?>(null)
        private set

    val complete: Boolean get() = first.text.isNotEmpty() && second.text.isNotEmpty() && acknowledged

    /**
     * The policy of security.md § 6.1 on copies of the fields, in its rule order. A violation keeps the text and shows
     * under its field; otherwise the new password is handed over as an array and both fields are emptied.
     */
    fun take(): CharArray? {
        when (val check = checkNewPassword(first.copyChars(), second.copyChars(), acknowledged)) {
            is PasswordCheck.Rejected -> {
                violation = check.rule
                return null
            }
            is PasswordCheck.Ok -> check.bytes.fill(0)
        }
        violation = null
        second.clearText()
        return first.takePassword()
    }

    private fun TextFieldState.copyChars() = CharArray(text.length) { text[it] }
}

@Composable
internal fun rememberNewPasswordState(): NewPasswordState {
    val acknowledgement = rememberSaveable { mutableStateOf(false) }
    return remember(acknowledgement) { NewPasswordState(TextFieldState(), TextFieldState(), acknowledgement) }
}

/** The two new-password fields with [firstLabel] and [secondLabel], then the acknowledgement checkbox. */
@Composable
internal fun NewPasswordFields(state: NewPasswordState, firstLabel: String, secondLabel: String, enabled: Boolean) {
    val violation = state.violation
    PasswordField(
        state = state.first,
        label = firstLabel,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        message = violation?.takeIf { it != InputRule.PasswordMismatch }?.let { policyMessage(it) },
        imeAction = ImeAction.Next,
    )
    PasswordField(
        state = state.second,
        label = secondLabel,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        message = violation?.takeIf { it == InputRule.PasswordMismatch }?.let { policyMessage(it) },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(state.acknowledged, enabled = enabled, role = Role.Checkbox) { state.acknowledged = it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.acknowledged, onCheckedChange = null, enabled = enabled)
        Text(stringResource(R.string.password_ack))
    }
}

@Composable
private fun policyMessage(rule: InputRule) =
    FieldMessage(stringResource(AppError.InvalidInput(rule).messageRes()), isError = true)

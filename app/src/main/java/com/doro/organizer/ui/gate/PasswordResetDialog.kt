package com.doro.organizer.ui.gate

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.DialogProperties
import com.doro.organizer.R
import com.doro.organizer.core.password.PasswordCheck
import com.doro.organizer.core.password.checkNewPassword
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.FieldMessage
import com.doro.organizer.ui.common.PasswordField
import com.doro.organizer.ui.common.copyPassword
import com.doro.organizer.ui.common.takePassword

/**
 * "Set a new password" (ui.md § 8.15, DEC-63): the new startup password typed twice with the acknowledgement
 * (DEC-64). Confirm runs the policy on copies of the fields; a violation keeps the text and shows under the offending
 * field, otherwise the first field is handed to [onConfirm] and both fields are cleared. While [busy] the buttons are
 * disabled; the fingerprint prompt follows from the ViewModel.
 */
@Composable
internal fun PasswordResetDialog(busy: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var rejected by remember { mutableStateOf<InputRule?>(null) }
    val isComplete = { first.text.isNotEmpty() && second.text.isNotEmpty() && acknowledged }
    val complete = isComplete()
    val confirm: () -> Unit = confirm@{
        // Read live, not from this composition: a second tap in the same frame finds the fields already emptied.
        if (!isComplete()) return@confirm
        // checkNewPassword zeroes the copies it is given; the fields keep their text until the policy passes.
        when (val check = checkNewPassword(first.copyPassword(), second.copyPassword(), acknowledged)) {
            is PasswordCheck.Rejected -> rejected = check.rule
            is PasswordCheck.Ok -> {
                check.bytes.fill(0)
                rejected = null
                second.clearText()
                onConfirm(first.takePassword())
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.pw_reset_title)) },
        text = {
            DialogContent {
                Text(stringResource(R.string.pw_reset_body))
                Text(stringResource(R.string.pw_set_body))
                PasswordField(
                    state = first,
                    label = stringResource(R.string.password_new),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    message = rejected?.takeIf { it != InputRule.PasswordMismatch }?.let { policyMessage(it) },
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    state = second,
                    label = stringResource(R.string.password_repeat),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    message = rejected?.takeIf { it == InputRule.PasswordMismatch }?.let { policyMessage(it) },
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = acknowledged,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onValueChange = { acknowledged = it },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.password_ack))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = complete && !busy) {
                Text(stringResource(R.string.action_set_password))
            }
        },
    )
}

/** The inline text of a policy rule (ui.md § 8.1); the acknowledgement cannot fail here, the button needs it. */
@Composable
private fun policyMessage(rule: InputRule): FieldMessage? = when (rule) {
    InputRule.PasswordMalformed -> R.string.password_malformed
    InputRule.PasswordTooShort -> R.string.password_too_short
    InputRule.PasswordMismatch -> R.string.password_mismatch
    InputRule.AcknowledgementMissing, InputRule.BlankName -> null
}?.let { FieldMessage(stringResource(it), isError = true) }

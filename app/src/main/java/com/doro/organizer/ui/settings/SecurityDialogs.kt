package com.doro.organizer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.DialogProperties
import com.doro.organizer.R
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.model.KeyMode
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.PasswordDialogProperties
import com.doro.organizer.ui.common.PasswordField
import com.doro.organizer.ui.common.SecureDialogWindow
import com.doro.organizer.ui.common.currentPasswordMessage
import com.doro.organizer.ui.common.rememberRemainingMillis
import com.doro.organizer.ui.common.takePassword
import com.doro.organizer.ui.common.throttledUntil

/** The texts of a key-change dialog (ui.md § 8.8) for the committed [mode] and the biometric lock [lockOn]. */
private class KeyChangeTexts(@StringRes val title: Int, val body: List<Int>, @StringRes val confirm: Int)

private fun keyChangeTexts(flow: KeyFlow, mode: KeyMode, lockOn: Boolean): KeyChangeTexts {
    // With the lock on, a change into PASSWORD needs one touch to keep fingerprint unlock, and one out of it keeps the
    // lock with the screen lock as its fallback (DEC-65).
    val fingerprintAgain = listOfNotNull(R.string.pw_body_fingerprint_again.takeIf { lockOn })
    val lockStays = listOfNotNull(R.string.pw_remove_body_lock.takeIf { lockOn })
    return when (flow) {
        KeyFlow.EncryptOn -> KeyChangeTexts(R.string.enc_on_title, listOf(R.string.enc_on_body), R.string.action_encrypt)
        KeyFlow.SetPassword -> KeyChangeTexts(
            R.string.pw_set_title,
            listOf(R.string.pw_set_body) + listOfNotNull(R.string.pw_set_body_plain.takeIf { mode == KeyMode.PLAIN }) +
                fingerprintAgain,
            R.string.action_set_password,
        )
        KeyFlow.ChangePassword ->
            KeyChangeTexts(R.string.pw_change_title, listOf(R.string.pw_set_body) + fingerprintAgain, R.string.action_change_password)
        KeyFlow.RemovePassword ->
            KeyChangeTexts(R.string.pw_remove_title, listOf(R.string.pw_remove_body) + lockStays, R.string.action_remove_password)
        KeyFlow.EncryptOff -> KeyChangeTexts(
            R.string.enc_off_title,
            if (mode == KeyMode.PASSWORD) {
                listOf(R.string.enc_off_body, R.string.enc_off_body_password) + lockStays
            } else {
                listOf(R.string.enc_off_body)
            },
            R.string.action_turn_off,
        )
    }
}

/**
 * One dialog per key change (ui.md § 8.8). The current password is asked whenever the committed [mode] is `PASSWORD`;
 * a new one is typed twice with the acknowledgement. [error]: `WrongPassword` or `Throttled` of the last attempt,
 * shown under the current-password field, which then takes the focus again; [retryAt] is the running backoff
 * (`AppTime.elapsedMillis()` base), which disables that field and Confirm with its countdown.
 */
@Composable
internal fun KeyChangeDialog(
    flow: KeyFlow,
    mode: KeyMode,
    lockOn: Boolean,
    busy: Boolean,
    error: AppError?,
    retryAt: Long,
    appTime: AppTime,
    onConfirm: (current: CharArray?, new: CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    val texts = keyChangeTexts(flow, mode, lockOn)
    val asksCurrent = mode == KeyMode.PASSWORD
    val current = remember { TextFieldState() }
    val newPassword = rememberNewPasswordState()
    val currentFocus = remember { FocusRequester() }
    val remaining = rememberRemainingMillis(throttledUntil(retryAt, error), appTime)
    val throttled = asksCurrent && remaining > 0
    val complete = (!asksCurrent || current.text.isNotEmpty()) && (!flow.asksNew || newPassword.complete)
    val confirm = confirm@{
        if (busy || throttled || !complete) return@confirm
        val new = if (flow.asksNew) newPassword.take() ?: return@confirm else null
        onConfirm(if (asksCurrent) current.takePassword() else null, new)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = if (asksCurrent || flow.asksNew) PasswordDialogProperties else DialogProperties(),
        modifier = Modifier.testTag("key_change_dialog"),
        title = { Text(stringResource(texts.title)) },
        text = {
            SecureDialogWindow(hideOverlays = true) {
                DialogContent {
                    texts.body.forEach { Text(stringResource(it)) }
                    if (asksCurrent) {
                        // Confirm emptied every field; after a wrong current password the user types it again first
                        // (ui.md § 8.8). In the dialog's own composition, where the requester is attached.
                        LaunchedEffect(error) { if (error != null && !throttled) currentFocus.requestFocus() }
                        PasswordField(
                            state = current,
                            label = stringResource(R.string.password_current),
                            modifier = Modifier.fillMaxWidth().focusRequester(currentFocus),
                            enabled = !busy && !throttled,
                            message = currentPasswordMessage(throttled, remaining, error),
                            imeAction = if (flow.asksNew) ImeAction.Next else ImeAction.Done,
                            onSubmit = if (flow.asksNew) null else confirm,
                        )
                    }
                    if (flow.asksNew) {
                        NewPasswordFields(
                            newPassword,
                            stringResource(R.string.password_new),
                            stringResource(R.string.password_repeat),
                            enabled = !busy,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = confirm,
                enabled = !busy && !throttled && complete,
                colors = if (flow == KeyFlow.EncryptOff) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(stringResource(texts.confirm)) }
        },
    )
}

/** The texts and test tag of a lock password dialog: `lock_password_dialog` is the § 8.16 dialog only (ui.md § 12.8). */
private class LockPasswordTexts(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val confirm: Int,
    val tag: String,
)

private fun lockPasswordTexts(dialog: SettingsDialog.LockPassword): LockPasswordTexts = when (dialog) {
    SettingsDialog.LockOnPassword -> LockPasswordTexts(
        R.string.lock_on_password_title,
        R.string.lock_on_password_body,
        R.string.action_continue,
        LOCK_PASSWORD_TAG,
    )
    SettingsDialog.SlotSetupPassword -> LockPasswordTexts(
        R.string.slot_setup_password_title,
        R.string.slot_setup_password_body,
        R.string.action_continue,
        LOCK_PASSWORD_TAG,
    )
    SettingsDialog.LockOffPassword -> LockPasswordTexts(
        R.string.lock_off_password_title,
        R.string.lock_off_password_body,
        R.string.action_turn_off,
        "lock_off_password_dialog",
    )
}

private const val LOCK_PASSWORD_TAG = "lock_password_dialog"


/**
 * The startup password before the fingerprint arms the slot (ui.md § 8.16, both variants) or turns the lock off
 * (§ 8.14). It stays open, disabled, while the password is checked and while the fingerprint prompt is shown.
 */
@Composable
internal fun LockPasswordDialog(
    dialog: SettingsDialog.LockPassword,
    busy: Boolean,
    error: AppError?,
    retryAt: Long,
    appTime: AppTime,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val texts = lockPasswordTexts(dialog)
    val field = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    val remaining = rememberRemainingMillis(throttledUntil(retryAt, error), appTime)
    val throttled = remaining > 0
    val confirm = { if (!busy && !throttled && field.text.isNotEmpty()) onConfirm(field.takePassword()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = PasswordDialogProperties,
        modifier = Modifier.testTag(texts.tag),
        title = { Text(stringResource(texts.title)) },
        text = {
            SecureDialogWindow(hideOverlays = true) {
                DialogContent {
                    Text(stringResource(texts.body))
                    // In the dialog's own composition, where the requester is attached (ui.md § 8.8).
                    LaunchedEffect(error) { if (error != null && !throttled) focus.requestFocus() }
                    PasswordField(
                        state = field,
                        label = stringResource(R.string.password_label),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        enabled = !busy && !throttled,
                        message = currentPasswordMessage(throttled, remaining, error),
                        onSubmit = confirm,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = !busy && !throttled && field.text.isNotEmpty()) {
                Text(stringResource(texts.confirm))
            }
        },
    )
}

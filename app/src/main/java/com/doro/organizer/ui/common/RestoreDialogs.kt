package com.doro.organizer.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.doro.organizer.R
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode

// The restore's dialogs (ui.md § 8.7), rendered by the maintenance status host from DbMaintenance.status. The answer
// of each goes to DbMaintenance, which owns the password arrays from then on.

/** Password dialogs never close on a stray tap (ui.md § 8.1); Back still cancels. */
internal val PasswordDialogProperties = DialogProperties(dismissOnClickOutside = false)

/** "Checking the backup…" until the confirmation; Cancel and Back end the restore, nothing else dismisses it. */
@Composable
internal fun RestoreCheckingDialog(fraction: Float?, onCancel: () -> Unit) {
    SecureDialogWindow {
        AlertDialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.restore_checking)) },
            text = {
                DialogContent {
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** `NeedsBackupPassword`: [retry] after a password SQLCipher rejected, which cannot be told from a foreign file. */
@Composable
internal fun RestoreBackupPasswordDialog(retry: Boolean, onAnswer: (CharArray?) -> Unit) {
    val field = remember(retry) { TextFieldState() }
    val submit = { if (field.text.isNotEmpty()) onAnswer(field.takePassword()) }
    SecureDialogWindow {
        AlertDialog(
            onDismissRequest = { onAnswer(null) },
            properties = PasswordDialogProperties,
            title = { Text(stringResource(R.string.restore_password_title)) },
            text = {
                DialogContent {
                    Text(stringResource(R.string.restore_password_body))
                    PasswordField(
                        state = field,
                        label = stringResource(R.string.backup_password_label),
                        modifier = Modifier.fillMaxWidth().testTag("restore_password_field"),
                        message = if (retry) FieldMessage(stringResource(R.string.restore_password_retry), isError = true) else null,
                        onSubmit = submit,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = submit, enabled = field.text.isNotEmpty()) {
                    Text(stringResource(R.string.action_continue))
                }
            },
        )
    }
}

/**
 * `NeedsDatabasePassword` (a restore from the Corrupt screen in `PASSWORD` mode): [error] `WrongPassword` shows
 * "Wrong password."; `Throttled` disables the field and Continue with the countdown of the password screen.
 */
@Composable
internal fun RestoreDatabasePasswordDialog(error: AppError?, appTime: AppTime, onAnswer: (CharArray?) -> Unit) {
    val field = remember(error) { TextFieldState() }
    val remaining = rememberRemainingMillis(throttledUntil(0L, error), appTime)
    val throttled = remaining > 0
    val submit = { if (!throttled && field.text.isNotEmpty()) onAnswer(field.takePassword()) }
    SecureDialogWindow {
        AlertDialog(
            onDismissRequest = { onAnswer(null) },
            properties = PasswordDialogProperties,
            title = { Text(stringResource(R.string.restore_db_password_title)) },
            text = {
                DialogContent {
                    Text(stringResource(R.string.restore_db_password_body))
                    PasswordField(
                        state = field,
                        label = stringResource(R.string.password_label),
                        modifier = Modifier.fillMaxWidth().testTag("restore_db_password_field"),
                        enabled = !throttled,
                        message = currentPasswordMessage(throttled, remaining, error),
                        onSubmit = submit,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = submit, enabled = !throttled && field.text.isNotEmpty()) {
                    Text(stringResource(R.string.action_continue))
                }
            },
        )
    }
}

/**
 * The confirmation (DEC-84): the backup's counts, the irreversible replacement and the resulting encryption state,
 * which is stated truthfully also when a restore from the KeyUnavailable screen turns encryption off (DEC-86).
 */
@Composable
internal fun RestoreConfirmDialog(preview: RestorePreview, onAnswer: (Boolean) -> Unit) {
    SecureDialogWindow {
        AlertDialog(
            onDismissRequest = { onAnswer(false) },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                DialogContent {
                    Text(
                        stringResource(
                            R.string.restore_confirm_counts,
                            pluralStringResource(R.plurals.count_notes, preview.notes, preview.notes),
                            pluralStringResource(R.plurals.count_people, preview.persons, preview.persons),
                            pluralStringResource(R.plurals.count_projects, preview.projects, preview.projects),
                        ),
                    )
                    Text(stringResource(R.string.restore_confirm_replace))
                    val settings = if (preview.encryptionTurnsOff) {
                        R.string.restore_encryption_turns_off
                    } else {
                        R.string.restore_settings_kept
                    }
                    Text(stringResource(resultingModeText(preview.resultingMode)) + " " + stringResource(settings))
                }
            },
            dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = { onAnswer(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_restore)) }
            },
        )
    }
}

private fun resultingModeText(mode: KeyMode): Int = when (mode) {
    KeyMode.PLAIN -> R.string.restore_result_plain
    KeyMode.DEVICE -> R.string.restore_result_device
    KeyMode.PASSWORD -> R.string.restore_result_password
}

/** A dialog with a title, paragraphs of resource text and "OK" (ui.md § 8.7, § 9.6); Back and OK both close it. */
@Composable
fun MessageDialog(title: String, paragraphs: List<UiText>, appTime: AppTime, onClose: () -> Unit) {
    val resources = LocalResources.current
    SecureDialogWindow {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(title) },
            text = { DialogContent { paragraphs.forEach { Text(resources.resolve(it, appTime)) } } },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

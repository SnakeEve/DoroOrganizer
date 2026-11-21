package com.doro.organizer.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.PasswordDialogProperties
import com.doro.organizer.ui.common.SecureDialogWindow

/** The warning before every plaintext export (ui.md § 8.5, DEC-83), before the picker or the share copy. */
@Composable
internal fun PlainBackupWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("backup_plain_warning"),
        title = { Text(stringResource(R.string.backup_plain_title)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.backup_plain_body)) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_backup_anyway)) } },
    )
}

/**
 * The backup password (ui.md § 8.6): typed twice with the acknowledgement; "Back up" on the document path, "Share" on
 * the share path. It is independent of the startup password and never becomes one (DEC-83).
 */
@Composable
internal fun BackupPasswordDialog(share: Boolean, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val password = rememberNewPasswordState()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = PasswordDialogProperties,
        modifier = Modifier.testTag("backup_password_dialog"),
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            SecureDialogWindow(hideOverlays = true) {
                DialogContent {
                    Text(stringResource(R.string.backup_password_body))
                    NewPasswordFields(
                        password,
                        stringResource(R.string.backup_password_label),
                        stringResource(R.string.backup_password_repeat),
                        enabled = true,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(onClick = { password.take()?.let(onConfirm) }, enabled = password.complete) {
                Text(stringResource(if (share) R.string.action_share else R.string.action_backup))
            }
        },
    )
}

/** "Back up first?" before a first startup password (DEC-73); Back and a tap outside change nothing. */
@Composable
internal fun BackupOfferDialog(onBackUp: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pw_backup_offer_title)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.pw_backup_offer_body)) } } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) } },
        confirmButton = { TextButton(onClick = onBackUp) { Text(stringResource(R.string.action_backup_now)) } },
    )
}

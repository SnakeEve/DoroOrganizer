package com.doro.organizer.testing

import androidx.compose.runtime.Composable
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.KeyMode
import com.doro.organizer.ui.common.EntityDeleteDialog
import com.doro.organizer.ui.common.MessageDialog
import com.doro.organizer.ui.common.RestoreBackupPasswordDialog
import com.doro.organizer.ui.common.RestoreCheckingDialog
import com.doro.organizer.ui.common.RestoreConfirmDialog
import com.doro.organizer.ui.common.RestoreDatabasePasswordDialog
import com.doro.organizer.ui.gate.EraseDialog
import com.doro.organizer.ui.gate.PasswordResetDialog
import com.doro.organizer.ui.gate.StartFreshDialog
import com.doro.organizer.ui.notes.RenameEntityDialog
import com.doro.organizer.ui.settings.BackupOfferDialog
import com.doro.organizer.ui.settings.BackupPasswordDialog
import com.doro.organizer.ui.settings.KeyChangeDialog
import com.doro.organizer.ui.settings.KeyFlow
import com.doro.organizer.ui.settings.LockPasswordDialog
import com.doro.organizer.ui.settings.OpenSourceNoticesDialog
import com.doro.organizer.ui.settings.PlainBackupWarningDialog
import com.doro.organizer.ui.settings.SettingsDialog

/** A dialog that a test shows alone (`showDialog`); [security] marks the security dialogs of security.md § 9.4. */
class CatalogDialog(val name: String, val security: Boolean, val content: @Composable () -> Unit)

/**
 * Every dialog of ui.md § 8–§ 10 that a test can show alone, each in a typical state: the security dialogs of
 * security.md § 9.4, which hide overlays, then the others, the rename and the shared delete dialog of DEC-113 among
 * them. The shell's dialogs need the shell (`visitShellDialogs`), and the list's and the editor's need their screens
 * (`visitListWindows`).
 */
fun dialogCatalog(appTime: AppTime, dispatchers: AppDispatchers): List<CatalogDialog> =
    KeyFlow.entries.map { flow -> CatalogDialog("key change $flow", security = true) { KeyChange(flow, appTime) } } +
        listOf(
            SettingsDialog.LockOffPassword to "turn off with password",
            SettingsDialog.LockOnPassword to "password first, lock on",
            SettingsDialog.SlotSetupPassword to "password first, set up",
        ).map { (dialog, name) ->
            CatalogDialog(name, security = true) {
                LockPasswordDialog(
                    dialog, busy = false, error = null, retryAt = 0L, appTime, onConfirm = {}, onDismiss = {},
                )
            }
        } +
        listOf(
            CatalogDialog("fingerprint password reset", security = true) {
                PasswordResetDialog(busy = false, onDismiss = {}, onConfirm = {})
            },
            CatalogDialog("backup password", security = true) {
                BackupPasswordDialog(share = false, onConfirm = {}, onDismiss = {})
            },
            CatalogDialog("restore password", security = true) {
                RestoreBackupPasswordDialog(retry = true, onAnswer = {})
            },
            CatalogDialog("restore startup password", security = true) {
                RestoreDatabasePasswordDialog(error = null, appTime, onAnswer = {})
            },
            CatalogDialog("restore confirmation", security = true) {
                RestoreConfirmDialog(RestorePreview(3, 1, 1, KeyMode.PLAIN, encryptionTurnsOff = false), onAnswer = {})
            },
            CatalogDialog("Start fresh", security = true) { StartFreshDialog(onDismiss = {}, onConfirm = {}) },
            CatalogDialog("Erase", security = true) { EraseDialog(onDismiss = {}, onConfirm = {}) },
            CatalogDialog("plaintext backup warning", security = false) {
                PlainBackupWarningDialog(onConfirm = {}, onDismiss = {})
            },
            CatalogDialog("back up first", security = false) {
                BackupOfferDialog(onBackUp = {}, onSkip = {}, onDismiss = {})
            },
            CatalogDialog("checking the backup", security = false) {
                RestoreCheckingDialog(fraction = null, onCancel = {})
            },
            // Every error dialog of ui.md § 9.6, "Encryption was not changed" among them, is this one.
            CatalogDialog("message", security = false) {
                MessageDialog(MESSAGE_TITLE, listOf(UiText.Error(AppError.FileAccess)), appTime, onClose = {})
            },
            CatalogDialog("open-source notices", security = false) {
                OpenSourceNoticesDialog(dispatchers, onClose = {})
            },
            // Without the one-shot focus request, so no keyboard comes up (DEC-113).
            CatalogDialog("rename person", security = false) {
                RenameEntityDialog(
                    EntityKind.Person, CATALOG_NAME, focusOnStart = false, onFocusedOnStart = {}, onTextChange = {},
                    onConfirm = {}, onDismiss = {},
                )
            },
            CatalogDialog("delete person", security = false) {
                EntityDeleteDialog(EntityKind.Person, CATALOG_NAME, n = 12, m = 4, onConfirm = {}, onDismiss = {})
            },
        )

/** The dialog of [flow] from the mode it starts in; the current password is asked in `PASSWORD` mode. */
@Composable
private fun KeyChange(flow: KeyFlow, appTime: AppTime) {
    val mode = when (flow) {
        KeyFlow.EncryptOn -> KeyMode.PLAIN
        KeyFlow.SetPassword -> KeyMode.DEVICE
        KeyFlow.ChangePassword, KeyFlow.RemovePassword, KeyFlow.EncryptOff -> KeyMode.PASSWORD
    }
    KeyChangeDialog(
        flow, mode, lockOn = false, busy = false, error = null, retryAt = 0L, appTime,
        onConfirm = { _, _ -> }, onDismiss = {},
    )
}

/** Literal copied from ui.md § 11. */
private const val MESSAGE_TITLE = "Encryption was not changed"

/** The person the rename and delete dialogs show (ui.md § 8.4 example). */
private const val CATALOG_NAME = "Alice"

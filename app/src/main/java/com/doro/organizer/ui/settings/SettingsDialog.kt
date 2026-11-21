package com.doro.organizer.ui.settings

import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.AuthPurpose

/** The key changes Settings offers (ui.md § 8.8); the fingerprint reset is the Forgot password screen's (DEC-63). */
enum class KeyFlow(val target: KeyMode) {
    /** `PLAIN` → `DEVICE`. */
    EncryptOn(KeyMode.DEVICE),

    /** `PLAIN` or `DEVICE` → `PASSWORD`. */
    SetPassword(KeyMode.PASSWORD),

    /** `PASSWORD` → `PASSWORD`. */
    ChangePassword(KeyMode.PASSWORD),

    /** `PASSWORD` → `DEVICE`. */
    RemovePassword(KeyMode.DEVICE),

    /** `DEVICE` or `PASSWORD` → `PLAIN`. */
    EncryptOff(KeyMode.PLAIN),
    ;

    /** A new password, typed twice with the acknowledgement (DEC-64). */
    val asksNew: Boolean get() = target == KeyMode.PASSWORD
}

/** The open dialog of Settings (ui.md § 9.8): part of the UiState, saved as a String key, never with field contents. */
sealed interface SettingsDialog {
    /** The unencrypted-backup warning (ui.md § 8.5) before the document picker or, with [share], the share copy. */
    data class PlainBackupWarning(val share: Boolean) : SettingsDialog {
        override fun toString() = "PlainBackupWarning(share=$share)"
    }

    /** The backup password (ui.md § 8.6): after the picker on the document path, before the copy with [share]. */
    data class BackupPassword(val share: Boolean) : SettingsDialog {
        override fun toString() = "BackupPassword(share=$share)"
    }

    /** "Back up first?" before a first startup password (DEC-73). */
    data object BackupOffer : SettingsDialog

    data class KeyChange(val flow: KeyFlow) : SettingsDialog {
        override fun toString() = "KeyChange(flow=$flow)"
    }

    /** A dialog that hands the startup password to `AppLockController.confirmWithPassword` for [purpose]. */
    sealed interface LockPassword : SettingsDialog {
        val purpose: AuthPurpose
    }

    /** The startup password before the lock turns on in `PASSWORD` mode (ui.md § 8.16, EnableLock). */
    data object LockOnPassword : LockPassword {
        override val purpose get() = AuthPurpose.EnableLock
    }

    /** The startup password before "Set up" creates the biometric slot (ui.md § 8.16, SetUpSlot). */
    data object SlotSetupPassword : LockPassword {
        override val purpose get() = AuthPurpose.SetUpSlot
    }

    /** The startup password that turns the lock off in `PASSWORD` mode (ui.md § 8.14). */
    data object LockOffPassword : LockPassword {
        override val purpose get() = AuthPurpose.DisableLock
    }

    /** The open-source notices (ui.md § 9.7). */
    data object Notices : SettingsDialog
}

private const val PLAIN_WARNING = "PlainBackupWarning"
private const val BACKUP_PASSWORD = "BackupPassword"
private const val KEY_CHANGE = "KeyChange"
private const val SHARE = "share"
private const val DOCUMENT = "document"

/** The saved form of a [SettingsDialog], for example `"KeyChange:SetPassword"`. */
internal fun SettingsDialog.toKey(): String = when (this) {
    is SettingsDialog.PlainBackupWarning -> "$PLAIN_WARNING:${pathOf(share)}"
    is SettingsDialog.BackupPassword -> "$BACKUP_PASSWORD:${pathOf(share)}"
    SettingsDialog.BackupOffer -> "BackupOffer"
    is SettingsDialog.KeyChange -> "$KEY_CHANGE:${flow.name}"
    SettingsDialog.LockOnPassword -> "LockOnPassword"
    SettingsDialog.SlotSetupPassword -> "SlotSetupPassword"
    SettingsDialog.LockOffPassword -> "LockOffPassword"
    SettingsDialog.Notices -> "Notices"
}

/** The [SettingsDialog] of a saved [key]; null for none, or for a key this version does not know. */
internal fun settingsDialogOf(key: String?): SettingsDialog? {
    val name = key?.substringBefore(':') ?: return null
    val arg = key.substringAfter(':', missingDelimiterValue = "")
    return when (name) {
        PLAIN_WARNING -> shareOf(arg)?.let(SettingsDialog::PlainBackupWarning)
        BACKUP_PASSWORD -> shareOf(arg)?.let(SettingsDialog::BackupPassword)
        "BackupOffer" -> SettingsDialog.BackupOffer
        KEY_CHANGE -> KeyFlow.entries.firstOrNull { it.name == arg }?.let(SettingsDialog::KeyChange)
        "LockOnPassword" -> SettingsDialog.LockOnPassword
        "SlotSetupPassword" -> SettingsDialog.SlotSetupPassword
        "LockOffPassword" -> SettingsDialog.LockOffPassword
        "Notices" -> SettingsDialog.Notices
        else -> null
    }
}

private fun pathOf(share: Boolean) = if (share) SHARE else DOCUMENT

private fun shareOf(arg: String): Boolean? = when (arg) {
    SHARE -> true
    DOCUMENT -> false
    else -> null
}

package com.doro.organizer.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.doro.organizer.R

/** A string resource and its text in the strings table of ui.md § 11. */
internal sealed interface TableString {
    data class Single(@param:StringRes val id: Int, val text: String) : TableString

    /** A plural resource: its `one` and `other` items, with `%d` unformatted. */
    data class Plural(@param:PluralsRes val id: Int, val one: String, val other: String) : TableString
}

/**
 * Every text that a dialog shows (testing.md I-34): the rows of ui.md § 11 used in a dialog, confirmation, notice,
 * offer, warning or impact sentence, by a dialog's password fields or by the batch date picker, every text of the
 * system biometric prompts of ui.md § 8.11 (titles and negative buttons), and every error text of § 11.13, which the
 * error dialogs of § 9.6 show. Sheets and full-screen pickers are not dialogs. Literals copied from ui.md § 11.
 */
internal val DIALOG_STRINGS: List<TableString> = listOf(
    TableString.Single(R.string.action_close, "Close"),
    TableString.Single(R.string.action_cancel, "Cancel"),
    TableString.Single(R.string.action_ok, "OK"),
    TableString.Single(R.string.action_delete, "Delete"),
    TableString.Single(R.string.action_continue, "Continue"),
    TableString.Plural(R.plurals.dialog_batch_delete_title, "Delete %d note?", "Delete %d notes?"),
    TableString.Single(R.string.dialog_irreversible, "This cannot be undone."),
    TableString.Single(R.string.dialog_delete_note_title, "Delete this note?"),
    TableString.Single(R.string.dialog_set_date_title, "Set date"),
    TableString.Single(R.string.action_remove_date, "Remove date"),
    TableString.Single(R.string.dialog_delete_person_title, "Delete person \"%1\$s\"?"),
    TableString.Single(R.string.dialog_delete_project_title, "Delete project \"%1\$s\"?"),
    TableString.Single(R.string.dialog_rename_person_title, "Rename person"),
    TableString.Single(R.string.dialog_rename_project_title, "Rename project"),
    TableString.Single(R.string.rename_name_label, "Name"),
    TableString.Single(R.string.action_rename, "Rename"),
    TableString.Single(R.string.impact_person_not_linked, "This person is not linked to any note."),
    TableString.Single(R.string.impact_project_not_linked, "This project is not linked to any note."),
    TableString.Plural(
        R.plurals.impact_person_linked,
        "This person is linked to %d note.",
        "This person is linked to %d notes.",
    ),
    TableString.Plural(
        R.plurals.impact_project_linked,
        "This project is linked to %d note.",
        "This project is linked to %d notes.",
    ),
    TableString.Plural(
        R.plurals.impact_none_orphaned,
        "That note keeps other links and stays out of Orphans.",
        "All of them keep other links and stay out of Orphans.",
    ),
    TableString.Plural(
        R.plurals.impact_all_orphaned,
        "That note will have no person or project left and will move to Orphans.",
        "All of them will have no person or project left and will move to Orphans.",
    ),
    TableString.Plural(
        R.plurals.impact_some_orphaned,
        "%d of them will have no person or project left and will move to Orphans.",
        "%d of them will have no person or project left and will move to Orphans.",
    ),
    TableString.Single(R.string.open_source_notices, "Open-source notices"),
    TableString.Single(R.string.lock_auto_off_title, "Biometric lock turned off"),
    TableString.Single(
        R.string.lock_auto_off_body,
        "This device no longer has a screen lock, so the biometric lock was turned off. Set a screen lock in " +
            "system settings to use it again.",
    ),
    TableString.Single(R.string.backup_plain_title, "Back up without encryption?"),
    TableString.Single(
        R.string.backup_plain_body,
        "This backup will not be encrypted. Anyone who gets the file can read all your notes, people and " +
            "projects. To protect backups with a password, turn on Encrypt backups in Settings.",
    ),
    TableString.Single(R.string.action_backup_anyway, "Back up anyway"),
    TableString.Single(R.string.backup_password_title, "Protect the backup"),
    TableString.Single(
        R.string.backup_password_body,
        "The backup file will be encrypted with this password. You need it to restore the backup, and it " +
            "cannot be recovered. A long passphrase of several words is safest.",
    ),
    TableString.Single(R.string.backup_password_label, "Backup password"),
    TableString.Single(R.string.backup_password_repeat, "Repeat backup password"),
    TableString.Single(R.string.action_backup, "Back up"),
    TableString.Single(R.string.action_share, "Share"),
    TableString.Single(R.string.backup_failed_title, "Backup failed"),
    TableString.Single(
        R.string.backup_error_damaged,
        "The database is damaged, so no backup was made. Your earlier backups are not affected.",
    ),
    TableString.Single(
        R.string.backup_error_file_access,
        "The backup file could not be written. If an incomplete file remains at the chosen location, delete it.",
    ),
    TableString.Single(
        R.string.backup_error_overwritten,
        "The backup could not be completed, and the previous content of the file you chose was lost. Delete " +
            "the incomplete file if it remains, choose another location and try again.",
    ),
    TableString.Single(R.string.outcome_unknown_title, "Please check your data"),
    TableString.Single(
        R.string.maintenance_unavailable,
        "The operation could not run right now. Nothing was changed. Try again.",
    ),
    TableString.Single(R.string.restore_checking, "Checking the backup…"),
    TableString.Single(R.string.restore_password_title, "Encrypted backup"),
    TableString.Single(
        R.string.restore_password_body,
        "Enter the password that was set when this backup was made.",
    ),
    TableString.Single(
        R.string.restore_password_retry,
        "Wrong password, or the file is not a Doro Organizer backup.",
    ),
    TableString.Single(R.string.restore_confirm_title, "Restore this backup?"),
    TableString.Single(R.string.restore_confirm_counts, "The backup contains %1\$s, %2\$s and %3\$s."),
    TableString.Plural(R.plurals.count_notes, "%d note", "%d notes"),
    TableString.Plural(R.plurals.count_people, "%d person", "%d people"),
    TableString.Plural(R.plurals.count_projects, "%d project", "%d projects"),
    TableString.Single(
        R.string.restore_confirm_replace,
        "Everything now in the app will be replaced. This cannot be undone.",
    ),
    TableString.Single(
        R.string.restore_result_plain,
        "After the restore, your data will be stored unencrypted on this device.",
    ),
    TableString.Single(
        R.string.restore_result_device,
        "After the restore, your data will be encrypted on this device.",
    ),
    TableString.Single(
        R.string.restore_result_password,
        "After the restore, your data will be encrypted and the app will still ask for your current startup " +
            "password.",
    ),
    TableString.Single(R.string.restore_settings_kept, "Your security settings stay as they are."),
    TableString.Single(
        R.string.restore_encryption_turns_off,
        "Database encryption will be turned off. You can turn it on again in Settings afterwards.",
    ),
    TableString.Single(R.string.action_restore, "Restore"),
    TableString.Single(R.string.restore_error_title, "This file cannot be restored"),
    TableString.Single(R.string.restore_error_file_access, "The selected file could not be read."),
    TableString.Single(R.string.restore_error_not_backup, "This file is not a Doro Organizer backup."),
    TableString.Single(
        R.string.restore_error_newer,
        "This backup was made by a newer version of Doro Organizer. Update the app, then try again.",
    ),
    TableString.Single(
        R.string.restore_error_incompatible,
        "This backup does not match this version of Doro Organizer.",
    ),
    TableString.Single(R.string.restore_error_damaged, "This backup is damaged or incomplete."),
    TableString.Single(R.string.restore_error_unchanged, "Your current data was not changed."),
    TableString.Single(R.string.restore_done, "Backup restored"),
    TableString.Single(
        R.string.restore_encryption_off_body,
        "Database encryption is now off. You can turn it on again in Settings.",
    ),
    TableString.Single(R.string.password_label, "Password"),
    TableString.Single(R.string.password_current, "Current password"),
    TableString.Single(R.string.password_new, "New password"),
    TableString.Single(R.string.password_repeat, "Repeat new password"),
    TableString.Single(R.string.password_ack, "I understand this cannot be recovered"),
    TableString.Single(R.string.password_show, "Show password"),
    TableString.Single(R.string.password_hide, "Hide password"),
    TableString.Single(R.string.password_too_short, "Use at least 8 characters."),
    TableString.Single(R.string.password_mismatch, "The passwords do not match."),
    TableString.Single(R.string.password_malformed, "This password contains characters that cannot be used."),
    TableString.Single(R.string.password_wrong, "Wrong password."),
    TableString.Single(R.string.password_throttled, "Too many attempts. Try again in %1\$s."),
    TableString.Single(R.string.enc_on_title, "Encrypt the database?"),
    TableString.Single(
        R.string.enc_on_body,
        "Your notes, people and projects will be encrypted with a key kept in this device's secure hardware. " +
            "Backups and shared copies made earlier are not changed. You can add a startup password later.",
    ),
    TableString.Single(R.string.action_encrypt, "Encrypt"),
    TableString.Single(R.string.pw_set_title, "Set a startup password"),
    TableString.Single(
        R.string.pw_set_body,
        "You will enter this password when the app starts, unless fingerprint unlock is on. It cannot be " +
            "recovered: if you forget it and cannot use your fingerprint, the only way back in is to erase all " +
            "data.",
    ),
    TableString.Single(
        R.string.pw_body_fingerprint_again,
        "Afterwards, touch the fingerprint sensor once to keep fingerprint unlock.",
    ),
    TableString.Single(R.string.pw_reset_title, "Set a new password"),
    TableString.Single(
        R.string.pw_reset_body,
        "Choose a new startup password. It replaces the old one; your notes stay as they are. After you " +
            "confirm, touch the fingerprint sensor.",
    ),
    TableString.Single(
        R.string.pw_set_body_plain,
        "This also encrypts the database. Backups and shared copies made earlier are not changed.",
    ),
    TableString.Single(R.string.pw_backup_offer_title, "Back up first?"),
    TableString.Single(
        R.string.pw_backup_offer_body,
        "If you ever forget the startup password and cannot use your fingerprint, a backup is the only way " +
            "back to your notes. Make one now?",
    ),
    TableString.Single(R.string.action_backup_now, "Back up now"),
    TableString.Single(R.string.action_skip, "Skip"),
    TableString.Single(R.string.action_set_password, "Set password"),
    TableString.Single(R.string.pw_change_title, "Change the startup password"),
    TableString.Single(R.string.action_change_password, "Change password"),
    TableString.Single(R.string.pw_remove_title, "Remove the startup password?"),
    TableString.Single(
        R.string.pw_remove_body,
        "The app will open without asking for a password. The database stays encrypted on this device.",
    ),
    TableString.Single(R.string.action_remove_password, "Remove password"),
    TableString.Single(R.string.enc_off_title, "Turn off encryption?"),
    TableString.Single(
        R.string.enc_off_body,
        "Your notes, people and projects will be stored unencrypted on this device.",
    ),
    TableString.Single(R.string.enc_off_body_password, "The startup password will be removed too."),
    TableString.Single(R.string.action_turn_off, "Turn off"),
    TableString.Single(R.string.key_change_failed_title, "Encryption was not changed"),
    TableString.Single(R.string.key_change_error_damaged, "The database is damaged, so nothing was changed."),
    TableString.Single(R.string.biometric_unlock_title, "Unlock Doro Organizer"),
    TableString.Single(R.string.biometric_enable_title, "Turn on biometric lock"),
    TableString.Single(R.string.biometric_disable_title, "Turn off biometric lock"),
    TableString.Single(R.string.biometric_slot_title, "Set up fingerprint unlock"),
    TableString.Single(R.string.biometric_reset_title, "Confirm with your fingerprint"),
    TableString.Single(R.string.action_use_password, "Use password"),
    TableString.Single(R.string.slot_offer_title, "Set up fingerprint unlock again?"),
    TableString.Single(
        R.string.slot_offer_body_invalidated,
        "The fingerprints on this phone changed, so fingerprint unlock was switched off to keep others out. " +
            "Touch the sensor once to switch it on again.",
    ),
    TableString.Single(
        R.string.slot_offer_body_missing,
        "Fingerprint unlock is not set up for your current password. Touch the sensor once to set it up, so " +
            "the app opens with your fingerprint again.",
    ),
    TableString.Single(R.string.action_not_now, "Not now"),
    TableString.Single(R.string.action_slot_setup, "Set up"),
    TableString.Single(R.string.lock_off_password_title, "Turn off biometric lock?"),
    TableString.Single(
        R.string.lock_off_password_body,
        "Enter your startup password. Afterwards the app asks for it every time it starts, and no longer " +
            "when it comes back from the background.",
    ),
    TableString.Single(R.string.lock_on_password_title, "Turn on biometric lock?"),
    TableString.Single(
        R.string.lock_on_password_body,
        "Enter your startup password. Then touch the fingerprint sensor once to open the app with your " +
            "fingerprint.",
    ),
    TableString.Single(R.string.slot_setup_password_title, "Set up fingerprint unlock?"),
    TableString.Single(
        R.string.slot_setup_password_body,
        "Enter your startup password. Then touch the fingerprint sensor once.",
    ),
    TableString.Single(R.string.erase_title, "Erase all data?"),
    TableString.Single(
        R.string.erase_body,
        "All notes, people and projects, the encryption key and the security settings will be deleted from " +
            "this device. This cannot be undone.",
    ),
    TableString.Single(R.string.erase_prompt, "Type %1\$s to confirm"),
    TableString.Single(R.string.erase_word, "ERASE"),
    TableString.Single(R.string.action_erase, "Erase all data"),
    TableString.Single(R.string.start_fresh_title, "Start fresh?"),
    TableString.Single(
        R.string.start_fresh_body,
        "The current database and everything in it will be deleted from this device, and the app will start " +
            "empty and unencrypted. This cannot be undone. If you have a backup, choose Restore from backup " +
            "instead.",
    ),
    TableString.Single(R.string.action_start_fresh_confirm, "Delete and start fresh"),
    TableString.Single(R.string.start_fresh_failed_title, "Start fresh failed"),
    TableString.Single(R.string.erase_failed_title, "Erase failed"),
    TableString.Single(R.string.start_fresh_done_title, "Started fresh"),
    TableString.Single(
        R.string.start_fresh_done_body,
        "You are starting with an empty database. Database encryption is off; you can turn it on in Settings.",
    ),
    TableString.Single(R.string.kek_notice_title, "Your startup password needs renewing"),
    TableString.Single(
        R.string.kek_notice_body,
        "This phone's secure storage lost part of the key that your startup password unlocks. Fingerprint " +
            "unlock still works. Make a backup now. Then, the next time the password is asked while the app is " +
            "open, choose \"Forgot password?\" and set a new password with your fingerprint.",
    ),
    TableString.Single(R.string.error_ack_missing, "Confirm that you understand the password cannot be recovered."),
    TableString.Single(R.string.error_blank_name, "Enter a name."),
    TableString.Single(
        R.string.error_storage_full,
        "Not enough free storage, so nothing was changed. Free up some space and try again.",
    ),
    TableString.Single(
        R.string.error_file_access,
        "The file could not be read or written. Try again, or choose another location.",
    ),
    TableString.Single(
        R.string.error_key_unavailable,
        "The encryption key could not be accessed, so nothing was changed. Try again later.",
    ),
    TableString.Single(R.string.error_auth_failed, "Authentication failed. The setting was not changed."),
    TableString.Single(R.string.error_busy, "Another operation is still running. Try again when it has finished."),
    TableString.Single(R.string.error_database_unavailable, "Your data is not available right now. Try again."),
    TableString.Single(
        R.string.error_outcome_unknown,
        "The app could not confirm whether this change was applied, so it reloaded your data. Check that " +
            "your notes are as you expect. If the app asks for a password after a password change, try the new " +
            "password first, then the old one.",
    ),
    TableString.Single(R.string.error_unexpected, "Something went wrong. Your data was not changed."),
)

package com.doro.organizer.ui.common

import android.content.res.Resources
import androidx.annotation.StringRes
import com.doro.organizer.R
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.core.time.AppTime

/** The one text of each expected failure (ui.md § 11.13); exception messages never reach the UI (DEC-70). */
@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.WrongPassword -> R.string.password_wrong
    is AppError.Throttled -> R.string.password_throttled
    is AppError.InvalidInput -> when (rule) {
        InputRule.BlankName -> R.string.error_blank_name
        InputRule.PasswordMalformed -> R.string.password_malformed
        InputRule.PasswordTooShort -> R.string.password_too_short
        InputRule.PasswordMismatch -> R.string.password_mismatch
        InputRule.AcknowledgementMissing -> R.string.error_ack_missing
    }
    is AppError.InvalidBackup -> when (reason) {
        BackupRejection.NotABackup -> R.string.restore_error_not_backup
        BackupRejection.NewerVersion -> R.string.restore_error_newer
        BackupRejection.Incompatible -> R.string.restore_error_incompatible
        BackupRejection.Damaged -> R.string.restore_error_damaged
    }
    AppError.StorageFull -> R.string.error_storage_full
    AppError.FileAccess -> R.string.error_file_access
    AppError.KeyUnavailable -> R.string.error_key_unavailable
    AppError.AuthFailed -> R.string.error_auth_failed
    AppError.Busy -> R.string.error_busy
    AppError.DatabaseUnavailable -> R.string.error_database_unavailable
    AppError.OutcomeUnknown -> R.string.error_outcome_unknown
    AppError.Unexpected -> R.string.error_unexpected
}

/** The text of [error]; a backoff shows its remaining time as `m:ss`, read from [appTime] (ui.md § 11.13). */
fun Resources.errorText(error: AppError, appTime: AppTime): String =
    if (error is AppError.Throttled) {
        getString(R.string.password_throttled, countdownText(error.retryAtElapsedMs - appTime.elapsedMillis()))
    } else {
        getString(error.messageRes())
    }

/** Resolves [text] in the current locale when it is shown (ui.md § 2.4); every argument is itself a [UiText]. */
fun Resources.resolve(text: UiText, appTime: AppTime): String = when (text) {
    is UiText.Res -> getString(text.id, *text.args.map { resolve(it, appTime) }.toTypedArray())
    is UiText.Plural -> getQuantityString(text.id, text.count, text.count)
    is UiText.Error -> errorText(text.error, appTime)
}

package com.doro.organizer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.core.result.AppError

/**
 * The end of the backoff a password dialog counts down to (`AppTime.elapsedMillis()` base): the one already running
 * when the dialog opened ([retryAt], 0 for none) or the one the last attempt answered with `Throttled`, whichever ends
 * later.
 */
fun throttledUntil(retryAt: Long, error: AppError?): Long =
    maxOf(retryAt, (error as? AppError.Throttled)?.retryAtElapsedMs ?: 0L)

/**
 * The line under a current-password field (ui.md § 8.8): while a backoff runs ([throttled]) its countdown replaces
 * "Wrong password." and is plain text, not an error; otherwise a `StorageFull` [error], an attempt that could not be
 * counted and so was not checked (DEC-75), and a `WrongPassword` one show as errors, in that order, as on the password
 * screen.
 */
@Composable
fun currentPasswordMessage(throttled: Boolean, remainingMs: Long, error: AppError?): FieldMessage? = when {
    throttled -> FieldMessage(stringResource(R.string.password_throttled, countdownText(remainingMs)), isError = false)
    error == AppError.StorageFull -> FieldMessage(stringResource(R.string.gate_password_storage_full), isError = true)
    error == AppError.WrongPassword -> FieldMessage(stringResource(R.string.password_wrong), isError = true)
    else -> null
}

package com.doro.organizer.ui.gate

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.LockError

private val LOCK_LOGO_SIZE = 96.dp

/**
 * The lock screen (ui.md § 10.4), `LockedScreen` because `LockScreen` is the enum of `security`. The automatic prompt
 * is started by `AppLockController` at the activity's ON_RESUME, never from composition (DEC-65); "Unlock" is the
 * manual retry, and "Use password" shows only while the route is the biometric slot.
 */
@Composable
internal fun LockedScreen(authError: LockError?, lock: AppLockController) {
    val route by lock.lockRoute.collectAsState()
    GateColumn(stringResource(R.string.app_name), header = { AppLogo(LOCK_LOGO_SIZE) }) {
        GateBody(stringResource(R.string.gate_locked_body))
        Button(onClick = lock::prompt, modifier = Modifier.fillMaxWidth().testTag("gate_unlock")) {
            Text(stringResource(R.string.action_unlock))
        }
        if (route == UnlockRoute.Slot) {
            TextButton(onClick = lock::usePassword, modifier = Modifier.testTag("gate_use_password")) {
                Text(stringResource(R.string.action_use_password))
            }
        }
        // The fixed texts of the app's own errors (a fingerprint the slot could not use, a key-state that could not be
        // read), else the system's already localized errString; a silent error has no message (security.md § 7.4).
        val fixed = authError?.let { fixedLockErrorOf(it.code) }
        val errorText = fixed?.let { stringResource(it.text) } ?: authError?.message
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .then(fixed?.let { Modifier.testTag(it.tag) } ?: Modifier),
            )
        }
    }
}

/** A lock error of the app itself, not of the system prompt: its fixed text and its test tag. */
private class FixedLockError(@param:StringRes val text: Int, val tag: String)

private fun fixedLockErrorOf(code: Int): FixedLockError? = when (code) {
    LockError.SLOT_FAILED -> FixedLockError(R.string.gate_slot_failed, "gate_slot_failed_text")
    LockError.KEY_STATE_UNREADABLE -> FixedLockError(R.string.gate_key_state_unreadable, "gate_key_state_unreadable_text")
    else -> null
}

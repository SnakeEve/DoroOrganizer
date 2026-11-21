package com.doro.organizer.ui.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.ui.common.Announcer
import com.doro.organizer.ui.common.FieldMessage
import com.doro.organizer.ui.common.PasswordField
import com.doro.organizer.ui.common.countdownText
import com.doro.organizer.ui.common.rememberRemainingMillis
import com.doro.organizer.ui.common.takePassword

/**
 * The password screen (ui.md § 10.2, DEC-62): the same in `NeedsPassword`, over a `Locked` password entry and, busy,
 * in `Opening(viaPassword)`; only the transition behind "Unlock" differs. The field lives in
 * `remember { TextFieldState() }`, so rotation and process death empty it (security.md § 6.2); everything else comes
 * from [model].
 */
@Composable
internal fun PasswordScreen(
    model: PasswordScreenModel,
    appTime: AppTime,
    announcer: Announcer,
    onSubmit: (CharArray) -> Unit,
    onUseFingerprint: () -> Unit,
    onForgot: () -> Unit,
) {
    val field = remember { TextFieldState() }
    val remaining = rememberRemainingMillis(model.retryAt, appTime)
    val throttled = remaining > 0
    val inputEnabled = !model.busy && !throttled && model.maintenance == null
    val submit = { if (inputEnabled && field.text.isNotEmpty()) onSubmit(field.takePassword()) }
    val throttledText = stringResource(R.string.password_throttled, countdownText(remaining))
    val focus = remember { FocusRequester() }

    LaunchedEffect(model.busy) { if (model.busy) field.clearText() }
    LaunchedEffect(inputEnabled) { if (inputEnabled) focus.requestFocus() }
    PasswordAnnouncements(model, throttled, throttledText, announcer)

    GateColumn(stringResource(R.string.gate_password_title)) {
        GateBody(stringResource(R.string.gate_password_body))
        ReasonLine(model)
        if (model.couldNotOpen) {
            Text(
                stringResource(R.string.gate_could_not_open),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        PasswordField(
            state = field,
            label = stringResource(R.string.password_label),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("gate_password_field"),
            enabled = inputEnabled,
            message = when {
                throttled -> FieldMessage(throttledText, isError = false) // not a live region (ui.md § 12.3)
                // Not a wrong password: it was not checked, because its attempt could not be counted (DEC-75).
                model.storageFull && !model.busy && model.maintenance == null ->
                    FieldMessage(stringResource(R.string.gate_password_storage_full), isError = true)
                model.failures > 0 && !model.busy && model.maintenance == null ->
                    FieldMessage(stringResource(R.string.password_wrong), isError = true)
                else -> null
            },
            onSubmit = submit,
        )
        if (model.checkFailed) {
            Text(
                stringResource(R.string.gate_password_check_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("gate_check_failed_text"),
                textAlign = TextAlign.Center,
            )
        }
        if (model.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.gate_unlocking))
            }
        } else {
            Button(
                onClick = submit,
                enabled = inputEnabled && field.text.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("gate_unlock"),
            ) { Text(stringResource(R.string.action_unlock)) }
        }
        // The backoff limits password guesses only; the fingerprint has the system's own lockout (ui.md § 10.2).
        if (model.fingerprintUsable) {
            TextButton(onClick = onUseFingerprint, enabled = !model.busy, modifier = Modifier.testTag("gate_use_fingerprint")) {
                Text(stringResource(R.string.action_use_fingerprint))
            }
        }
        // Hidden while an operation runs behind the lock: no erase or reset can start then (DEC-63).
        if (model.maintenance == null) {
            TextButton(onClick = onForgot, enabled = !model.busy, modifier = Modifier.testTag("gate_forgot")) {
                Text(stringResource(R.string.gate_forgot))
            }
        }
    }
}

/**
 * The line below the body: the running operation's text, the key-store line (DEC-86, error-styled with a decorative
 * icon) or the reason line; nothing for `PasswordReason.None`.
 */
@Composable
private fun ReasonLine(model: PasswordScreenModel) {
    val style = MaterialTheme.typography.bodyMedium
    when {
        model.maintenance != null -> Text(
            stringResource(model.maintenance.textRes()),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("gate_password_reason"),
        )
        model.reason == PasswordReason.KeyStoreFailed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.gate_password_kek_failed),
                style = style,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("gate_kek_failed_text"),
            )
        }
        else -> reasonRes(model.reason)?.let { res ->
            Text(
                stringResource(res),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("gate_password_reason"),
            )
        }
    }
}

/** The text of a reason line; the key-store reason has its own line and `None` has none. */
private fun reasonRes(reason: PasswordReason): Int? = when (reason) {
    PasswordReason.None, PasswordReason.KeyStoreFailed -> null
    PasswordReason.Reminder -> R.string.gate_password_reminder
    PasswordReason.FingerprintChanged -> R.string.gate_password_fingerprint_changed
}

/**
 * The screen's one-time announcements (ui.md § 12.3), each guarded by a saveable flag so that a rotation repeats none:
 * the reason line when the screen appears with one, the start of a backoff, and "Use fingerprint" appearing on the
 * key-store screen after the re-check (DEC-86).
 */
@Composable
private fun PasswordAnnouncements(
    model: PasswordScreenModel,
    throttled: Boolean,
    throttledText: String,
    announcer: Announcer,
) {
    val reasonText = when {
        model.maintenance != null -> null
        model.reason == PasswordReason.KeyStoreFailed -> stringResource(R.string.gate_password_kek_failed)
        else -> reasonRes(model.reason)?.let { stringResource(it) }
    }
    var reasonAnnounced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!reasonAnnounced && reasonText != null) announcer.announce(reasonText)
        reasonAnnounced = true
    }
    var announcedRetryAt by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(model.retryAt, throttled) {
        if (throttled && announcedRetryAt != model.retryAt) {
            announcer.announce(throttledText)
            announcedRetryAt = model.retryAt
        }
    }
    val fingerprintAvailable = stringResource(R.string.gate_fingerprint_available)
    var usableSeen by rememberSaveable { mutableStateOf(model.fingerprintUsable) }
    LaunchedEffect(model.fingerprintUsable) {
        if (model.fingerprintUsable && !usableSeen && model.reason == PasswordReason.KeyStoreFailed) {
            announcer.announce(fingerprintAvailable)
        }
        usableSeen = model.fingerprintUsable
    }
}

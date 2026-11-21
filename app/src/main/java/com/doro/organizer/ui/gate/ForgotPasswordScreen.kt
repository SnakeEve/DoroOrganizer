package com.doro.organizer.ui.gate

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.ui.GateGraph
import com.doro.organizer.ui.common.MessageDialog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The Forgot password screen (ui.md § 10.3, DEC-63) over a password screen whose Erase origin is [eraseOrigin]. The
 * fingerprint reset comes first while `fingerprintUnlockAvailable()` says `Available` and [resetOrigin] exists (never
 * over `Locked(Corrupt)`); `Unknown` there says to try again later (DEC-77); the erase path is always offered once the
 * answer is in. Back returns to the password screen.
 * [onClose] also runs when a fingerprint reset starts and when an Erase from a `Locked` origin is confirmed: neither
 * returns here (ui.md § 10.3), and the gate states that say so can pass between two frames.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForgotPasswordScreen(
    eraseOrigin: MaintenanceOrigin,
    resetOrigin: MaintenanceOrigin?,
    vm: ForgotPasswordViewModel,
    graph: GateGraph,
    onClose: () -> Unit,
) {
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var eraseOpen by rememberSaveable { mutableStateOf(false) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    var resetSubmitted by rememberSaveable { mutableStateOf(false) }
    // Once per opening, not per composition: after a rotation the activity-scoped ViewModel keeps its answer, and
    // after Android closed the app the new one asks again (DEC-59).
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            vm.onScreenOpened()
        } else {
            vm.onScreenRestored()
        }
    }
    // The reset dialog closes once the prompt and, if it authorized, the reset call have returned (ui.md § 8.15).
    // Keyed on both values: busy may go true and false again within one frame, so no edge of it is observable.
    LaunchedEffect(resetSubmitted, ui.busy) {
        if (resetSubmitted && !ui.busy) {
            resetSubmitted = false
            resetOpen = false
        }
    }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(vm) { vm.resetStarted.collect { close() } }
    BackHandler(enabled = true, onBack = onClose)

    val title = stringResource(R.string.forgot_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val available = ui.fingerprintAvailable ?: return@Scaffold // empty until the controller answered
        Box(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = GATE_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (resetOrigin == null) {
                    EraseOnly(R.string.forgot_body, busy = ui.busy, onErase = { eraseOpen = true })
                } else {
                    when (available) {
                        FingerprintAvailability.Available ->
                            ResetFirst(busy = ui.busy, onReset = { resetOpen = true }, onErase = { eraseOpen = true })
                        // The slot's key could not be looked up: the reset may work later, so the password is not
                        // called unrecoverable (DEC-77).
                        FingerprintAvailability.Unknown ->
                            EraseOnly(R.string.forgot_body_fingerprint_unknown, busy = ui.busy, onErase = { eraseOpen = true })
                        FingerprintAvailability.Unavailable ->
                            EraseOnly(R.string.forgot_body, busy = ui.busy, onErase = { eraseOpen = true })
                    }
                }
            }
        }
    }

    if (eraseOpen) {
        EraseDialog(
            onDismiss = { eraseOpen = false },
            onConfirm = {
                eraseOpen = false
                // Started at once and detached in DbMaintenance, so leaving this screen cancels only the wait for the
                // result; failures come through its status.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.dbMaintenance.erase(eraseOrigin) }
                // A failed Erase returns here only from NeedsPassword; a Locked origin clears the flag (ui.md § 10.3).
                if (eraseOrigin != MaintenanceOrigin.NeedsPassword) onClose()
            },
        )
    }
    if (resetOpen && resetOrigin != null) {
        PasswordResetDialog(
            busy = ui.busy,
            onDismiss = { resetOpen = false },
            onConfirm = { password ->
                resetSubmitted = true
                vm.resetPassword(resetOrigin, password)
            },
        )
    }
    ui.error?.let { error ->
        MessageDialog(
            stringResource(R.string.key_change_failed_title),
            listOf(UiText.Error(error)),
            graph.appTime,
            onClose = vm::dismissError,
        )
    }
}

@Composable
private fun ResetFirst(busy: Boolean, onReset: () -> Unit, onErase: () -> Unit) {
    Text(stringResource(R.string.forgot_body_fingerprint), style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = onReset,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("forgot_reset_fingerprint"),
    ) { Text(stringResource(R.string.action_reset_with_fingerprint)) }
    Text(
        stringResource(R.string.forgot_erase_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.forgot_erase_body), style = MaterialTheme.typography.bodyLarge)
    OutlinedButton(
        onClick = onErase,
        enabled = !busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().testTag("forgot_erase"),
    ) { Text(stringResource(R.string.action_erase_start_over)) }
}

@Composable
private fun EraseOnly(@StringRes body: Int, busy: Boolean, onErase: () -> Unit) {
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = onErase,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth().testTag("forgot_erase"),
    ) { Text(stringResource(R.string.action_erase_start_over)) }
}

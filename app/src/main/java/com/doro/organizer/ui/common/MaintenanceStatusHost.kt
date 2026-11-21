package com.doro.organizer.ui.common

import android.content.ClipData
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doro.organizer.R
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.session.GateState
import com.doro.organizer.ui.GateGraph

/**
 * The only renderer of `DbMaintenance.status` (ui.md § 8.12): the restore's questions, every outcome and the share
 * sheet. Composed in the shell with its [snackbar], and without one on the Password, Forgot password, Corrupt and
 * KeyUnavailable screens, where outcomes that need the shell wait for it. Over a `Locked` gate it runs in lock mode
 * (DEC-66). `Finished` is acknowledged only after the user has seen it, so it survives rotation, a lock and a
 * navigation reset.
 */
@Composable
fun MaintenanceStatusHost(graph: GateGraph, snackbar: SnackbarHostState? = null) {
    val maintenance = graph.dbMaintenance
    val status by maintenance.status.collectAsState()
    val gate by graph.sessionGate.state.collectAsState()
    val place = when {
        gate is GateState.Locked -> HostPlace.Locked
        snackbar != null -> HostPlace.Shell
        else -> HostPlace.Screen
    }
    val view = hostViewOf(status, place, gate is GateState.Maintenance, gate == GateState.Corrupt)
    val shown = status
    if (snackbar != null) ShareFailures(graph)
    // The fingerprint-reset marker belongs to one key change: a failure must not leak it into a later one (§ 9.6).
    LaunchedEffect(shown) {
        if (shown is MaintenanceStatus.Finished && shown.op == MaintenanceOp.KeyChange && shown.result is AppResult.Err) {
            graph.appMessages.clearFingerprintReset()
        }
    }
    when (view) {
        HostView.None -> Unit
        HostView.Acknowledge -> LaunchedEffect(shown) { maintenance.acknowledgeIfStill(shown) }
        is HostView.Checking -> RestoreCheckingDialog(view.fraction, onCancel = maintenance::cancelRestore)
        is HostView.BackupPassword -> RestoreBackupPasswordDialog(view.retry, onAnswer = maintenance::answerPassword)
        is HostView.DatabasePassword ->
            RestoreDatabasePasswordDialog(view.error, graph.appTime, onAnswer = maintenance::answerPassword)
        is HostView.Fingerprint -> {
            val prompter: RestoreKeyViewModel = viewModel(factory = restoreKeyFactory(graph))
            LaunchedEffect(shown) { prompter.answer(shown as MaintenanceStatus.NeedsBiometricUnlock) }
        }
        is HostView.Confirm -> RestoreConfirmDialog(view.preview, onAnswer = maintenance::answerConfirmation)
        is HostView.Snackbar -> {
            val text = LocalResources.current.resolve(view.text, graph.appTime)
            LaunchedEffect(shown) {
                checkNotNull(snackbar).showSnackbar(text, duration = SnackbarDuration.Short)
                maintenance.acknowledgeIfStill(shown)
            }
        }
        is HostView.KeyChanged -> {
            val resources = LocalResources.current
            LaunchedEffect(shown) {
                val reset = graph.appMessages.consumeFingerprintReset()
                var seen = false
                try {
                    val text = resources.getString(keyChangedText(view.from, view.to, reset))
                    checkNotNull(snackbar).showSnackbar(text, duration = SnackbarDuration.Short)
                    seen = true
                } finally {
                    // A rotation or a lock cancels the snackbar while the status stays: the next host shows the
                    // same text, so the marker goes back until the user has seen it (ui.md § 9.6, DEC-63).
                    if (reset && !seen) graph.appMessages.markFingerprintReset()
                }
                maintenance.acknowledgeIfStill(shown)
            }
        }
        is HostView.Share -> {
            val title = stringResource(R.string.share_chooser_title)
            LaunchedEffect(shown) {
                graph.externalActivities.request(ExternalRequest(ExternalKind.Share, shareChooser(view, title)))
                maintenance.acknowledgeIfStill(shown)
            }
        }
        is HostView.Notice -> MessageDialog(
            stringResource(view.title),
            listOf(UiText.Res(view.body)),
            graph.appTime,
            onClose = { maintenance.acknowledgeIfStill(shown) },
        )
        is HostView.Failure -> MessageDialog(
            stringResource(view.title),
            view.paragraphs,
            graph.appTime,
            onClose = { maintenance.acknowledgeIfStill(shown) },
        )
    }
}

/** A share sheet that could not open reaches the user as a file error (architecture.md § Result launchers). */
@Composable
private fun ShareFailures(graph: GateGraph) {
    LaunchedEffect(graph) {
        graph.externalActivities.failures.collect { kind ->
            if (kind == ExternalKind.Share) graph.appMessages.post(UserMessage.error(AppError.FileAccess))
        }
    }
}

/** The system share sheet for a share copy (db-lifecycle.md § To a share copy): a read grant only, never write. */
private fun shareChooser(share: HostView.Share, title: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, share.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(share.displayName, share.uri)
    return Intent.createChooser(send, title)
}

/** `acknowledge()` only while [shown] is still the status: a newer operation's status is never dropped. */
private fun DbMaintenance.acknowledgeIfStill(shown: MaintenanceStatus) {
    if (status.value === shown) acknowledge()
}

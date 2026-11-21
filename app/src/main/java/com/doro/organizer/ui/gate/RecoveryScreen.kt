package com.doro.organizer.ui.gate

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.ui.GateGraph
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.MaintenanceStatusHost
import com.doro.organizer.ui.common.MessageDialog
import com.doro.organizer.ui.common.SecureDialogWindow
import com.doro.organizer.ui.common.restoreErrorText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The two recovery screens (ui.md § 10.7): the database is damaged (DEC-85), or its key cannot be read (DEC-86). */
internal enum class Recovery(
    val origin: MaintenanceOrigin,
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
) {
    Corrupt(MaintenanceOrigin.Corrupt, R.drawable.ic_error, R.string.gate_corrupt_title, R.string.gate_corrupt_body),
    KeyUnavailable(
        MaintenanceOrigin.KeyUnavailable, R.drawable.ic_key_off, R.string.gate_key_title, R.string.gate_key_body,
    ),
}

/**
 * The Corrupt and KeyUnavailable screens (ui.md § 10.7). The file is kept until the user confirms a restore or Start
 * fresh (DEC-85, DEC-86), both with the screen's own origin. The screen consumes the `Restore` result of
 * `ExternalActivities`, and its maintenance status host asks the restore's questions and shows the outcomes.
 */
@Composable
internal fun RecoveryScreen(recovery: Recovery, graph: GateGraph) {
    val scope = rememberCoroutineScope()
    var startFreshOpen by rememberSaveable { mutableStateOf(false) }
    var pickerFailed by rememberSaveable { mutableStateOf(false) }
    val external = graph.externalActivities
    val inbox by external.inbox.collectAsState()
    // This screen is composed only while the gate is not locked, so a result that arrived meanwhile waits for it.
    LaunchedEffect(inbox) {
        external.take(ExternalKind.Restore)?.uri?.let { source ->
            // Detached in DbMaintenance: leaving this screen cancels only the wait for the result.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.dbMaintenance.restore(source, recovery.origin) }
        }
    }
    LaunchedEffect(external) { external.failures.collect { if (it == ExternalKind.Restore) pickerFailed = true } }
    GateColumn(stringResource(recovery.title), header = { ErrorIcon(recovery.icon) }) {
        GateBody(stringResource(recovery.body))
        Button(
            onClick = { external.request(ExternalRequest(ExternalKind.Restore)) },
            modifier = Modifier.fillMaxWidth().testTag("recovery_restore"),
        ) { Text(stringResource(R.string.restore_from_backup)) }
        OutlinedButton(
            onClick = { startFreshOpen = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_start_fresh)) }
    }
    if (startFreshOpen) {
        StartFreshDialog(
            onDismiss = { startFreshOpen = false },
            onConfirm = {
                startFreshOpen = false
                // Started at once and detached in DbMaintenance: leaving this screen cancels only the wait.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.dbMaintenance.startFresh(recovery.origin) }
            },
        )
    }
    if (pickerFailed) {
        MessageDialog(
            stringResource(R.string.restore_error_title),
            listOf(restoreErrorText(AppError.FileAccess), UiText.Res(R.string.restore_error_unchanged)),
            graph.appTime,
            onClose = { pickerFailed = false },
        )
    }
    MaintenanceStatusHost(graph)
}

/** The confirmation of Start fresh (ui.md § 8.9); its outcome reaches the user through `DbMaintenance.status`. */
@Composable
internal fun StartFreshDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_fresh_title)) },
        text = {
            SecureDialogWindow(hideOverlays = true) { DialogContent { Text(stringResource(R.string.start_fresh_body)) } }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_start_fresh_confirm)) }
        },
    )
}

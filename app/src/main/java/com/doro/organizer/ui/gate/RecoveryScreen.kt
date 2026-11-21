package com.doro.organizer.ui.gate

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.ui.GateGraph
import com.doro.organizer.ui.common.DialogContent
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
 * The Corrupt and KeyUnavailable screens (ui.md § 10.7). The file is kept until the user confirms Start fresh
 * (DEC-85, DEC-86), which runs with the screen's own origin. "Restore from backup" comes with the maintenance status
 * host that answers the restore's questions (SPEC T19): without it a started restore would wait for an answer forever.
 */
@Composable
internal fun RecoveryScreen(recovery: Recovery, graph: GateGraph) {
    val scope = rememberCoroutineScope()
    var startFreshOpen by rememberSaveable { mutableStateOf(false) }
    GateColumn(stringResource(recovery.title), header = { ErrorIcon(recovery.icon) }) {
        GateBody(stringResource(recovery.body))
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
}

/** The confirmation of Start fresh (ui.md § 8.9); its outcome reaches the user through `DbMaintenance.status`. */
@Composable
private fun StartFreshDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_fresh_title)) },
        text = { DialogContent { Text(stringResource(R.string.start_fresh_body)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_start_fresh_confirm)) }
        },
    )
}

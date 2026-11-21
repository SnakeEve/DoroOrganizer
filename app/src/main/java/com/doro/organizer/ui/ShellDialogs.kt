package com.doro.organizer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doro.organizer.R
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.session.GateState
import com.doro.organizer.ui.common.DialogContent
import com.doro.organizer.ui.common.HostPlace
import com.doro.organizer.ui.common.HostView
import com.doro.organizer.ui.common.SecureDialogWindow
import com.doro.organizer.ui.common.hostViewOf
import com.doro.organizer.ui.navigation.Navigator
import com.doro.organizer.ui.navigation.SettingsKey
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * The shell's own dialogs (ui.md § 2.4), at most one at a time and only while the maintenance status host shows
 * nothing and no operation runs, so a dialog of the host comes first and a key change shows its snackbar before the
 * offer. Priority: the biometric-lock auto-off notice, the kekBroken notice, then the fingerprint set-up offer. The
 * shell is not composed while the gate is locked, so none of them appears over a lock (DEC-66).
 */
@Composable
internal fun ShellDialogs(graph: UiGraph, navigator: Navigator) {
    val status by graph.dbMaintenance.status.collectAsState()
    val gate by graph.sessionGate.state.collectAsState()
    val settings by graph.settingsStore.settings.collectAsState()
    val kekNotice by graph.appLockController.kekNotice.collectAsState()
    val offer by graph.appLockController.slotOffer.collectAsState()
    // Not `status != Idle`: a status this host renders nothing for (a key change's inline WrongPassword) would hold
    // every notice back until some later operation replaced it.
    val hostBusy = status is MaintenanceStatus.Running ||
        hostViewOf(status, HostPlace.Shell, gate is GateState.Maintenance, gateCorrupt = false) != HostView.None
    when {
        hostBusy -> Unit
        settings?.lockAutoOffNotice == true -> AutoOffNotice(onOk = { clearAutoOffNotice(graph) })
        kekNotice -> KekNotice(
            onBackUpNow = {
                // Settings runs its own "Backup all data", with the plaintext warning when the backup is unencrypted:
                // the notice outlives a Start fresh into PLAIN (DEC-83). Then the notice goes (security.md § 7.3).
                navigator.open(SettingsKey(startBackup = true))
                graph.appLockController.dismissKekNotice()
            },
            onOk = graph.appLockController::dismissKekNotice,
        )
        offer != null -> SlotOfferDialog(checkNotNull(offer), graph)
    }
}

private fun clearAutoOffNotice(graph: UiGraph) {
    graph.applicationScope.launch {
        try {
            graph.settingsStore.clearLockAutoOffNotice()
        } catch (e: IOException) {
            // The flag stays set, so the notice shows again later; nothing else depends on it.
            AppLog.d("shell: auto-off notice not cleared: ${e.javaClass.simpleName}")
        }
    }
}

/** The biometric lock turned itself off because the device lost its screen lock (security.md § 7.6). */
@Composable
private fun AutoOffNotice(onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text(stringResource(R.string.lock_auto_off_title)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.lock_auto_off_body)) } } },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** The startup password's Keystore part is gone while the fingerprint still works (DEC-86). */
@Composable
private fun KekNotice(onBackUpNow: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        modifier = Modifier.testTag("kek_notice"),
        title = { Text(stringResource(R.string.kek_notice_title)) },
        text = { SecureDialogWindow { DialogContent { Text(stringResource(R.string.kek_notice_body)) } } },
        dismissButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.action_ok)) } },
        confirmButton = { TextButton(onClick = onBackUpNow) { Text(stringResource(R.string.action_backup_now)) } },
    )
}

/**
 * The fingerprint set-up offer (ui.md § 8.13): "Set up" runs `createSlot()`, whose prompt keeps the dialog open,
 * disabled and not dismissable; "Not now", Back and a tap outside decline it until the next password unlock.
 */
@Composable
private fun SlotOfferDialog(offer: SlotOffer, graph: UiGraph) {
    val vm: SlotOfferViewModel = viewModel(factory = slotOfferFactory(graph))
    val busy by vm.busy.collectAsStateWithLifecycle()
    val decline = { if (!busy) graph.appLockController.dismissSlotOffer() }
    AlertDialog(
        onDismissRequest = decline,
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        modifier = Modifier.testTag("slot_offer"),
        title = { Text(stringResource(R.string.slot_offer_title)) },
        text = {
            SecureDialogWindow {
                val body = when (offer) {
                    SlotOffer.Invalidated -> R.string.slot_offer_body_invalidated
                    SlotOffer.Missing -> R.string.slot_offer_body_missing
                }
                DialogContent { Text(stringResource(body)) }
            }
        },
        dismissButton = {
            TextButton(onClick = decline, enabled = !busy, modifier = Modifier.testTag("slot_offer_dismiss")) {
                Text(stringResource(R.string.action_not_now))
            }
        },
        confirmButton = {
            TextButton(onClick = vm::setUp, enabled = !busy, modifier = Modifier.testTag("slot_offer_setup")) {
                Text(stringResource(R.string.action_slot_setup))
            }
        },
    )
}

package com.doro.organizer.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doro.organizer.R
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.security.SlotState
import com.doro.organizer.session.GateState
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.common.AppSnackbarHost
import com.doro.organizer.ui.common.HostPlace
import com.doro.organizer.ui.common.MessageEffect
import com.doro.organizer.ui.common.hostViewOf
import com.doro.organizer.ui.navigation.LocalIsTopEntry
import com.doro.organizer.ui.navigation.Navigator

/** The widest the Settings column grows (ui.md § 9.1). */
private val SETTINGS_MAX_WIDTH = 720.dp

/**
 * The Settings screen (ui.md § 9). It consumes the `Backup` and `Restore` results of `ExternalActivities` while it is
 * the top entry, which also means the gate is not locked (architecture.md § Result launchers). Its own dialogs give
 * way to a dialog of the maintenance status host and to the maintenance overlay. [startBackup]: opened by "Back up
 * now" of the kekBroken notice, so "Backup all data" runs once (ui.md § 2.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navigator: Navigator, startBackup: Boolean) {
    val graph = LocalUiGraph.current
    val vm: SettingsViewModel = viewModel(factory = settingsFactory(graph, startBackup))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val isTop = LocalIsTopEntry.current
    LifecycleResumeEffect(vm) {
        vm.refreshAvailability()
        onPauseOrDispose { }
    }
    DocumentResults(graph, vm, isTop)
    MessageEffect(state.message, graph, vm::onMessageShown)

    val title = stringResource(R.string.settings_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = SETTINGS_MAX_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("settings_list"),
            ) {
                BackupSection(vm)
                SecuritySection(state, vm)
                AboutSection(vm)
            }
        }
    }
    SettingsDialogs(graph, state, vm)
}

/**
 * Takes the picker results this screen asked for, and maps a picker that could not open to a file error. A requested
 * backup starts after that collector, so a picker that cannot open is never reported before anyone listens.
 */
@Composable
private fun DocumentResults(graph: UiGraph, vm: SettingsViewModel, isTop: Boolean) {
    val external = graph.externalActivities
    val inbox by external.inbox.collectAsState()
    LaunchedEffect(inbox, isTop) {
        if (!isTop) return@LaunchedEffect
        external.take(ExternalKind.Backup)?.let { vm.onBackupDocument(it.uri) }
        external.take(ExternalKind.Restore)?.let { vm.onRestoreDocument(it.uri) }
    }
    LaunchedEffect(external) {
        external.failures.collect { kind ->
            if (kind == ExternalKind.Backup || kind == ExternalKind.Restore) vm.onLaunchFailed(kind)
        }
    }
    LaunchedEffect(vm) { vm.startRequestedBackup() }
}

@Composable
private fun BackupSection(vm: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_backup))
    // The labels of "Backup all data" and "Restore from backup" are the §4 texts, so TalkBack reads them (DEC-55).
    ActionRow(
        R.drawable.ic_save,
        stringResource(R.string.backup_all_data),
        stringResource(R.string.backup_all_data_summary),
        "settings_backup",
        vm::onBackupAll,
    )
    ActionRow(
        R.drawable.ic_share,
        stringResource(R.string.share_backup),
        stringResource(R.string.share_backup_summary),
        "settings_share",
        vm::onShareBackup,
    )
    ActionRow(
        R.drawable.ic_restore,
        stringResource(R.string.restore_from_backup),
        stringResource(R.string.restore_summary),
        "settings_restore",
        vm::onRestore,
    )
}

@Composable
private fun SecuritySection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_security))
    val settings = state.settings ?: return
    val mode = state.keyMode ?: return // never null in Ready (ui.md § 9.3)
    val availability = state.availability
    val encrypted = mode != KeyMode.PLAIN
    // Below API 33 nothing hides only the Recents thumbnail (security.md § 12).
    val oldRecents = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !settings.screenshotProtection

    if (availability?.deviceSecure == false) {
        HintCard(R.drawable.ic_warning, stringResource(R.string.security_no_screen_lock))
    }
    SwitchRow(
        R.drawable.ic_lock,
        stringResource(R.string.encryption_title),
        listOfNotNull(
            stringResource(if (encrypted) R.string.encryption_summary_on else R.string.encryption_summary_off),
            stringResource(R.string.encryption_recents_hint).takeIf { oldRecents && encrypted && !settings.biometricLock },
        ),
        checked = encrypted,
        enabled = true,
        tag = "settings_encryption",
        onToggle = vm::onEncryptionSwitch,
    )
    if (mode == KeyMode.PASSWORD) {
        ActionRow(
            R.drawable.ic_password,
            stringResource(R.string.startup_password_change),
            stringResource(
                if (settings.biometricLock) R.string.startup_password_on_fingerprint else R.string.startup_password_on,
            ),
            "settings_password_change",
            vm::onChangeStartupPassword,
        )
        ActionRow(
            R.drawable.ic_password,
            stringResource(R.string.startup_password_remove),
            null,
            "settings_password_remove",
            vm::onRemoveStartupPassword,
        )
    } else {
        ActionRow(
            R.drawable.ic_password,
            stringResource(R.string.startup_password_title),
            stringResource(R.string.startup_password_off),
            "settings_password_set",
            vm::onSetStartupPassword,
        )
    }
    if (availability != null) BiometricRow(state, mode, oldRecents, vm)
    SwitchRow(
        R.drawable.ic_screenshot_monitor,
        stringResource(R.string.screenshot_title),
        listOf(stringResource(R.string.screenshot_summary)),
        checked = settings.screenshotProtection,
        enabled = true,
        tag = "settings_screenshot",
        onToggle = { vm.setScreenshotProtection(!settings.screenshotProtection) },
    )
    // Forced on while the database is encrypted; the stored value applies again once it is not (security.md § 2).
    SwitchRow(
        R.drawable.ic_enhanced_encryption,
        stringResource(R.string.encrypt_backups_title),
        listOf(stringResource(if (encrypted) R.string.encrypt_backups_forced else R.string.encrypt_backups_summary)),
        checked = encrypted || settings.encryptBackups,
        enabled = !encrypted,
        tag = "settings_encrypt_backups",
        onToggle = { vm.setEncryptBackups(!settings.encryptBackups) },
    )
}

/** The biometric lock row (ui.md § 9.4): shown, enabled and explained by the availability and the lock setting. */
@Composable
private fun BiometricRow(state: SettingsUiState, mode: KeyMode, oldRecents: Boolean, vm: SettingsViewModel) {
    val availability = checkNotNull(state.availability)
    val lockOn = checkNotNull(state.settings).biometricLock
    val usable = availability.biometric == BiometricAvailability.Success ||
        availability.biometric == BiometricAvailability.StatusUnknown
    val noneEnrolled = availability.biometric == BiometricAvailability.NoneEnrolled
    if (!lockOn && !usable && !noneEnrolled) return
    val slotMissing = mode == KeyMode.PASSWORD && lockOn && usable &&
        (availability.slot == SlotState.Missing || availability.slot == SlotState.Invalidated)
    val enrollHint = !lockOn && noneEnrolled
    SwitchRow(
        R.drawable.ic_fingerprint,
        stringResource(R.string.biometric_title),
        listOfNotNull(
            stringResource(if (mode == KeyMode.PASSWORD) R.string.biometric_summary_password else R.string.biometric_summary),
            stringResource(R.string.biometric_none_enrolled).takeIf { enrollHint },
            stringResource(R.string.biometric_slot_missing).takeIf { slotMissing },
            stringResource(R.string.biometric_recents_hint).takeIf { oldRecents && lockOn },
        ),
        checked = lockOn,
        enabled = !enrollHint && !state.busy,
        tag = "settings_biometric",
        onToggle = vm::onBiometricSwitch,
    ) {
        if (enrollHint) {
            TextButton(onClick = vm::onEnroll, modifier = Modifier.testTag("settings_enroll")) {
                Text(stringResource(R.string.action_enroll))
            }
        }
        if (slotMissing) {
            TextButton(onClick = vm::onSetUpSlot, modifier = Modifier.testTag("settings_slot_setup")) {
                Text(stringResource(R.string.action_slot_setup))
            }
        }
    }
}

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_about))
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    InfoRow(R.drawable.ic_info, stringResource(R.string.about_version_title), version)
    ActionRow(
        R.drawable.ic_description,
        stringResource(R.string.open_source_notices),
        stringResource(R.string.open_source_notices_summary),
        "settings_notices",
        vm::openNotices,
    )
}

/**
 * The open dialog of [state]. None is shown under the maintenance overlay, whose dialogs would cover it, nor while the
 * status host shows a dialog: a failure or a restore question comes first (ui.md § 8.8, DEC-73). A key change that
 * reached the overlay stays hidden until `changeKey` returns: the gate is `Ready` again a moment before that, and the
 * dialog would come back with the texts of the new key mode.
 */
@Composable
private fun SettingsDialogs(graph: UiGraph, state: SettingsUiState, vm: SettingsViewModel) {
    val status by graph.dbMaintenance.status.collectAsState()
    val gate by graph.sessionGate.state.collectAsState()
    val inMaintenance = gate is GateState.Maintenance
    val keyChangeRuns = state.busy && state.dialog is SettingsDialog.KeyChange
    var keyChangeStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(keyChangeRuns, inMaintenance) { keyChangeStarted = keyChangeRuns && (keyChangeStarted || inMaintenance) }
    if (inMaintenance || (keyChangeRuns && keyChangeStarted)) return
    if (hostViewOf(status, HostPlace.Shell, gateInMaintenance = false, gateCorrupt = false).isDialog) return
    val retryAt = state.retryAtElapsedMs
    when (val dialog = state.dialog ?: return) {
        is SettingsDialog.PlainBackupWarning -> PlainBackupWarningDialog(
            onConfirm = { vm.confirmPlainBackup(dialog.share) },
            onDismiss = vm::cancelPlainBackup,
        )
        is SettingsDialog.BackupPassword -> BackupPasswordDialog(
            dialog.share,
            onConfirm = { vm.confirmBackupPassword(dialog.share, it) },
            onDismiss = vm::cancelBackupPassword,
        )
        SettingsDialog.BackupOffer -> BackupOfferDialog(
            onBackUp = vm::backUpBeforePassword,
            onSkip = vm::skipBackupOffer,
            onDismiss = vm::dismissDialog,
        )
        is SettingsDialog.KeyChange -> state.keyMode?.let { mode ->
            KeyChangeDialog(
                dialog.flow,
                mode,
                lockOn = state.settings?.biometricLock == true,
                busy = state.busy,
                error = state.dialogError,
                retryAt = retryAt,
                appTime = graph.appTime,
                onConfirm = { current, new -> vm.confirmKeyChange(dialog.flow, current, new) },
                onDismiss = vm::dismissDialog,
            )
        }
        is SettingsDialog.LockPassword ->
            LockPasswordDialog(
                dialog,
                busy = state.busy,
                error = state.dialogError,
                retryAt = retryAt,
                appTime = graph.appTime,
                onConfirm = { vm.confirmLockPassword(dialog, it) },
                onDismiss = vm::dismissDialog,
            )
        SettingsDialog.Notices -> OpenSourceNoticesDialog(graph.dispatchers, onClose = vm::dismissDialog)
    }
}

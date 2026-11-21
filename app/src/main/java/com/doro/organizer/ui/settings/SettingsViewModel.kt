package com.doro.organizer.ui.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.R
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockBackoff
import com.doro.organizer.model.backupFileName
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.LockAvailability
import com.doro.organizer.security.ToggleOutcome
import com.doro.organizer.settings.Settings
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.UiGraph
import com.doro.organizer.ui.slotErrorMessage
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Settings screen's state (ui.md § 9.8): the settings, the committed key mode and the biometric availability, all
 * without database access, plus the open dialog. [busy]: a call of the open dialog or of a switch runs, so its buttons
 * are disabled (ui.md § 8.1). [dialogError]: the `WrongPassword` or `Throttled` of the open password dialog.
 * [retryAtElapsedMs]: the running password backoff of the settings, clamped as every other reader clamps it.
 */
data class SettingsUiState(
    val settings: Settings? = null,
    val retryAtElapsedMs: Long = 0L,
    val keyMode: KeyMode? = null,
    val availability: LockAvailability? = null,
    val dialog: SettingsDialog? = null,
    val busy: Boolean = false,
    val dialogError: AppError? = null,
    val message: UserMessage? = null,
) {
    // Flags, enums and ids only (DEC-70); the settings hold no user data, but their values add nothing to a log.
    override fun toString() = "SettingsUiState(keyMode=$keyMode, dialog=$dialog, busy=$busy, dialogError=$dialogError, " +
        "message=$message)"
}

/**
 * Settings (ui.md § 9): backup, restore, the key changes of ui.md § 8.8 with the backup offer of DEC-73, the biometric
 * lock with its password dialogs (ui.md § 8.14, § 8.16), screenshot protection and Encrypt backups. Outcomes of
 * `DbMaintenance` operations reach the user through the maintenance status host, never through this ViewModel
 * (ui.md § 2.4). Passwords pass through as arrays the receiver owns and zeroes; none is ever kept here (DEC-64).
 */
class SettingsViewModel(
    private val handle: SavedStateHandle,
    private val requestedBackup: Boolean,
    private val maintenance: DbMaintenance,
    private val lock: AppLockController,
    private val settingsStore: SettingsStore,
    private val notes: NoteRepository,
    private val external: ExternalActivities,
    private val appTime: AppTime,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState(dialog = settingsDialogOf(handle[KEY_DIALOG])))
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s -> state.update { it.copy(settings = s, retryAtElapsedMs = retryAtOf(s)) } }
        }
        viewModelScope.launch { maintenance.keyMode.collect { m -> state.update { it.copy(keyMode = m) } } }
    }

    /** On every resume: returning from system settings may have changed the biometric inputs (ui.md § 9.1). */
    fun refreshAvailability() {
        viewModelScope.launch {
            val availability = lock.availability()
            state.update { it.copy(availability = availability) }
        }
    }

    fun dismissDialog() {
        if (!state.value.busy) setDialog(null)
    }

    fun openNotices() = setDialog(SettingsDialog.Notices)

    fun onMessageShown(id: Long) = state.update { if (it.message?.id == id) it.copy(message = null) else it }

    /**
     * The stored backoff runs on the `elapsedRealtime` base, which restarts at boot: clamped to the delay of the
     * failure count (security.md § 5.3), so a reboot never leaves the dialogs locked for the old uptime.
     */
    private fun retryAtOf(s: Settings?): Long =
        s?.let { UnlockBackoff.effectiveRetryAt(it.unlockFailures, it.unlockRetryAtElapsedMs, appTime.elapsedMillis()) } ?: 0L

    // Backup and restore (ui.md § 9.2)

    /** "Backup all data" of the row; a backup the offer of DEC-73 started earlier no longer waits for its end. */
    fun onBackupAll() {
        handle[KEY_AFTER_BACKUP] = false
        startBackup()
    }

    fun onShareBackup() {
        handle[KEY_AFTER_BACKUP] = false
        val requires = maintenance.backupRequiresPassword()
        setDialog(if (requires) SettingsDialog.BackupPassword(true) else SettingsDialog.PlainBackupWarning(true))
    }

    /**
     * The backup the shell's kekBroken notice asked for with "Back up now" (ui.md § 2.4): once per screen, also across
     * recreation. It is the row's flow, so a plaintext backup still warns first (DEC-83).
     */
    fun startRequestedBackup() {
        if (!requestedBackup || handle.get<Boolean>(KEY_REQUESTED_STARTED) == true) return
        handle[KEY_REQUESTED_STARTED] = true
        onBackupAll()
    }

    private fun startBackup() {
        if (maintenance.backupRequiresPassword()) requestDocument() else setDialog(SettingsDialog.PlainBackupWarning(false))
    }

    /** "Back up anyway": the document picker, or the share copy without a password. */
    fun confirmPlainBackup(share: Boolean) {
        setDialog(null)
        if (share) runBackup { maintenance.backupForShare(null) } else requestDocument()
    }

    fun cancelPlainBackup() {
        setDialog(null)
        backupEnded(succeeded = false)
    }

    /** The picker's answer, taken from the `ExternalActivities` inbox; null: cancelled, which is no error. */
    fun onBackupDocument(target: Uri?) {
        when {
            target == null -> backupEnded(succeeded = false)
            maintenance.backupRequiresPassword() -> {
                handle[KEY_TARGET] = target
                setDialog(SettingsDialog.BackupPassword(false))
            }
            else -> runBackup { maintenance.backupToDocument(target, null) }
        }
    }

    /** "Back up" or "Share" of the backup password dialog, after the policy check; owns [password]. */
    fun confirmBackupPassword(share: Boolean, password: CharArray) {
        val target = handle.get<Uri>(KEY_TARGET)
        setDialog(null)
        handle.remove<Uri>(KEY_TARGET)
        when {
            share -> runBackup { maintenance.backupForShare(password) }
            target != null -> runBackup { maintenance.backupToDocument(target, password) }
            else -> {
                password.fill('\u0000') // no document: the dialog outlived its picker result
                backupEnded(succeeded = false)
            }
        }
    }

    /** "Cancel" of the backup password dialog: a document the picker just created is deleted again (ui.md § 8.6). */
    fun cancelBackupPassword() {
        val target = handle.get<Uri>(KEY_TARGET)
        setDialog(null)
        handle.remove<Uri>(KEY_TARGET)
        target?.let { viewModelScope.launch { maintenance.discardDocument(it) } }
        backupEnded(succeeded = false)
    }

    fun onRestore() = external.request(ExternalRequest(ExternalKind.Restore))

    /** The picked backup; the restore's questions and outcome follow its status (ui.md § 8.7). */
    fun onRestoreDocument(source: Uri?) {
        if (source != null) viewModelScope.launch { maintenance.restore(source, MaintenanceOrigin.Ready) }
    }

    /** A picker that could not be launched (architecture.md § Result launchers). */
    fun onLaunchFailed(kind: ExternalKind) {
        post(UserMessage.error(AppError.FileAccess))
        if (kind == ExternalKind.Backup) backupEnded(succeeded = false)
    }

    private fun requestDocument() = external.request(
        ExternalRequest(ExternalKind.Backup, suggestedName = backupFileName(appTime.today())),
    )

    private fun runBackup(backup: suspend () -> AppResult<MaintenanceResult>) {
        viewModelScope.launch { backupEnded(succeeded = backup() is AppResult.Ok) }
    }

    /** After a backup started by the offer of DEC-73: the password dialog on success, else the offer again. */
    private fun backupEnded(succeeded: Boolean) {
        if (handle.get<Boolean>(KEY_AFTER_BACKUP) != true) return
        handle[KEY_AFTER_BACKUP] = false
        setDialog(if (succeeded) SettingsDialog.KeyChange(KeyFlow.SetPassword) else SettingsDialog.BackupOffer)
    }

    // Encryption and startup password (ui.md § 9.3)

    fun onEncryptionSwitch() {
        val flow = when (state.value.keyMode ?: return) {
            KeyMode.PLAIN -> KeyFlow.EncryptOn
            KeyMode.DEVICE, KeyMode.PASSWORD -> KeyFlow.EncryptOff
        }
        setDialog(SettingsDialog.KeyChange(flow))
    }

    /** "Startup password · Off": the backup offer first when the database holds data (DEC-73). */
    fun onSetStartupPassword() {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val anyData = notes.hasAnyData()
            state.update { it.copy(busy = false) }
            when (anyData) {
                is AppResult.Ok -> setDialog(
                    if (anyData.value) SettingsDialog.BackupOffer else SettingsDialog.KeyChange(KeyFlow.SetPassword),
                )
                is AppResult.Err -> post(UserMessage.error(anyData.error))
            }
        }
    }

    fun onChangeStartupPassword() = setDialog(SettingsDialog.KeyChange(KeyFlow.ChangePassword))

    fun onRemoveStartupPassword() = setDialog(SettingsDialog.KeyChange(KeyFlow.RemovePassword))

    fun skipBackupOffer() = setDialog(SettingsDialog.KeyChange(KeyFlow.SetPassword))

    /** "Back up now": the normal backup flow; its end reopens the password dialog or the offer (DEC-73). */
    fun backUpBeforePassword() {
        handle[KEY_AFTER_BACKUP] = true
        setDialog(null)
        startBackup()
    }

    /**
     * Confirm of a key-change dialog with its fields' arrays, null where the flow has no such field (ui.md § 8.8).
     * `WrongPassword` and `Throttled` come back before any change and stay in the dialog, which acknowledges them; any
     * other outcome closes it, and the status host shows it.
     */
    fun confirmKeyChange(flow: KeyFlow, current: CharArray?, new: CharArray?) {
        if (state.value.busy) {
            current?.fill('\u0000')
            new?.fill('\u0000')
            return
        }
        state.update { it.copy(busy = true, dialogError = null) }
        viewModelScope.launch {
            val result = maintenance.changeKey(flow.target, current, new)
            val error = (result as? AppResult.Err)?.error
            if (error == AppError.WrongPassword || error is AppError.Throttled) {
                maintenance.acknowledge()
                state.update { it.copy(busy = false, dialogError = error) }
            } else {
                state.update { it.copy(busy = false) }
                setDialog(null)
            }
        }
    }

    // Biometric lock (ui.md § 9.4)

    /** The switch never flips by itself: it follows the setting once the controller has written it. */
    fun onBiometricSwitch() {
        val on = state.value.settings?.biometricLock ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = lock.confirm(if (on) AuthPurpose.DisableLock else AuthPurpose.EnableLock)
            state.update { it.copy(busy = false) }
            when (result) {
                is AppResult.Ok -> if (result.value == ToggleOutcome.PasswordRequired) {
                    setDialog(if (on) SettingsDialog.LockOffPassword else SettingsDialog.LockOnPassword)
                }
                is AppResult.Err -> post(UserMessage.error(result.error))
            }
        }
    }

    fun onSetUpSlot() = setDialog(SettingsDialog.SlotSetupPassword)

    fun onEnroll() = lock.enroll()

    /**
     * Confirm of the password dialogs of ui.md § 8.14 and § 8.16; the controller owns and zeroes [password].
     * `WrongPassword` and `Throttled` stay in the dialog; every other result closes it.
     */
    fun confirmLockPassword(dialog: SettingsDialog.LockPassword, password: CharArray) {
        val purpose = dialog.purpose
        if (state.value.busy) {
            password.fill('\u0000')
            return
        }
        state.update { it.copy(busy = true, dialogError = null) }
        viewModelScope.launch {
            val result = lock.confirmWithPassword(purpose, password)
            val error = (result as? AppResult.Err)?.error
            if (error == AppError.WrongPassword || error is AppError.Throttled) {
                state.update { it.copy(busy = false, dialogError = error) }
                return@launch
            }
            state.update { it.copy(busy = false) }
            setDialog(null)
            when {
                error != null -> (if (purpose == AuthPurpose.DisableLock) UserMessage.error(error) else slotErrorMessage(error))
                    ?.let(::post)
                purpose == AuthPurpose.SetUpSlot && (result as AppResult.Ok).value == ToggleOutcome.Done ->
                    post(UserMessage.of(UiText.Res(R.string.slot_created), isError = false))
            }
        }
    }

    // Screenshot protection and Encrypt backups (ui.md § 9.5): they toggle at once.

    fun setScreenshotProtection(on: Boolean) = write { settingsStore.setScreenshotProtection(on) }

    fun setEncryptBackups(on: Boolean) = write { settingsStore.setEncryptBackups(on) }

    /** A failed DataStore write is a full disk (`SettingsStore`); the switch keeps showing the stored value. */
    private fun write(edit: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                edit()
            } catch (e: IOException) {
                post(UserMessage.error(AppError.StorageFull))
            }
        }
    }

    private fun setDialog(dialog: SettingsDialog?) {
        handle[KEY_DIALOG] = dialog?.toKey()
        state.update { it.copy(dialog = dialog, dialogError = null) }
    }

    private fun post(message: UserMessage) = state.update { it.copy(message = message) }

    private companion object {
        const val KEY_DIALOG = "settingsDialog"

        /** The document of a backup whose password is being asked; a picker result, never a password (DEC-64). */
        const val KEY_TARGET = "backupTarget"

        /** A backup started by "Back up now" of the offer before a first startup password (DEC-73). */
        const val KEY_AFTER_BACKUP = "backupBeforePassword"

        /** The backup that opened this screen (`requestedBackup`) has been started. */
        const val KEY_REQUESTED_STARTED = "requestedBackupStarted"
    }
}

/** [requestedBackup]: Settings was opened by "Back up now" of the kekBroken notice (`SettingsKey.startBackup`). */
fun settingsFactory(graph: UiGraph, requestedBackup: Boolean) = viewModelFactory {
    initializer {
        SettingsViewModel(
            createSavedStateHandle(),
            requestedBackup,
            graph.dbMaintenance,
            graph.appLockController,
            graph.settingsStore,
            graph.noteRepository,
            graph.externalActivities,
            graph.appTime,
        )
    }
}

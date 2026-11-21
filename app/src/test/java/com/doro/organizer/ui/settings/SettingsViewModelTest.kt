package com.doro.organizer.ui.settings

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.R
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.external.ExternalResult
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockBackoff
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.ToggleOutcome
import com.doro.organizer.settings.Settings
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.unused
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Settings flows of ui.md § 8.5–§ 8.8, § 8.14, § 8.16 and § 9 that need no `Uri` (the document path is the Compose
 * half, `BackupUiTest`): the plaintext warning before any export, the backup offer of DEC-73 and its return, the
 * inline errors of the password dialogs, the outcomes of the lock toggles, and the saved dialog key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val maintenance = ScriptedMaintenance()
    private val lock = ScriptedLock()
    private val notes = ScriptedNotes()
    private val external = RecordingExternal()
    private val settings = FakeSettingsStore(Settings()).apply { load() }
    // 11:00 UTC on Nov 17 is already Nov 18 at UTC+14: the backup name uses the local date (testing.md L-08).
    private val time = FakeAppTime(Instant.parse("2026-11-17T11:00:00Z"), ZoneOffset.ofHours(14))
    private val store = ViewModelStore()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        requestedBackup: Boolean = false,
    ): SettingsViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer { SettingsViewModel(handle, requestedBackup, maintenance, lock, settings, notes, external, time) }
        },
    )[SettingsViewModel::class]

    private val SettingsViewModel.dialog get() = uiState.value.dialog

    @Test
    fun aPlaintextBackup_warnsBeforeThePicker_andNamesTheDocumentWithTheLocalDate() {
        maintenance.requiresPassword = false
        val vm = viewModel()
        vm.onBackupAll()
        assertEquals(SettingsDialog.PlainBackupWarning(share = false), vm.dialog)
        assertEquals(emptyList<ExternalRequest>(), external.requests)

        vm.confirmPlainBackup(share = false)
        assertNull(vm.dialog)
        val request = external.requests.single()
        assertEquals(ExternalKind.Backup, request.kind)
        assertEquals("doro-organizer-backup-2026-11-18.db", request.suggestedName)
    }

    @Test
    fun anEncryptedBackup_asksForTheDocumentAtOnce() {
        val vm = viewModel()
        vm.onBackupAll()
        assertNull(vm.dialog)
        assertEquals(listOf(ExternalKind.Backup), external.requests.map { it.kind })
    }

    @Test
    fun theSharePath_asksThePasswordBeforeTheCopy_orWarnsFirstWithoutOne() {
        val vm = viewModel()
        vm.onShareBackup()
        assertEquals(SettingsDialog.BackupPassword(share = true), vm.dialog)
        vm.confirmBackupPassword(share = true, "a backup password".toCharArray())
        assertEquals(listOf("backupForShare:true"), maintenance.calls)

        maintenance.requiresPassword = false
        vm.onShareBackup()
        assertEquals(SettingsDialog.PlainBackupWarning(share = true), vm.dialog)
        vm.confirmPlainBackup(share = true)
        assertEquals(listOf("backupForShare:true", "backupForShare:false"), maintenance.calls)
        assertEquals("no picker on the share path", emptyList<ExternalRequest>(), external.requests)
    }

    @Test
    fun aFirstStartupPassword_withData_offersABackupFirst_Dec73() {
        notes.anyData = true
        val vm = viewModel()
        vm.onSetStartupPassword()
        assertEquals(SettingsDialog.BackupOffer, vm.dialog)
        vm.skipBackupOffer()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.SetPassword), vm.dialog)
    }

    @Test
    fun aFirstStartupPassword_onAnEmptyDatabase_opensThePasswordDialogDirectly() {
        notes.anyData = false
        val vm = viewModel()
        vm.onSetStartupPassword()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.SetPassword), vm.dialog)
    }

    @Test
    fun backUpNow_aCancelledPicker_bringsTheOfferBack() {
        notes.anyData = true
        val vm = viewModel()
        vm.onSetStartupPassword()
        vm.backUpBeforePassword()
        assertNull(vm.dialog)
        assertEquals(listOf(ExternalKind.Backup), external.requests.map { it.kind })
        vm.onBackupDocument(null)
        assertEquals(SettingsDialog.BackupOffer, vm.dialog)
    }

    @Test
    fun backUpNow_aCancelledPlaintextWarning_bringsTheOfferBack() {
        notes.anyData = true
        maintenance.requiresPassword = false
        val vm = viewModel()
        vm.onSetStartupPassword()
        vm.backUpBeforePassword()
        assertEquals(SettingsDialog.PlainBackupWarning(share = false), vm.dialog)
        vm.cancelPlainBackup()
        assertEquals(SettingsDialog.BackupOffer, vm.dialog)
    }

    @Test
    fun backUpNow_aPickerThatCannotOpen_isAFileError_andBringsTheOfferBack() {
        notes.anyData = true
        val vm = viewModel()
        vm.onSetStartupPassword()
        vm.backUpBeforePassword()
        vm.onLaunchFailed(ExternalKind.Backup)
        assertEquals(SettingsDialog.BackupOffer, vm.dialog)
        assertEquals(UiText.Error(AppError.FileAccess), vm.uiState.value.message?.text)
    }

    @Test
    fun backUpNow_aPasswordDialogWithoutItsDocument_bringsTheOfferBack() {
        notes.anyData = true
        val vm = viewModel()
        vm.onSetStartupPassword()
        vm.backUpBeforePassword()
        // The picker's answer was lost (process death): the backup password dialog has no document to write.
        val password = "a backup password".toCharArray()
        vm.confirmBackupPassword(share = false, password)
        assertEquals(SettingsDialog.BackupOffer, vm.dialog)
        assertArrayEquals("the password is zeroed", CharArray(password.size), password)
        assertEquals(emptyList<String>(), maintenance.calls)
    }

    @Test
    fun aBackupFromTheRows_afterAnUnfinishedOfferBackup_endsWithoutTheOffersDialogs() {
        notes.anyData = true
        fun afterStaleOffer(backup: SettingsViewModel.() -> Unit): SettingsDialog? {
            val handle = SavedStateHandle()
            viewModel(handle).run {
                onSetStartupPassword()
                backUpBeforePassword()
            }
            store.clear() // the process died with the offer's backup still pending
            return viewModel(handle).apply(backup).dialog.also { store.clear() }
        }
        assertNull("Backup all data", afterStaleOffer { onBackupAll(); onBackupDocument(null) })
        assertNull("Share backup", afterStaleOffer { onShareBackup(); cancelBackupPassword() })
    }

    @Test
    fun aRequestedBackup_runsTheRowsFlow_once_alsoAfterRecreation() {
        maintenance.requiresPassword = false
        val handle = SavedStateHandle()
        val vm = viewModel(handle, requestedBackup = true)
        vm.startRequestedBackup()
        assertEquals("the plaintext warning first (DEC-83)", SettingsDialog.PlainBackupWarning(share = false), vm.dialog)
        assertEquals(emptyList<ExternalRequest>(), external.requests)

        vm.cancelPlainBackup()
        vm.startRequestedBackup()
        store.clear()
        viewModel(handle, requestedBackup = true).startRequestedBackup()
        assertNull(viewModel(handle, requestedBackup = true).dialog)
        assertEquals(emptyList<ExternalRequest>(), external.requests)
    }

    @Test
    fun aRequestedEncryptedBackup_asksForTheDocument_andAnOrdinaryVisitStartsNothing() {
        viewModel(requestedBackup = true).startRequestedBackup()
        assertEquals(listOf(ExternalKind.Backup), external.requests.map { it.kind })
        store.clear()
        viewModel().startRequestedBackup()
        assertEquals(1, external.requests.size)
    }

    @Test
    fun theBackoffOfTheDialogs_isClampedAfterAReboot_andKeptWhileItRuns() = runTest {
        val now = time.elapsedMillis()
        // Stored before a reboot with 20 days of uptime; elapsedRealtime restarted (security.md § 5.3).
        settings.setBackoff(failures = 5, retryAtElapsedMs = now + 20L * 24 * 60 * 60 * 1000)
        val vm = viewModel()
        assertEquals(now + UnlockBackoff.delayMillis(5), vm.uiState.value.retryAtElapsedMs)

        settings.setBackoff(failures = 5, retryAtElapsedMs = now + 10_000)
        assertEquals(now + 10_000, vm.uiState.value.retryAtElapsedMs)
        settings.setBackoff(failures = 0, retryAtElapsedMs = 0)
        assertEquals(0L, vm.uiState.value.retryAtElapsedMs)
    }

    @Test
    fun aWrongCurrentPassword_staysInTheDialog_whichAcknowledgesIt() {
        maintenance.mode.value = KeyMode.PASSWORD
        maintenance.keyChangeResult = AppResult.Err(AppError.WrongPassword)
        val vm = viewModel()
        vm.onChangeStartupPassword()
        vm.confirmKeyChange(KeyFlow.ChangePassword, "old".toCharArray(), "new password".toCharArray())
        assertEquals(SettingsDialog.KeyChange(KeyFlow.ChangePassword), vm.dialog)
        assertEquals(AppError.WrongPassword, vm.uiState.value.dialogError)
        assertEquals(listOf("changeKey:PASSWORD:true:true", "acknowledge"), maintenance.calls)
        assertEquals(false, vm.uiState.value.busy)
    }

    @Test
    fun anyOtherKeyChangeOutcome_closesTheDialog_andLeavesItToTheStatusHost() {
        maintenance.keyChangeResult = AppResult.Err(AppError.StorageFull)
        val vm = viewModel()
        vm.onEncryptionSwitch()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.EncryptOn), vm.dialog)
        vm.confirmKeyChange(KeyFlow.EncryptOn, null, null)
        assertNull(vm.dialog)
        assertEquals("the host acknowledges it", listOf("changeKey:DEVICE:false:false"), maintenance.calls)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun theEncryptionSwitch_opensTheDialogOfTheCommittedMode() {
        val vm = viewModel()
        maintenance.mode.value = KeyMode.DEVICE
        vm.onEncryptionSwitch()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.EncryptOff), vm.dialog)
        maintenance.mode.value = KeyMode.PLAIN
        vm.onEncryptionSwitch()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.EncryptOn), vm.dialog)
    }

    @Test
    fun turningTheLockOn_inPasswordMode_opensThePasswordDialog_andOff_theTurnOffDialog() = runTest {
        val vm = viewModel()
        lock.confirmResult = AppResult.Ok(ToggleOutcome.PasswordRequired)
        vm.onBiometricSwitch()
        assertEquals(SettingsDialog.LockOnPassword, vm.dialog)
        assertEquals(listOf("confirm:EnableLock"), lock.calls)

        vm.dismissDialog()
        settings.setBiometricLock(true)
        vm.onBiometricSwitch()
        assertEquals(SettingsDialog.LockOffPassword, vm.dialog)
        assertEquals(listOf("confirm:EnableLock", "confirm:DisableLock"), lock.calls)
    }

    @Test
    fun aCancelledToggle_changesNothing_andAFailedOne_isAnErrorMessage() {
        val vm = viewModel()
        lock.confirmResult = AppResult.Ok(ToggleOutcome.Cancelled)
        vm.onBiometricSwitch()
        assertNull(vm.dialog)
        assertNull(vm.uiState.value.message)
        lock.confirmResult = AppResult.Err(AppError.AuthFailed)
        vm.onBiometricSwitch()
        assertEquals(UserMessage.error(AppError.AuthFailed).text, vm.uiState.value.message?.text)
        assertTrue(vm.uiState.value.message!!.isError)
    }

    @Test
    fun thePasswordFirstDialog_keepsWrongAndThrottledInline() {
        val vm = viewModel()
        vm.onSetUpSlot()
        lock.passwordResult = AppResult.Err(AppError.WrongPassword)
        vm.confirmLockPassword(SettingsDialog.SlotSetupPassword, "wrong".toCharArray())
        assertEquals(SettingsDialog.SlotSetupPassword, vm.dialog)
        assertEquals(AppError.WrongPassword, vm.uiState.value.dialogError)
        lock.passwordResult = AppResult.Err(AppError.Throttled(99_000))
        vm.confirmLockPassword(SettingsDialog.SlotSetupPassword, "again".toCharArray())
        assertEquals(AppError.Throttled(99_000), vm.uiState.value.dialogError)
        assertEquals(listOf("password:SetUpSlot", "password:SetUpSlot"), lock.calls)
    }

    @Test
    fun thePasswordFirstDialog_outcomes() {
        val vm = viewModel()
        fun outcome(dialog: SettingsDialog.LockPassword, result: AppResult<ToggleOutcome>): UserMessage? {
            if (dialog == SettingsDialog.SlotSetupPassword) vm.onSetUpSlot()
            lock.passwordResult = result
            vm.confirmLockPassword(dialog, "the password".toCharArray())
            assertNull("$result closes the dialog", vm.dialog)
            return vm.uiState.value.message.also { it?.let { m -> vm.onMessageShown(m.id) } }
        }
        assertEquals(
            UiText.Res(R.string.slot_created),
            outcome(SettingsDialog.SlotSetupPassword, AppResult.Ok(ToggleOutcome.Done))?.text,
        )
        assertNull(outcome(SettingsDialog.LockOnPassword, AppResult.Ok(ToggleOutcome.Done)))
        assertNull(outcome(SettingsDialog.LockOnPassword, AppResult.Ok(ToggleOutcome.Cancelled)))
        assertNull("Busy: no message", outcome(SettingsDialog.LockOnPassword, AppResult.Err(AppError.Busy)))
        assertEquals(
            UiText.Res(R.string.slot_outcome_unknown),
            outcome(SettingsDialog.SlotSetupPassword, AppResult.Err(AppError.OutcomeUnknown))?.text,
        )
        assertEquals(
            "a message only; it never changes the gate (DEC-86)",
            UiText.Error(AppError.KeyUnavailable),
            outcome(SettingsDialog.LockOnPassword, AppResult.Err(AppError.KeyUnavailable))?.text,
        )
        assertEquals(
            UiText.Error(AppError.StorageFull),
            outcome(SettingsDialog.LockOffPassword, AppResult.Err(AppError.StorageFull))?.text,
        )
    }

    @Test
    fun theOpenDialog_comesBackFromTheSavedState() {
        val handle = SavedStateHandle()
        viewModel(handle).onChangeStartupPassword()
        store.clear()
        assertEquals(SettingsDialog.KeyChange(KeyFlow.ChangePassword), viewModel(handle).dialog)
    }

    @Test
    fun aFailedSettingsWrite_isAStorageFullMessage() {
        settings.failEdits = true
        val vm = viewModel()
        vm.setScreenshotProtection(true)
        assertEquals(UiText.Error(AppError.StorageFull), vm.uiState.value.message?.text)
        assertEquals(false, settings.settings.value?.screenshotProtection)
    }

    private class ScriptedMaintenance : DbMaintenance by unused() {
        val mode = MutableStateFlow<KeyMode?>(KeyMode.PLAIN)
        override val keyMode = mode
        override val status = MutableStateFlow<MaintenanceStatus>(MaintenanceStatus.Idle)
        var requiresPassword = true
        var keyChangeResult: AppResult<MaintenanceResult> = AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.DEVICE))
        val calls = mutableListOf<String>()

        override fun backupRequiresPassword() = requiresPassword

        override suspend fun backupForShare(backupPassword: CharArray?): AppResult<MaintenanceResult> {
            calls += "backupForShare:${backupPassword != null}"
            backupPassword?.fill('\u0000')
            return AppResult.Ok(MaintenanceResult.BackupSaved)
        }

        override suspend fun changeKey(target: KeyMode, currentPassword: CharArray?, newPassword: CharArray?): AppResult<MaintenanceResult> {
            calls += "changeKey:$target:${currentPassword != null}:${newPassword != null}"
            currentPassword?.fill('\u0000')
            newPassword?.fill('\u0000')
            return keyChangeResult
        }

        override fun acknowledge() {
            calls += "acknowledge"
        }
    }

    private class ScriptedLock : AppLockController by unused() {
        var confirmResult: AppResult<ToggleOutcome> = AppResult.Ok(ToggleOutcome.Done)
        var passwordResult: AppResult<ToggleOutcome> = AppResult.Ok(ToggleOutcome.Done)
        val calls = mutableListOf<String>()

        override suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome> {
            calls += "confirm:$purpose"
            return confirmResult
        }

        override suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome> {
            assertTrue("the dialog hands over a password", password.isNotEmpty())
            password.fill('\u0000')
            calls += "password:$purpose"
            return passwordResult
        }
    }

    private class ScriptedNotes : NoteRepository by unused() {
        var anyData = false

        override suspend fun hasAnyData(): AppResult<Boolean> = AppResult.Ok(anyData)
    }

    private class RecordingExternal : ExternalActivities {
        val requests = mutableListOf<ExternalRequest>()
        override val inbox = MutableStateFlow<ExternalResult?>(null)
        override val failures = MutableSharedFlow<ExternalKind>()

        override fun request(request: ExternalRequest) {
            requests += request
        }

        override fun take(kind: ExternalKind): ExternalResult? = null

        override fun bind(activity: ComponentActivity) = Unit
    }
}

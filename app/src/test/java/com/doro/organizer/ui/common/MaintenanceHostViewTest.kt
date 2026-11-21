package com.doro.organizer.ui.common

import com.doro.organizer.R
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maintenance status host's decisions (ui.md § 8.7, § 8.12, § 9.6): what each status renders at each place, the
 * failure texts, and the key-change snackbar mapping. The rendering and the share sheet (an `android.net.Uri`) are the
 * Compose half (`MaintenanceStatusHostTest`, `BackupUiTest`).
 */
class MaintenanceHostViewTest {
    private val preview = RestorePreview(3, 1, 2, KeyMode.PLAIN, encryptionTurnsOff = false)
    private val step = PipelineStep.entries.first()

    private fun view(status: MaintenanceStatus, place: HostPlace = HostPlace.Shell, inMaintenance: Boolean = false, corrupt: Boolean = false) =
        hostViewOf(status, place, inMaintenance, corrupt)

    private fun failed(op: MaintenanceOp, error: AppError, lost: Boolean = false) =
        MaintenanceStatus.Finished(op, AppResult.Err(error), previousContentLost = lost)

    private fun done(op: MaintenanceOp, result: MaintenanceResult) = MaintenanceStatus.Finished(op, AppResult.Ok(result))

    @Test
    fun idle_andRunningOperations_renderNothing_exceptARestoreBeforeItsConfirmation() {
        assertEquals(HostView.None, view(MaintenanceStatus.Idle))
        assertEquals(HostView.None, view(MaintenanceStatus.Running(MaintenanceOp.Backup, step)))
        assertEquals(HostView.None, view(MaintenanceStatus.Running(MaintenanceOp.KeyChange, step)))
        assertEquals(HostView.Checking(0.5f), view(MaintenanceStatus.Running(MaintenanceOp.Restore, step, 0.5f)))
        assertEquals(HostView.Checking(null), view(MaintenanceStatus.Running(MaintenanceOp.Restore, step), HostPlace.Screen))
        // After the confirmation the gate is in Maintenance and its overlay or screen shows the swap (ui.md § 10.6).
        assertEquals(HostView.None, view(MaintenanceStatus.Running(MaintenanceOp.Restore, step), inMaintenance = true))
    }

    @Test
    fun theRestoreQuestions_showOnAnUnlockedHost_andWaitWhileLocked() {
        val questions = listOf(
            MaintenanceStatus.NeedsBackupPassword(retry = true) to HostView.BackupPassword(true),
            MaintenanceStatus.NeedsDatabasePassword(AppError.WrongPassword) to HostView.DatabasePassword(AppError.WrongPassword),
            MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = true) to HostView.Fingerprint(true),
            MaintenanceStatus.NeedsConfirmation(preview) to HostView.Confirm(preview),
            MaintenanceStatus.Running(MaintenanceOp.Restore, step) to HostView.Checking(null),
        )
        for ((status, expected) in questions) {
            assertEquals(expected, view(status, HostPlace.Shell))
            assertEquals(expected, view(status, HostPlace.Screen))
            assertEquals("$status over a lock", HostView.None, view(status, HostPlace.Locked))
        }
    }

    @Test
    fun theKeyChangeDialogsOwnErrors_areIgnoredEverywhere() {
        for (error in listOf(AppError.WrongPassword, AppError.Throttled(5_000))) {
            for (place in HostPlace.entries) {
                assertEquals(HostView.None, view(failed(MaintenanceOp.KeyChange, error), place))
            }
        }
    }

    @Test
    fun aCancelledRestore_andBusy_areAcknowledgedSilently() {
        val cancelled = done(MaintenanceOp.Restore, MaintenanceResult.RestoreCancelled)
        assertEquals(HostView.Acknowledge, view(cancelled, HostPlace.Shell))
        assertEquals(HostView.Acknowledge, view(cancelled, HostPlace.Screen))
        assertEquals(HostView.None, view(cancelled, HostPlace.Locked))
        assertEquals(HostView.Acknowledge, view(failed(MaintenanceOp.Backup, AppError.Busy)))
        assertEquals(HostView.Acknowledge, view(failed(MaintenanceOp.Erase, AppError.Busy), HostPlace.Locked))
    }

    @Test
    fun successes_waitForTheShell_whichShowsThem() {
        val successes = listOf(
            done(MaintenanceOp.Backup, MaintenanceResult.BackupSaved) to HostView.Snackbar(UiText.Res(R.string.backup_done)),
            done(MaintenanceOp.KeyChange, MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.PASSWORD)) to
                HostView.KeyChanged(KeyMode.PLAIN, KeyMode.PASSWORD),
            done(MaintenanceOp.Restore, MaintenanceResult.Restored(1, 2, 3, encryptionTurnedOff = false)) to
                HostView.Snackbar(UiText.Res(R.string.restore_done)),
            done(MaintenanceOp.Restore, MaintenanceResult.Restored(1, 2, 3, encryptionTurnedOff = true)) to
                HostView.Notice(R.string.restore_done, R.string.restore_encryption_off_body),
            done(MaintenanceOp.StartFresh, MaintenanceResult.StartedFresh) to
                HostView.Notice(R.string.start_fresh_done_title, R.string.start_fresh_done_body),
            done(MaintenanceOp.Erase, MaintenanceResult.Erased) to HostView.Acknowledge,
        )
        for ((status, expected) in successes) {
            assertEquals(expected, view(status, HostPlace.Shell))
            assertEquals("$status on a gate screen", HostView.None, view(status, HostPlace.Screen))
            assertEquals("$status over a lock", HostView.None, view(status, HostPlace.Locked))
        }
    }

    @Test
    fun lockMode_showsOnlyTheFailuresOfAKeyChangeOrAnErase() {
        assertEquals(
            HostView.Failure(R.string.key_change_failed_title, listOf(UiText.Error(AppError.AuthFailed))),
            view(failed(MaintenanceOp.KeyChange, AppError.AuthFailed), HostPlace.Locked),
        )
        assertEquals(
            HostView.Failure(R.string.erase_failed_title, listOf(UiText.Res(R.string.maintenance_unavailable))),
            view(failed(MaintenanceOp.Erase, AppError.DatabaseUnavailable), HostPlace.Locked),
        )
        for (op in listOf(MaintenanceOp.Backup, MaintenanceOp.Restore, MaintenanceOp.StartFresh)) {
            assertEquals(HostView.None, view(failed(op, AppError.StorageFull), HostPlace.Locked))
        }
    }

    @Test
    fun backupFailures_carryTheirMessage() {
        fun message(error: AppError, lost: Boolean = false, corrupt: Boolean = false) =
            (view(failed(MaintenanceOp.Backup, error, lost), corrupt = corrupt) as HostView.Failure).also {
                assertEquals(R.string.backup_failed_title, it.title)
            }.paragraphs.single()
        assertEquals(UiText.Res(R.string.backup_error_overwritten), message(AppError.StorageFull, lost = true))
        assertEquals(UiText.Res(R.string.backup_error_file_access), message(AppError.FileAccess))
        assertEquals(UiText.Res(R.string.backup_error_damaged), message(AppError.DatabaseUnavailable, corrupt = true))
        assertEquals(UiText.Res(R.string.maintenance_unavailable), message(AppError.DatabaseUnavailable))
        assertEquals(UiText.Error(AppError.StorageFull), message(AppError.StorageFull))
    }

    @Test
    fun restoreFailures_addThatTheDataWasNotChanged() {
        val unchanged = UiText.Res(R.string.restore_error_unchanged)
        fun failure(error: AppError) = view(failed(MaintenanceOp.Restore, error), HostPlace.Screen)
        for (reason in BackupRejection.entries) {
            val error = AppError.InvalidBackup(reason)
            assertEquals(HostView.Failure(R.string.restore_error_title, listOf(UiText.Error(error), unchanged)), failure(error))
        }
        assertEquals(
            HostView.Failure(R.string.restore_error_title, listOf(UiText.Res(R.string.restore_error_file_access), unchanged)),
            failure(AppError.FileAccess),
        )
        assertEquals(
            HostView.Failure(R.string.restore_error_title, listOf(UiText.Res(R.string.maintenance_unavailable), unchanged)),
            failure(AppError.DatabaseUnavailable),
        )
        assertEquals(
            HostView.Failure(R.string.restore_error_title, listOf(UiText.Error(AppError.KeyUnavailable), unchanged)),
            failure(AppError.KeyUnavailable),
        )
    }

    @Test
    fun keyChangeAndRecoveryFailures_carryTheirTitles() {
        assertEquals(
            HostView.Failure(R.string.key_change_failed_title, listOf(UiText.Res(R.string.key_change_error_damaged))),
            view(failed(MaintenanceOp.KeyChange, AppError.DatabaseUnavailable), corrupt = true),
        )
        assertEquals(
            HostView.Failure(R.string.start_fresh_failed_title, listOf(UiText.Error(AppError.StorageFull))),
            view(failed(MaintenanceOp.StartFresh, AppError.StorageFull), HostPlace.Screen),
        )
    }

    @Test
    fun anUnknownOutcome_neverClaimsThatNothingChanged() {
        val expected = HostView.Failure(R.string.outcome_unknown_title, listOf(UiText.Res(R.string.error_outcome_unknown)))
        for (op in MaintenanceOp.entries) {
            assertEquals(op.name, expected, view(failed(op, AppError.OutcomeUnknown), HostPlace.Screen))
        }
    }

    @Test
    fun onlyQuestionsNoticesAndFailures_coverTheScreen() {
        assertTrue(view(MaintenanceStatus.NeedsConfirmation(preview)).isDialog)
        assertTrue(view(failed(MaintenanceOp.Backup, AppError.FileAccess)).isDialog)
        assertTrue(view(done(MaintenanceOp.StartFresh, MaintenanceResult.StartedFresh)).isDialog)
        assertFalse(view(MaintenanceStatus.NeedsBiometricUnlock()).isDialog)
        assertFalse(view(done(MaintenanceOp.Backup, MaintenanceResult.BackupSaved)).isDialog)
        assertFalse(view(MaintenanceStatus.Running(MaintenanceOp.KeyChange, step)).isDialog)
    }

    @Test
    fun theKeyChangeSnackbar_followsThePairOfModes() {
        val expected = mapOf(
            Triple(KeyMode.PLAIN, KeyMode.DEVICE, false) to R.string.enc_on_done,
            Triple(KeyMode.PLAIN, KeyMode.PASSWORD, false) to R.string.pw_set_done,
            Triple(KeyMode.DEVICE, KeyMode.PASSWORD, false) to R.string.pw_set_done,
            Triple(KeyMode.PASSWORD, KeyMode.PASSWORD, false) to R.string.pw_changed_done,
            Triple(KeyMode.PASSWORD, KeyMode.PASSWORD, true) to R.string.pw_reset_done,
            Triple(KeyMode.PASSWORD, KeyMode.DEVICE, false) to R.string.pw_removed_done,
            Triple(KeyMode.DEVICE, KeyMode.PLAIN, false) to R.string.enc_off_done,
            Triple(KeyMode.PASSWORD, KeyMode.PLAIN, false) to R.string.enc_off_done,
        )
        for ((pair, text) in expected) assertEquals("$pair", text, keyChangedText(pair.first, pair.second, pair.third))
    }
}

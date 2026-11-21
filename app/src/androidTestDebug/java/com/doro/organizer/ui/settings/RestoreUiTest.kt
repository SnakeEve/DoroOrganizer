package com.doro.organizer.ui.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.Perspective
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertTouchTarget
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.resetRecordingProvider
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * L-09 and L-11, the Compose half (DEC-84; ui.md § 8.7, § 9.2): "Restore from backup" opens any file (`OpenDocument`
 * with `*` / `*`) and restores it with origin `Ready`; the questions and the confirmation follow the status, with the
 * backup's counts and the resulting encryption state; every rejection shows its message and that the data was not
 * changed; after the swap the back stack is All. The Corrupt screen's "Restore from backup" restores with its own
 * origin (ui.md § 10.7). [Scripted] runs over a scripted gate and `DbMaintenance`; [RoundTrip] restores a real backup
 * and checks the counts and the data reset. The round trips and rejections themselves are `RestoreTest` and
 * `RestoreValidationTest`.
 */
@RunWith(Enclosed::class)
class RestoreUiTest {
    /** Over a scripted gate and `DbMaintenance`: the intent, the origin, every question, message and the back stack. */
    @RunWith(AndroidJUnit4::class)
    class Scripted : ScriptedGateComposeTest() {
        @Before
        fun recordIntents() = Intents.init()

        @After
        fun releaseIntents() = Intents.release()

        @Test
        fun restore_opensAnyFile_andRestoresItFromReady() {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(BACKUP)))
            openSettings()
            compose.onNodeWithText(RESTORE_FROM_BACKUP).performClick()

            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasType("*/*")))
            assertEquals(listOf("restore:$BACKUP:${MaintenanceOrigin.Ready}"), scene.maintenance.calls)
        }

        @Test
        fun theCorruptScreen_restoresWithItsOwnOrigin() {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(BACKUP)))
            scene.launch(GateState.Corrupt)
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.onNodeWithText(RESTORE_FROM_BACKUP).assertTouchTarget().performClick()

            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasType("*/*")))
            assertEquals(listOf("restore:$BACKUP:${MaintenanceOrigin.Corrupt}"), scene.maintenance.calls)
        }

        @Test
        fun aPickerThatCannotOpen_onTheRecoveryScreen_saysTheFileCannotBeRestored_alsoAfterARotation() {
            scene.launch(GateState.Corrupt)
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.launchFailures.subscriptionCount.value > 0 }
            scene.launchFailures.tryEmit(ExternalKind.Restore)

            ui.awaitText(ERROR_TITLE)
            compose.onNodeWithText(FILE_NOT_READ).assertExists()
            compose.onNodeWithText(UNCHANGED).assertExists()
            scene.recreateActivity()
            ui.awaitText(ERROR_TITLE)
            compose.onNodeWithText(OK).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { ui.countText(ERROR_TITLE) == 0 }
            assertEquals("no restore started", emptyList<String>(), scene.maintenance.calls)
        }

        @Test
        fun aCancelledPicker_startsNoRestore() {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            openSettings()
            compose.onNodeWithText(RESTORE_FROM_BACKUP).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { Intents.getIntents().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(emptyList<String>(), scene.maintenance.calls)
        }

        @Test
        fun theConfirmation_showsTheBackupsCounts_theReplacement_andTheResultingState() {
            openSettings()
            scene.maintenance.status.value = MaintenanceStatus.NeedsConfirmation(RestorePreview(12, 1, 3, KeyMode.PLAIN, false))
            ui.awaitText(CONFIRM_TITLE)
            compose.onNodeWithText("The backup contains 12 notes, 1 person and 3 projects.").assertExists()
            compose.onNodeWithText(REPLACE).assertExists()
            compose.onNodeWithText("$RESULT_PLAIN $SETTINGS_KEPT").assertExists()

            compose.onNodeWithText(RESTORE).performClick()
            assertEquals(listOf("answerConfirmation:true"), scene.maintenance.calls)
        }

        @Test
        fun theConfirmation_overEncryptedDataFromKeyUnavailable_saysEncryptionTurnsOff() {
            scene.launch(GateState.KeyUnavailable)
            ui.awaitRecoveryScreen(GateUi.KEY_UNAVAILABLE_TITLE)
            scene.maintenance.status.value = MaintenanceStatus.NeedsConfirmation(RestorePreview(1, 2, 1, KeyMode.PLAIN, true))
            ui.awaitText(CONFIRM_TITLE)
            compose.onNodeWithText("The backup contains 1 note, 2 people and 1 project.").assertExists()
            compose.onNodeWithText("$RESULT_PLAIN $TURNS_OFF").assertExists()
            compose.onNodeWithText(SETTINGS_KEPT, substring = true).assertDoesNotExist()

            compose.onNodeWithText(CANCEL).performClick()
            assertEquals(listOf("answerConfirmation:false"), scene.maintenance.calls)
        }

        @Test
        fun theBackupPasswordQuestion_repeatsAfterAWrongPassword_andCancels() {
            openSettings()
            scene.maintenance.status.value = MaintenanceStatus.NeedsBackupPassword(retry = false)
            ui.awaitText(ENCRYPTED_BACKUP)
            compose.onNodeWithText(RETRY).assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("the backup password")
            compose.onNodeWithText(CONTINUE).performClick()
            assertEquals(listOf("answerPassword:true"), scene.maintenance.calls)

            scene.maintenance.status.value = MaintenanceStatus.NeedsBackupPassword(retry = true)
            ui.awaitText(RETRY)
            compose.onNodeWithText(CANCEL).performClick()
            assertEquals(listOf("answerPassword:true", "answerPassword:false"), scene.maintenance.calls)
        }

        @Test
        fun checkingTheBackup_canBeCancelled() {
            openSettings()
            scene.maintenance.status.value = MaintenanceStatus.Running(MaintenanceOp.Restore, PipelineStep.entries.first())
            ui.awaitText(CHECKING)
            compose.onNodeWithText(CANCEL).performClick()
            assertEquals(listOf("cancelRestore"), scene.maintenance.calls)
        }

        @Test
        fun everyRejection_showsItsMessage_andThatTheDataWasNotChanged() {
            openSettings()
            val messages = BackupRejection.entries.associate { AppError.InvalidBackup(it) to REJECTIONS.getValue(it) } +
                mapOf(
                    AppError.FileAccess to FILE_NOT_READ,
                    AppError.DatabaseUnavailable to "The operation could not run right now. Nothing was changed. Try again.",
                )
            for ((error, message) in messages) {
                scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Restore, AppResult.Err(error))
                ui.awaitText(ERROR_TITLE)
                compose.onNodeWithText(message).assertExists()
                compose.onNodeWithText(UNCHANGED).assertExists()
                compose.onNodeWithText(OK).performClick()
                compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
                compose.onNodeWithText(ERROR_TITLE).assertDoesNotExist()
            }
        }

        @Test
        fun afterARestore_theBackStackIsAll_andTheShellSaysSo() = runBlocking<Unit> {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            // Settings opened from another tab: the data reset drops that tab's stack as well (architecture.md § Data reset).
            compose.onNodeWithTag(PROJECT_TAB).performClick()
            compose.onNodeWithTag(SETTINGS_ACTION).performClick()
            ui.awaitTag(SETTINGS_LIST)
            // The swap replaced the data: a data reset (architecture.md § Data reset) and the outcome.
            scene.settings.onDataReplaced()
            val restored = MaintenanceResult.Restored(12, 1, 3, encryptionTurnedOff = false)
            scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Restore, AppResult.Ok(restored))

            ui.awaitText(RESTORED)
            compose.onNodeWithTag(SETTINGS_LIST).assertDoesNotExist()
            compose.onNodeWithTag(ALL_TAB).assertIsSelected()
            compose.onNodeWithText(ALL_NOTES).assertExists()
            compose.onNodeWithTag(PROJECT_TAB).performClick()
            compose.onNodeWithTag(PROJECT_TAB).assertIsSelected()
            compose.onNodeWithTag(SETTINGS_LIST).assertDoesNotExist()
        }

        private fun openSettings() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            compose.onNodeWithTag(SETTINGS_ACTION).performClick()
            ui.awaitTag(SETTINGS_LIST)
        }
    }

    /**
     * The L-09 round trip on the real stack: a real backup restored from Settings, opened from a Project tab that
     * shows a remembered project, with a search on All. The confirmation shows the backup's counts, not the live
     * data's; afterwards All is selected, the search is gone, the remembered ids are cleared and the Project tab is
     * back at its picker (architecture.md § Data reset, DEC-58).
     */
    @RunWith(AndroidJUnit4::class)
    class RoundTrip : ListComposeTest() {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext

        @Before
        fun recordIntents() {
            resetRecordingProvider(context)
            Intents.init()
        }

        @After
        fun releaseIntents() {
            Intents.release()
            resetRecordingProvider(context)
        }

        @Test
        fun aRestoreFromSettings_showsTheBackupsCounts_thenClearsNavigationFiltersAndRememberedSelections() = runBlocking<Unit> {
            val setup = MaintenanceSetup(databases, vaults, scope.scope, scene.settings, scene.time, scene.kek, scene.bio)
            setup.start()
            val before = TestRows(setup.holder.openDatabase().openHelper.writableDatabase)
            val project = before.project("Alpha", now)
            val person = before.person("Alice", now)
            val projectNote = before.note("for Alpha", createdAt = now).also { before.linkProject(it, project) }
            val personNote = before.note("for Alice", createdAt = now + 1).also { before.linkPerson(it, person) }
            val orphan = before.note("alone", createdAt = now + 2)
            val backup = setup.backupTo(context, "round-trip.db")
            // Data the restore must replace: the live counts differ from the backup's.
            val later = TestRows(setup.holder.openDatabase().openHelper.writableDatabase).note("after the backup", createdAt = now + 3)
            setup.holder.close()
            scene.coldStart()
            ui.awaitShell()
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(backup.uri)))

            openTab(Perspective.Project)
            ui.awaitTag(pickerRow(project))
            compose.onNodeWithTag(pickerRow(project)).performClick()
            awaitRow(projectNote)
            compose.waitUntil(GateUi.TIMEOUT_MS) { runBlocking { scene.settings.snapshot().rememberedProjectId == project } }
            openTab(Perspective.All)
            compose.onNodeWithContentDescription(SEARCH).performClick()
            compose.onNodeWithTag(SEARCH_FIELD).performTextInput("zzz")
            awaitNoRow(orphan)
            openTab(Perspective.Project)
            awaitRow(projectNote)
            compose.onNodeWithTag(SETTINGS_ACTION).performClick()
            ui.awaitTag(SETTINGS_LIST)
            compose.onNodeWithText(RESTORE_FROM_BACKUP).performClick()

            ui.awaitText(CONFIRM_TITLE)
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasType("*/*")))
            compose.onNodeWithText("The backup contains 3 notes, 1 person and 1 project.").assertExists()
            compose.onNodeWithText(RESTORE).performClick()

            ui.awaitText(RESTORED)
            // All of the new data epoch, without the search: the row it hid shows again.
            listOf(orphan, personNote, projectNote).forEach(::awaitRow)
            awaitNoRow(later)
            compose.onNodeWithTag(ALL_TAB).assertIsSelected()
            compose.onNodeWithTag(SETTINGS_LIST).assertDoesNotExist()
            compose.onNodeWithTag(SEARCH_FIELD).assertDoesNotExist()
            assertNull(scene.settings.snapshot().rememberedProjectId)

            openTab(Perspective.Project)
            compose.onNodeWithTag(PROJECT_TAB).assertIsSelected()
            ui.awaitTag(pickerRow(project))
            compose.onNodeWithTag(rowTag(projectNote)).assertDoesNotExist()
            compose.onNodeWithTag(SETTINGS_LIST).assertDoesNotExist()
        }

        private fun pickerRow(id: Long) = "picker_row_$id"
    }

    private companion object {
        val BACKUP: Uri = Uri.parse("content://com.doro.organizer.test.documents/document/restore")
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val ALL_TAB = "tab_All"
        const val PROJECT_TAB = "tab_Project"
        const val SEARCH_FIELD = "search_field"

        // Literals copied from ui.md § 11.
        const val RESTORE_FROM_BACKUP = "Restore from backup"
        const val CORRUPT_TITLE = "The database is damaged"
        const val CONFIRM_TITLE = "Restore this backup?"
        const val REPLACE = "Everything now in the app will be replaced. This cannot be undone."
        const val RESULT_PLAIN = "After the restore, your data will be stored unencrypted on this device."
        const val SETTINGS_KEPT = "Your security settings stay as they are."
        const val TURNS_OFF = "Database encryption will be turned off. You can turn it on again in Settings afterwards."
        const val RESTORE = "Restore"
        const val CANCEL = "Cancel"
        const val CONTINUE = "Continue"
        const val OK = "OK"
        const val ENCRYPTED_BACKUP = "Encrypted backup"
        const val RETRY = "Wrong password, or the file is not a Doro Organizer backup."
        const val CHECKING = "Checking the backup…"
        const val ERROR_TITLE = "This file cannot be restored"
        const val UNCHANGED = "Your current data was not changed."
        const val FILE_NOT_READ = "The selected file could not be read."
        const val RESTORED = "Backup restored"
        const val ALL_NOTES = "All notes"
        val REJECTIONS = mapOf(
            BackupRejection.NotABackup to "This file is not a Doro Organizer backup.",
            BackupRejection.NewerVersion to
                "This backup was made by a newer version of Doro Organizer. Update the app, then try again.",
            BackupRejection.Incompatible to "This backup does not match this version of Doro Organizer.",
            BackupRejection.Damaged to "This backup is damaged or incomplete.",
        )
    }
}

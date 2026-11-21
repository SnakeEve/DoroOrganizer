package com.doro.organizer.ui.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.IntentCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasFlag
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import java.time.Instant
import java.time.ZoneOffset
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-08, the Compose half (DEC-83; ui.md § 8.5, § 8.6, § 9.2): through the real `ExternalActivities` launchers, answered
 * by Espresso-Intents. The `CreateDocument` intent has type `application/octet-stream` and the name of the local date
 * (2026-11-17T11:00Z at UTC+14 is Nov 18); the plaintext warning precedes it; an encrypted backup asks the password
 * after the document, and its Cancel discards the document; the outcome shows as a snackbar, a share copy as the share
 * sheet. The backup offer before a first startup password (DEC-73) continues with the password dialog after a saved
 * backup, and comes back after the failure message of a failed one. The export itself is `BackupTest`.
 */
@RunWith(AndroidJUnit4::class)
class BackupUiTest : ScriptedGateComposeTest() {
    @Before
    fun recordIntents() {
        Intents.init()
        scene.time.setZone(ZoneOffset.ofHours(14))
        scene.time.setWallClock(Instant.parse("2026-11-17T11:00:00Z"))
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(DOCUMENT)))
    }

    @After
    fun releaseIntents() = Intents.release()

    @Test
    fun aPlaintextBackup_warnsFirst_thenAsksForTheDatedDocument() {
        scene.maintenance.keyMode.value = KeyMode.PLAIN
        scene.maintenance.requiresPassword = false
        openSettings()
        compose.onNodeWithText(BACKUP_ALL_DATA).performClick()

        ui.awaitText(PLAIN_TITLE)
        compose.onNodeWithText(PLAIN_BODY).assertExists()
        assertEquals("no picker before the warning", 0, Intents.getIntents().size)
        compose.onNodeWithText(BACK_UP_ANYWAY).performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }
        intended(
            allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("application/octet-stream"),
                hasExtra(Intent.EXTRA_TITLE, "doro-organizer-backup-2026-11-18.db"),
            ),
        )
        assertEquals(listOf("backupToDocument:$DOCUMENT:false"), scene.maintenance.calls)
    }

    @Test
    fun anEncryptedBackup_asksThePasswordAfterTheDocument() {
        openSettings()
        compose.onNodeWithText(BACKUP_ALL_DATA).performClick()
        ui.awaitText(PASSWORD_TITLE)
        compose.onNodeWithText(PLAIN_TITLE).assertDoesNotExist()
        intended(hasAction(Intent.ACTION_CREATE_DOCUMENT))

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(BACKUP_PASSWORD)
        fields[1].performTextInput(BACKUP_PASSWORD)
        compose.onNodeWithText(ACKNOWLEDGE).performClick()
        compose.onNodeWithText(BACK_UP).performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }
        assertEquals(listOf("backupToDocument:$DOCUMENT:true"), scene.maintenance.calls)
    }

    @Test
    fun cancellingTheBackupPassword_discardsTheDocument() {
        openSettings()
        compose.onNodeWithText(BACKUP_ALL_DATA).performClick()
        ui.awaitText(PASSWORD_TITLE)
        compose.onNodeWithText(CANCEL).performClick()

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.isNotEmpty() }
        assertEquals(listOf("discard:$DOCUMENT"), scene.maintenance.calls)
        compose.onNodeWithText(PASSWORD_TITLE).assertDoesNotExist()
    }

    @Test
    fun aSavedBackup_showsItsSnackbar_thenIsAcknowledged() {
        openSettings()
        scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Backup, AppResult.Ok(MaintenanceResult.BackupSaved))
        ui.awaitText(BACKUP_SAVED)
        compose.waitUntil(SNACKBAR_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
    }

    @Test
    fun aShareCopy_opensTheShareSheet_withAReadGrantOnly() {
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        scene.launch(GateState.Ready)
        ui.awaitShell()
        val shared = MaintenanceResult.BackupShared(SHARED, "doro-organizer-backup-2026-11-18.db")
        scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.Backup, AppResult.Ok(shared))

        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        compose.waitUntil(GateUi.TIMEOUT_MS) { Intents.getIntents().isNotEmpty() }
        intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
                hasExtra(Intent.EXTRA_TITLE, "Share backup"),
                hasExtra(
                    equalTo(Intent.EXTRA_INTENT),
                    allOf(
                        hasAction(Intent.ACTION_SEND),
                        hasType("application/octet-stream"),
                        hasExtra(Intent.EXTRA_STREAM, SHARED),
                        hasFlag(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    ),
                ),
            ),
        )
        val send = checkNotNull(IntentCompat.getParcelableExtra(Intents.getIntents().single(), Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("never a write grant", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun theOffersBackup_whenSaved_opensSetAStartupPassword_Dec73() {
        backUpFromTheOffer()
        ui.awaitText(SET_PASSWORD_TITLE)
        assertEquals(listOf("backupToDocument:$DOCUMENT:true"), scene.maintenance.calls)
        compose.onNodeWithText(OFFER_TITLE).assertDoesNotExist()
    }

    @Test
    fun theOffersBackup_whenFailed_showsTheFailureFirst_thenTheOfferAgain_Dec73() {
        scene.maintenance.backupResult = AppResult.Err(AppError.FileAccess)
        backUpFromTheOffer()
        ui.awaitText(BACKUP_FAILED)
        compose.onNodeWithText(BACKUP_FILE_ACCESS).assertExists()
        compose.onNodeWithText(OFFER_TITLE).assertDoesNotExist()
        compose.onNodeWithText(OK).performClick()

        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(SET_PASSWORD_TITLE).assertDoesNotExist()
    }

    /** `DEVICE` mode with data: "Startup password", "Back up now", the document and its backup password. */
    private fun backUpFromTheOffer() {
        scene.hasAnyData = true
        scene.maintenance.keyMode.value = KeyMode.DEVICE
        openSettings()
        compose.onNodeWithTag(PASSWORD_SET).performScrollTo().performClick()
        ui.awaitText(OFFER_TITLE)
        compose.onNodeWithText(BACK_UP_NOW).performClick()
        ui.awaitText(PASSWORD_TITLE)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(BACKUP_PASSWORD)
        fields[1].performTextInput(BACKUP_PASSWORD)
        compose.onNodeWithText(ACKNOWLEDGE).performClick()
        compose.onNodeWithText(BACK_UP).performClick()
    }

    private fun openSettings() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(SETTINGS_ACTION).performClick()
        ui.awaitTag(SETTINGS_LIST)
    }

    private companion object {
        val DOCUMENT: Uri = Uri.parse("content://com.doro.organizer.test.documents/document/backup")
        val SHARED: Uri = Uri.parse("content://com.doro.organizer.test.fileprovider/share/backup")
        const val SNACKBAR_MS = 15_000L
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val PASSWORD_SET = "settings_password_set"
        const val BACKUP_PASSWORD = "a long backup passphrase"

        // Literals copied from ui.md § 11.
        const val BACKUP_ALL_DATA = "Backup all data"
        const val PLAIN_TITLE = "Back up without encryption?"
        const val PLAIN_BODY = "This backup will not be encrypted. Anyone who gets the file can read all your notes, " +
            "people and projects. To protect backups with a password, turn on Encrypt backups in Settings."
        const val BACK_UP_ANYWAY = "Back up anyway"
        const val PASSWORD_TITLE = "Protect the backup"
        const val ACKNOWLEDGE = "I understand this cannot be recovered"
        const val BACK_UP = "Back up"
        const val CANCEL = "Cancel"
        const val BACKUP_SAVED = "Backup saved"
        const val OFFER_TITLE = "Back up first?"
        const val BACK_UP_NOW = "Back up now"
        const val SET_PASSWORD_TITLE = "Set a startup password"
        const val BACKUP_FAILED = "Backup failed"
        const val BACKUP_FILE_ACCESS = "The backup file could not be written. If an incomplete file remains at the " +
            "chosen location, delete it."
        const val OK = "OK"
    }
}

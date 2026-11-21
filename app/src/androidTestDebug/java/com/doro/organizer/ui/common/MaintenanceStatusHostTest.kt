package com.doro.organizer.ui.common

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.PromptResult
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.session.GateState
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.CorruptRestoreScene
import com.doro.organizer.testing.FakeBiometricGateway
import com.doro.organizer.testing.FakeKeyVault
import com.doro.organizer.testing.GateComposeTest
import com.doro.organizer.testing.GateScene
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.resetRecordingProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * The maintenance status host on the gate screens (ui.md § 8.7, § 8.12): L-43 and L-51 (h), the Compose half of the
 * restore's fingerprint question on the Corrupt screen, and the lock mode of a locked password screen (DEC-66).
 * [ScriptedHost] scripts the controller and the restore; [RealStack] runs the fallback prompt across a rotation on the
 * real stack. The prompt mechanics themselves are `RestoreKeyGateTest` and `RestoreKekFallbackTest`.
 */
@RunWith(Enclosed::class)
class MaintenanceStatusHostTest {
    /** The host over a scripted controller and restore: one `slotPrompt(RestoreKey)` per status value. */
    @RunWith(AndroidJUnit4::class)
    class ScriptedHost : ScriptedGateComposeTest() {
        @Test
        fun aRotationWhileTheFingerprintPromptIsUp_asksNothingTwice_andTheAnswerStillArrives() {
            val held = CompletableDeferred<AppResult<SlotCipher?>>()
            scene.lock.restoreKeyHeld = held
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = false)
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.lock.calls.isNotEmpty() }

            scene.recreateActivity()
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.waitForIdle()
            held.complete(AppResult.Ok(FakeKeyVault().newSlotCipher()))
            awaitCalls(1)
            compose.waitForIdle()

            assertEquals("one prompt (promptInFlight)", listOf("prompt:RestoreKey"), scene.lock.calls)
            assertEquals(listOf("answerBiometric:true"), scene.maintenance.calls)
        }

        @Test
        fun afterAKekFailure_aRotationDuringThePrompt_keepsReportThenOnePrompt() {
            scene.lock.kekFailureLive = true
            val held = CompletableDeferred<AppResult<SlotCipher?>>()
            scene.lock.restoreKeyHeld = held
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = true)
            compose.waitUntil(GateUi.TIMEOUT_MS) { "prompt:RestoreKey" in scene.lock.calls }

            scene.recreateActivity()
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.waitForIdle()
            held.complete(AppResult.Ok(null))
            awaitCalls(1)
            compose.waitForIdle()

            assertEquals(listOf("report", "prompt:RestoreKey"), scene.lock.calls)
            assertEquals(listOf("answerBiometric:false"), scene.maintenance.calls)
        }

        @Test
        fun theFingerprintQuestion_promptsOnce_andAnswersWithItsCipher_alsoAcrossARotation() {
            scene.lock.restoreKeyResult = AppResult.Ok(FakeKeyVault().newSlotCipher())
            onTheCorruptScreen()
            val question = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = false)
            scene.maintenance.status.value = question
            awaitCalls(1)
            scene.recreateActivity()
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.waitForIdle()

            assertEquals(listOf("prompt:RestoreKey"), scene.lock.calls)
            assertEquals(listOf("answerBiometric:true"), scene.maintenance.calls)
        }

        @Test
        fun afterAKekFailure_theHostReportsItBeforeTheOnePrompt() {
            scene.lock.kekFailureLive = true
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = false)
            awaitCalls(1)
            scene.maintenance.status.value = MaintenanceStatus.NeedsDatabasePassword(error = null)
            ui.awaitText(DATABASE_PASSWORD_TITLE)
            scene.maintenance.status.value = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = true)
            awaitCalls(2)

            assertEquals(listOf("prompt:RestoreKey", "report", "prompt:RestoreKey"), scene.lock.calls)
            assertEquals(
                "the fingerprint answered null both times",
                listOf("answerBiometric:false", "answerBiometric:false"),
                scene.maintenance.calls,
            )
        }

        @Test
        fun afterAKekFailure_withoutALiveSlot_theRestoreEndsWithoutAPrompt() {
            scene.lock.kekFailureLive = false
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = true)
            awaitCalls(1)
            compose.waitForIdle()

            assertEquals(listOf("report"), scene.lock.calls)
            assertEquals(listOf("answerBiometric:false"), scene.maintenance.calls)
        }

        @Test
        fun theDatabasePasswordQuestion_showsAWrongPassword_andACountdownWhileThrottled() {
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsDatabasePassword(AppError.WrongPassword)
            ui.awaitText(DATABASE_PASSWORD_TITLE)
            compose.onNodeWithText(WRONG_PASSWORD).assertExists()

            val retryAt = scene.time.elapsedMillis() + 65_000
            scene.maintenance.status.value = MaintenanceStatus.NeedsDatabasePassword(AppError.Throttled(retryAt))
            ui.awaitText("Too many attempts. Try again in 1:05.")
            compose.onNodeWithText(WRONG_PASSWORD).assertDoesNotExist()
        }

        @Test
        fun theDatabasePasswordQuestion_saysStorageIsFull_forAnAttemptThatCouldNotBeCounted() {
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsDatabasePassword(AppError.StorageFull)
            ui.awaitText(DATABASE_PASSWORD_TITLE)
            // DEC-75: the password was not checked, so it is not called wrong.
            compose.onNodeWithText(PASSWORD_STORAGE_FULL).assertExists()
            compose.onNodeWithText(WRONG_PASSWORD).assertDoesNotExist()
        }

        @Test
        fun lockMode_waitsWithTheRestoreQuestions_butShowsAFailedErase() {
            scene.launch(GateState.Locked(Resume.Ready, password = entry))
            ui.awaitPasswordScreen()
            scene.maintenance.status.value = MaintenanceStatus.NeedsConfirmation(RestorePreview(4, 0, 0, KeyMode.PLAIN, false))
            compose.waitForIdle()
            compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()

            scene.maintenance.status.value =
                MaintenanceStatus.Finished(MaintenanceOp.Erase, AppResult.Err(AppError.DatabaseUnavailable))
            ui.awaitText(ERASE_FAILED)
            compose.onNodeWithText(UNAVAILABLE).assertExists()
            compose.onNodeWithText(OK).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        }

        @Test
        fun aStartFreshFailure_showsOnTheRecoveryScreen() {
            onTheCorruptScreen()
            scene.maintenance.status.value = MaintenanceStatus.Finished(MaintenanceOp.StartFresh, AppResult.Err(AppError.StorageFull))
            ui.awaitText(START_FRESH_FAILED)
            compose.onNodeWithText(OK).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.status.value == MaintenanceStatus.Idle }
        }

        private fun onTheCorruptScreen() {
            scene.launch(GateState.Corrupt)
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
        }

        private fun awaitCalls(count: Int) =
            compose.waitUntil(GateUi.TIMEOUT_MS) { scene.maintenance.calls.size >= count }
    }

    /**
     * L-51 (h) over the real gate, controller, `DbMaintenance` and `RestoreKeyViewModel` with `FakeBiometricGateway`:
     * `PASSWORD` mode, the lock on, a live slot, the Corrupt screen reached through the slot and the KEK alias deleted
     * there. A rotation while the fallback prompt after the password's KEK failure is up shows no second prompt
     * (`promptInFlight`), and that prompt's answer still reaches the restore.
     */
    @RunWith(AndroidJUnit4::class)
    class RealStack : GateComposeTest() {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val maintenance get() = app.container.dbMaintenance

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
        fun aRotationWhileTheFallbackPromptIsUp_showsOnePrompt_andItsAnswerReachesTheConfirmation() = runBlocking<Unit> {
            val setup = MaintenanceSetup(databases, vaults, scope.scope, scene.settings, scene.time, scene.kek, scene.bio)
            val restore = CorruptRestoreScene(setup, vaults, context)
            restore.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD)
            scene.gateway.succeed()
            scene.coldStart()
            scene.awaitGate(GateState.Corrupt) // through the slot prompt
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            vaults.deleteKekAlias()
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(restore.backup.uri)))

            ui.click(RECOVERY_RESTORE)
            enterAndContinue(BACKUP_PASSWORD_FIELD, BACKUP_PASSWORD)
            awaitPrompts(2) // the host's prompt for the first fingerprint question, answered with "Use password"
            GateScene.onMain { scene.gateway.answer(PromptResult.Error(BiometricPrompt.ERROR_NEGATIVE_BUTTON, USE_PASSWORD)) }
            val beforeKekFailure = scene.gateway.biometricPromptCount
            enterAndContinue(DATABASE_PASSWORD_FIELD, DATABASE_PASSWORD) // passes the GCM tag, but the KEK is gone

            awaitPrompts(beforeKekFailure + 1) // the host's fallback prompt
            assertEquals(MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure = true), maintenance.status.value)
            scene.recreateActivity()
            ui.awaitRecoveryScreen(CORRUPT_TITLE)
            compose.waitForIdle()

            assertEquals("one prompt after the KEK failure (promptInFlight)", beforeKekFailure + 1, scene.gateway.biometricPromptCount)
            assertTrue("the prompt survived the rotation", scene.gateway.hasOpenPrompt)
            assertEquals(FakeBiometricGateway.Prompt(UNLOCK_TITLE, false, USE_PASSWORD, withCrypto = true), scene.gateway.prompts.last())
            GateScene.onMain { scene.gateway.answer(PromptResult.Succeeded(cryptoMatches = true)) }

            ui.awaitText(CONFIRM_TITLE)
            compose.onNodeWithText(countsOf(restore.atBackup)).assertExists()
            assertEquals(beforeKekFailure + 1, scene.gateway.biometricPromptCount)
            compose.onNodeWithText(CANCEL).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { maintenance.status.value == MaintenanceStatus.Idle }
        }

        private fun enterAndContinue(field: String, password: String) {
            ui.awaitTag(field)
            compose.onNodeWithTag(field).performTextInput(password)
            compose.onNodeWithText(CONTINUE).performClick()
        }

        /** `restore_confirm_counts` with the backup's numbers (ui.md § 11). */
        private fun countsOf(backup: Map<String, List<List<String?>>>): String {
            fun count(table: String, one: String, other: String) =
                backup.getValue(table).size.let { n -> "$n ${if (n == 1) one else other}" }
            return "The backup contains ${count("notes", "note", "notes")}, ${count("persons", "person", "people")} " +
                "and ${count("projects", "project", "projects")}."
        }
    }

    private companion object {
        val entry = PasswordEntry(PasswordReason.None, fingerprintUsable = false, failures = 0, retryAt = 0)

        // Literals copied from ui.md § 11.
        const val CORRUPT_TITLE = "The database is damaged"
        const val DATABASE_PASSWORD_TITLE = "Enter your startup password"
        const val WRONG_PASSWORD = "Wrong password."
        const val PASSWORD_STORAGE_FULL =
            "Not enough free storage, so the password was not checked. Free up some space and try again."
        const val CONFIRM_TITLE = "Restore this backup?"
        const val ERASE_FAILED = "Erase failed"
        const val START_FRESH_FAILED = "Start fresh failed"
        const val UNAVAILABLE = "The operation could not run right now. Nothing was changed. Try again."
        const val OK = "OK"
        const val CANCEL = "Cancel"
        const val CONTINUE = "Continue"
        const val UNLOCK_TITLE = "Unlock Doro Organizer"
        const val USE_PASSWORD = "Use password"

        const val RECOVERY_RESTORE = "recovery_restore"
        const val BACKUP_PASSWORD_FIELD = "restore_password_field"
        const val DATABASE_PASSWORD_FIELD = "restore_db_password_field"
        const val DATABASE_PASSWORD = "database password"
        const val BACKUP_PASSWORD = "backup password"
    }
}

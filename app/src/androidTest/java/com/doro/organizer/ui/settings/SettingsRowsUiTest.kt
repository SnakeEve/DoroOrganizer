package com.doro.organizer.ui.settings

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.security.LockAvailability
import com.doro.organizer.security.SlotState
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.assertNotCutOff
import com.doro.organizer.testing.assertTouchTarget
import com.doro.organizer.testing.textLayout
import com.doro.organizer.ui.DoroRoot
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.theme.DoroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings rows (ui.md § 9.3–§ 9.5, DEC-72) from the committed key mode, the settings and the biometric
 * availability, all scripted: the encryption switch and the startup-password rows per mode, the biometric row per
 * availability with its enrollment and slot "Set up" buttons, the Recents hints of API 28–32, Encrypt backups forced
 * on while the database is encrypted, and the no-screen-lock card. The flows behind the rows are
 * `SettingsViewModelTest`, `BackupUiTest`, `RestoreUiTest` and `SettingsLockUiTest`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRowsUiTest : ScriptedGateComposeTest() {
    private val oldRecents = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    @Test
    fun plainMode_encryptionOff_aStartupPasswordToSet_andEncryptBackupsFollowsItsSetting() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PLAIN
        openSettings()

        row(ENCRYPTION).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(ENCRYPTION_OFF).assertExists()
        compose.onNodeWithText(ENCRYPTION_RECENTS, substring = true).assertDoesNotExist()
        row(PASSWORD_SET).assert(hasText(STARTUP_PASSWORD)).assert(hasText(OFF))
        compose.onNodeWithTag(PASSWORD_CHANGE).assertDoesNotExist()
        compose.onNodeWithTag(PASSWORD_REMOVE).assertDoesNotExist()

        row(ENCRYPT_BACKUPS).assertIsOff().assertIsEnabled().assert(hasText(ENCRYPT_BACKUPS_SUMMARY))
        row(ENCRYPT_BACKUPS).performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.settings.settings.value?.encryptBackups == true }
        row(ENCRYPT_BACKUPS).assertIsOn()
        row(SCREENSHOT).assertIsOff().performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { scene.settings.settings.value?.screenshotProtection == true }
    }

    @Test
    fun deviceMode_encryptionOn_andEncryptBackupsForcedOn() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.DEVICE
        openSettings()

        row(ENCRYPTION).assertIsOn().assertIsEnabled()
        compose.onNodeWithText(ENCRYPTION_ON).assertExists()
        // Below API 33 only screenshot protection hides the Recents thumbnail (security.md § 12).
        assertEquals(oldRecents, ui.countText(ENCRYPTION_RECENTS, substring = true) > 0)
        row(PASSWORD_SET).assert(hasText(OFF))
        row(ENCRYPT_BACKUPS).assertIsOn().assertIsNotEnabled().assert(hasText(ENCRYPT_BACKUPS_FORCED))
        assertEquals("the stored value is kept for a later PLAIN", false, scene.settings.settings.value?.encryptBackups)
    }

    @Test
    fun passwordMode_changeAndRemove_withTheLockOn_thePasswordIsAskedWhenTheFingerprintCannotBeUsed() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.settings.setBiometricLock(true)
        openSettings()

        row(PASSWORD_CHANGE).assert(hasText(CHANGE_PASSWORD)).assert(hasText(ASKED_WITH_FINGERPRINT))
        row(PASSWORD_REMOVE).assert(hasText(REMOVE_PASSWORD))
        compose.onNodeWithTag(PASSWORD_SET).assertDoesNotExist()
        compose.onNodeWithText(ENCRYPTION_RECENTS, substring = true).assertDoesNotExist()

        scene.settings.setBiometricLock(false)
        compose.waitUntil(GateUi.TIMEOUT_MS) { ui.countText(ASKED_EVERY_START) > 0 }
        assertEquals(oldRecents, ui.countText(ENCRYPTION_RECENTS, substring = true) > 0)
    }

    @Test
    fun theBiometricRow_isHidden_whileUnusable_andTheLockIsOff() = runBlocking<Unit> {
        scene.lock.lockAvailability = available(BiometricAvailability.Unavailable)
        openSettings()
        row(SCREENSHOT)
        compose.onNodeWithTag(BIOMETRIC).assertDoesNotExist()
    }

    @Test
    fun theBiometricRow_staysShown_whileTheLockIsOn_evenWhenUnusable() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.DEVICE
        scene.settings.setBiometricLock(true)
        scene.lock.lockAvailability = available(BiometricAvailability.Unavailable)
        openSettings()
        row(BIOMETRIC).assertIsOn().assertIsEnabled().assert(hasText(BIOMETRIC_SUMMARY))
        compose.onNodeWithTag(SLOT_SET_UP).assertDoesNotExist()
        assertEquals(oldRecents, ui.countText(BIOMETRIC_RECENTS) > 0)
    }

    @Test
    fun noBiometricEnrolled_theSwitchIsDisabled_andSetUpOpensTheEnrollment() = runBlocking<Unit> {
        scene.lock.lockAvailability = available(BiometricAvailability.NoneEnrolled)
        openSettings()
        row(BIOMETRIC).assertIsOff().assertIsNotEnabled().assert(hasText(NONE_ENROLLED))
        compose.onNodeWithTag(ENROLL).performScrollTo().assertTouchTarget().assertIsEnabled().performClick()
        compose.waitUntil(GateUi.TIMEOUT_MS) { "enroll" in scene.lock.calls }
        assertEquals(listOf("enroll"), scene.lock.calls)
    }

    @Test
    fun passwordMode_withTheLockOn_aMissingSlot_isNamed_withItsOwnSetUp() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.settings.setBiometricLock(true)
        scene.lock.lockAvailability = available(BiometricAvailability.Success, SlotState.Missing)
        openSettings()
        row(BIOMETRIC).assertIsOn().assert(hasText(BIOMETRIC_SUMMARY_PASSWORD)).assert(hasText(SLOT_MISSING))
        compose.onNodeWithTag(SLOT_SET_UP).performScrollTo().assertTouchTarget().performClick()
        ui.awaitText(SLOT_SETUP_TITLE)
    }

    /** DEC-77 as the owner signed it off: a slot that could not be looked up is not called missing. */
    @Test
    fun aSlotThatCannotBeLookedUp_showsNoSlotLine_andOffersNoSetUp() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.settings.setBiometricLock(true)
        scene.lock.lockAvailability = available(BiometricAvailability.Success, SlotState.Unknown)
        openSettings()
        row(BIOMETRIC).assertIsOn().assert(hasText(BIOMETRIC_SUMMARY_PASSWORD))
        compose.onNodeWithText(SLOT_MISSING).assertDoesNotExist()
        compose.onNodeWithTag(SLOT_SET_UP).assertDoesNotExist()
    }

    @Test
    fun aLiveSlot_orTheLockOff_showsNoSlotLine() = runBlocking<Unit> {
        scene.maintenance.keyMode.value = KeyMode.PASSWORD
        scene.lock.lockAvailability = available(BiometricAvailability.Success, SlotState.Missing)
        openSettings()
        row(BIOMETRIC).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(SLOT_MISSING).assertDoesNotExist()

        scene.settings.setBiometricLock(true)
        scene.lock.lockAvailability = available(BiometricAvailability.Success, SlotState.Ready)
        ui.pressBack()
        openSettingsFromTheShell()
        row(BIOMETRIC).assertIsOn()
        compose.onNodeWithText(SLOT_MISSING).assertDoesNotExist()
    }

    @Test
    fun withoutAScreenLock_theCardSaysSo_andNothingIsBlocked() = runBlocking<Unit> {
        scene.lock.lockAvailability = available(BiometricAvailability.Success).copy(deviceSecure = false)
        openSettings()
        // A non-clickable M3 card does not merge its children (Surface: mergeDescendants = false): its text is a child.
        compose.onNodeWithTag(NO_SCREEN_LOCK).assert(hasAnyDescendant(hasText(NO_SCREEN_LOCK_TEXT)))
        compose.onNodeWithTag(NO_SCREEN_LOCK).assertHasNoClickAction()
        row(BIOMETRIC).assertIsEnabled()
        row(ENCRYPTION).assertIsEnabled()
    }

    @Test
    fun withAScreenLock_thereIsNoCard() = runBlocking<Unit> {
        openSettings()
        row(BIOMETRIC)
        compose.onNodeWithTag(NO_SCREEN_LOCK).assertDoesNotExist()
    }

    @Test
    fun theEnrollmentButton_wrapsWithoutTruncation_at200PercentFont() = runBlocking<Unit> {
        scene.lock.lockAvailability = available(BiometricAvailability.NoneEnrolled)
        openSettings()
        // The same root and graph as MainActivity's, at twice the font scale (testing.md I-35).
        scene.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalUiGraph provides app.container) {
                    DoroTheme {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) { DoroRoot() }
                    }
                }
            }
        }
        ui.awaitShell()
        openSettingsFromTheShell()
        val label = compose.onNode(hasText(NONE_ENROLLED), useUnmergedTree = true).performScrollTo().textLayout()
        assertEquals(2f, label.layoutInput.density.fontScale)
        label.assertNotCutOff()
        compose.onNodeWithTag(ENROLL).performScrollTo().assertTouchTarget()
    }

    private fun available(biometric: BiometricAvailability, slot: SlotState = SlotState.NotUsed) =
        LockAvailability(biometric, deviceSecure = true, slot)

    private fun openSettings() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        openSettingsFromTheShell()
    }

    private fun openSettingsFromTheShell() {
        compose.onNodeWithTag(SETTINGS_ACTION).performClick()
        ui.awaitTag(SETTINGS_LIST)
        ui.awaitTag(SCREENSHOT) // the security rows wait for the settings and the key mode
    }

    /** The row with [tag], scrolled into view. */
    private fun row(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    private companion object {
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val ENCRYPTION = "settings_encryption"
        const val PASSWORD_SET = "settings_password_set"
        const val PASSWORD_CHANGE = "settings_password_change"
        const val PASSWORD_REMOVE = "settings_password_remove"
        const val BIOMETRIC = "settings_biometric"
        const val ENROLL = "settings_enroll"
        const val SLOT_SET_UP = "settings_slot_setup"
        const val SCREENSHOT = "settings_screenshot"
        const val ENCRYPT_BACKUPS = "settings_encrypt_backups"
        const val NO_SCREEN_LOCK = "settings_no_screen_lock"

        // Literals copied from ui.md § 11.
        const val ENCRYPTION_OFF = "Off. Notes are stored unencrypted on this device."
        const val ENCRYPTION_ON = "On. Notes are encrypted with a key kept on this device."
        const val ENCRYPTION_RECENTS = "On this Android version the recent apps screen can still show the app's last " +
            "screen, even though the database is encrypted."
        const val STARTUP_PASSWORD = "Startup password"
        const val OFF = "Off"
        const val CHANGE_PASSWORD = "Change startup password"
        const val REMOVE_PASSWORD = "Remove startup password"
        const val ASKED_EVERY_START = "Asked every time the app starts"
        const val ASKED_WITH_FINGERPRINT = "Asked when your fingerprint cannot be used, and every 14 days"
        const val BIOMETRIC_SUMMARY =
            "Ask for your fingerprint or face when the app opens or comes back from the background"
        const val BIOMETRIC_SUMMARY_PASSWORD = "Open the app with your fingerprint instead of the startup password. " +
            "The password is still asked every 14 days and whenever the fingerprint cannot be used."
        const val BIOMETRIC_RECENTS = "On this Android version the recent apps screen can still show the app's last " +
            "screen. Turn on screenshot protection to hide it."
        const val NONE_ENROLLED = "No fingerprint or face is set up for unlocking."
        const val SLOT_MISSING = "Fingerprint unlock is not set up, so the app asks for your password."
        const val SLOT_SETUP_TITLE = "Set up fingerprint unlock?"
        const val ENCRYPT_BACKUPS_SUMMARY = "Protect backup files with a separate password"
        const val ENCRYPT_BACKUPS_FORCED = "Always on while the database is encrypted"
        const val NO_SCREEN_LOCK_TEXT = "This device has no screen lock, so anyone holding it can open its apps. Set " +
            "a screen lock in system settings, or use a startup password."
    }
}

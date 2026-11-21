package com.doro.organizer.ui.gate

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.GateUi.Companion.FINGERPRINT_AVAILABLE
import com.doro.organizer.testing.GateUi.Companion.KEK_FAILED_TEXT
import com.doro.organizer.testing.GateUi.Companion.KEK_LINE
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.textLayout
import com.doro.organizer.testing.themeErrorColor
import com.doro.organizer.ui.DoroRoot
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.theme.DoroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-54 (DEC-86, ui.md § 10.2, § 12.3): the key-store line of the password screen, from a scripted gate state and a
 * scripted `AppLockController`, with every `Announcer` call recorded.
 */
@RunWith(AndroidJUnit4::class)
class KeyStoreLineUiTest : ScriptedGateComposeTest() {
    @Test
    fun needsPassword_showsTheLine() = assertTheLine(needsPassword(), kekNotice = true)

    @Test
    fun needsPassword_showsTheLine_withTheNoticeDismissed() = assertTheLine(needsPassword(), kekNotice = false)

    @Test
    fun lockedReady_showsTheLine() = assertTheLine(locked(Resume.Ready), kekNotice = true)

    @Test
    fun lockedReady_showsTheLine_withTheNoticeDismissed() = assertTheLine(locked(Resume.Ready), kekNotice = false)

    @Test
    fun lockedCorrupt_showsTheLine() = assertTheLine(locked(Resume.Corrupt), kekNotice = true)

    @Test
    fun lockedCorrupt_showsTheLine_withTheNoticeDismissed() = assertTheLine(locked(Resume.Corrupt), kekNotice = false)

    @Test
    fun fingerprintUsable_falseToTrue_showsUseFingerprintBelowUnlock_andAnnouncesItOnce() {
        scene.launch(needsPassword())
        ui.awaitTag(KEK_LINE)
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertDoesNotExist()

        scene.gate.moveTo(needsPassword(fingerprintUsable = true))
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertTrue(ui.boundsOf(GateUi.UNLOCK).bottom <= ui.boundsOf(GateUi.USE_FINGERPRINT).top)
        assertEquals(1, ui.countText(KEK_FAILED_TEXT))
        assertEquals(listOf(KEK_FAILED_TEXT, FINGERPRINT_AVAILABLE), scene.announcements.all)

        scene.recreateActivity()
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals(listOf(KEK_FAILED_TEXT, FINGERPRINT_AVAILABLE), scene.announcements.all)

        scene.gate.moveTo(needsPassword(fingerprintUsable = false))
        compose.waitForIdle()
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertDoesNotExist()
        assertEquals(listOf(KEK_FAILED_TEXT, FINGERPRINT_AVAILABLE), scene.announcements.all)
    }

    @Test
    fun fingerprintUsable_atTheStart_isNotAnnounced() {
        scene.launch(locked(Resume.Ready, fingerprintUsable = true))
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals(1, ui.countText(KEK_FAILED_TEXT))
        assertEquals(listOf(KEK_FAILED_TEXT), scene.announcements.all)

        scene.recreateActivity()
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        assertEquals(listOf(KEK_FAILED_TEXT), scene.announcements.all)
    }

    @Test
    fun duringABackoff_theCountdownAndTheLineBothShow() {
        val retryAt = scene.time.elapsedMillis() + 30_000
        scene.launch(GateState.NeedsPassword(5, retryAt, couldNotOpen = false, reason = PasswordReason.KeyStoreFailed))
        ui.awaitTag(KEK_LINE)
        assertEquals(1, ui.countText(KEK_FAILED_TEXT))
        assertEquals(1, ui.countText(THROTTLED))
    }

    @Test
    fun overAMaintenanceResume_theOperationTakesTheReasonLine() {
        val running = Resume.Maintenance(GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready))
        scene.launch(GateState.Locked(running, password = entry(PasswordReason.KeyStoreFailed)))
        ui.awaitPasswordScreen()
        ui.assertReasonLine(CREATING_BACKUP)
        compose.onNodeWithTag(KEK_LINE).assertDoesNotExist()
        assertEquals(0, ui.countText(KEK_FAILED_TEXT))
    }

    @Test
    fun otherReasons_showNoLine_alsoWithTheNotice() {
        scene.lock.kekNotice.value = true
        scene.launch(needsPassword(PasswordReason.None))
        for (reason in listOf(PasswordReason.None, PasswordReason.Reminder, PasswordReason.FingerprintChanged)) {
            scene.gate.moveTo(needsPassword(reason))
            ui.awaitPasswordScreen()
            compose.onNodeWithTag(KEK_LINE).assertDoesNotExist()
            assertEquals(0, ui.countText(KEK_FAILED_TEXT))
        }
        // "Use password" on the slot route of a process whose KEK failed: reason None, "Use fingerprint" shown.
        scene.gate.moveTo(locked(Resume.Ready, PasswordReason.None, fingerprintUsable = true))
        ui.awaitTag(GateUi.USE_FINGERPRINT)
        compose.onNodeWithTag(KEK_LINE).assertDoesNotExist()
        assertFalse(KEK_FAILED_TEXT in scene.announcements.all)
    }

    @Test
    fun theLine_wrapsWithoutTruncation_at200PercentFont() {
        scene.launch(needsPassword())
        ui.awaitTag(KEK_LINE)
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
        ui.awaitTag(KEK_LINE)
        val layout = compose.onNodeWithTag(KEK_LINE).assertTextEquals(KEK_FAILED_TEXT).textLayout()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertTrue("wraps", layout.lineCount > 1)
        assertFalse("cut off", layout.hasVisualOverflow)
    }

    /** One of the key-store screens of ui.md § 10.2, its rotation, and its single announcement (§ 12.3). */
    private fun assertTheLine(state: GateState, kekNotice: Boolean) {
        scene.lock.kekNotice.value = kekNotice
        scene.launch(state)
        ui.awaitTag(KEK_LINE)
        assertTheLineAlone()
        assertEquals(listOf(KEK_FAILED_TEXT), scene.announcements.all)

        // The line sits between the body and the field, in the error color, its icon decorative.
        val body = compose.onNodeWithText(GateUi.PASSWORD_BODY).fetchSemanticsNode().boundsInRoot
        val line = ui.boundsOf(KEK_LINE)
        val field = ui.boundsOf(GateUi.PASSWORD_FIELD)
        assertTrue("below the body", body.bottom <= line.top)
        assertTrue("above the field", line.bottom <= field.top)
        assertEquals(themeErrorColor(), compose.onNodeWithTag(KEK_LINE).textLayout().layoutInput.style.color)
        val descriptions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
        assertEquals("only the field's visibility toggle is described", listOf(SHOW_PASSWORD), descriptions)
        compose.onNodeWithTag(GateUi.PASSWORD_FIELD)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
            .assert(!hasText(KEK_FAILED_TEXT, substring = true))

        // Nothing is disabled by the line, and "Use fingerprint" waits for the re-check (L-53).
        compose.onNodeWithTag(GateUi.USE_FINGERPRINT).assertDoesNotExist()
        compose.onNodeWithTag(GateUi.FORGOT).assertIsEnabled()
        compose.onNodeWithTag(GateUi.PASSWORD_FIELD).assertIsEnabled().performTextInput("x")
        compose.onNodeWithTag(GateUi.UNLOCK).assertIsEnabled()

        scene.recreateActivity()
        ui.awaitTag(KEK_LINE)
        assertTheLineAlone()
        assertEquals("no announcement after a rotation", listOf(KEK_FAILED_TEXT), scene.announcements.all)
    }

    /** The key-store text exactly once, on its tagged line, and no other reason line. */
    private fun assertTheLineAlone() {
        compose.onNodeWithTag(KEK_LINE).assertTextEquals(KEK_FAILED_TEXT)
        assertEquals(1, ui.countText(KEK_FAILED_TEXT))
        ui.assertReasonLine(null)
        assertEquals(0, ui.countText(GateUi.REMINDER_LINE))
        assertEquals(0, ui.countText(GateUi.FINGERPRINT_CHANGED_LINE))
    }

    private companion object {
        // Literals copied from ui.md § 11.
        const val SHOW_PASSWORD = "Show password"
        const val THROTTLED = "Too many attempts. Try again in 0:30."
        const val CREATING_BACKUP = "Creating backup…"

        fun needsPassword(reason: PasswordReason = PasswordReason.KeyStoreFailed, fingerprintUsable: Boolean = false) =
            GateState.NeedsPassword(0, 0, couldNotOpen = false, reason = reason, fingerprintUsable = fingerprintUsable)

        fun entry(reason: PasswordReason, fingerprintUsable: Boolean = false) =
            PasswordEntry(reason, fingerprintUsable, failures = 0, retryAt = 0)

        fun locked(
            resume: Resume,
            reason: PasswordReason = PasswordReason.KeyStoreFailed,
            fingerprintUsable: Boolean = false,
        ) = GateState.Locked(resume, password = entry(reason, fingerprintUsable))
    }
}

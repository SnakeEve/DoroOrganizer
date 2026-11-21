package com.doro.organizer.testing

import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * A gate test that also looks at the screen (testing.md § 1, Compose layer): [compose] finds the nodes of the
 * `MainActivity` that [scene] launches, through the test tags of ui.md § 12.8 and literals copied from ui.md § 11.
 */
abstract class GateComposeTest(realProcessLifecycle: Boolean = false) : GateTest(realProcessLifecycle) {
    @get:Rule(order = 4)
    val compose = createEmptyComposeRule()

    val ui by lazy { GateUi(compose) }
}

/** What a gate test asserts on the screen; every wait pumps Compose frames until the node is there. */
class GateUi(private val compose: ComposeTestRule) {
    /** The password screen, and no `NavDisplay` behind it (DEC-66). */
    fun awaitPasswordScreen() {
        awaitTag(PASSWORD_FIELD)
        assertNoShell()
    }

    /** The lock screen: neither the password screen nor `NavDisplay` (DEC-65, DEC-66). */
    fun awaitLockScreen() {
        awaitText(LOCKED_BODY)
        compose.onNodeWithTag(PASSWORD_FIELD).assertDoesNotExist()
        assertNoShell()
    }

    /** The shell with its tabs (ui.md § 2), and no password screen. */
    fun awaitShell() {
        awaitTag(TAB_ALL)
        compose.onNodeWithTag(PASSWORD_FIELD).assertDoesNotExist()
    }

    /** No `NavDisplay`: the shell and its navigation items are not composed (architecture.md § Rendering). */
    fun assertNoShell() {
        compose.waitForIdle()
        compose.onNodeWithTag(TAB_ALL).assertDoesNotExist()
    }

    /** A recovery screen with [title] (ui.md § 10.7): neither the lock screen, a password screen nor content. */
    fun awaitRecoveryScreen(title: String) {
        awaitText(title)
        compose.onNodeWithTag(PASSWORD_FIELD).assertDoesNotExist()
        compose.onNodeWithText(LOCKED_BODY).assertDoesNotExist()
        assertNoShell()
    }

    /** The Forgot password screen (ui.md § 10.3) once its availability answer is in, and no password screen. */
    fun awaitForgotScreen() {
        awaitTag(FORGOT_ERASE)
        compose.onNodeWithTag(PASSWORD_FIELD).assertDoesNotExist()
    }

    /** The password screen's reason line is [text], or absent for null (ui.md § 10.2). */
    fun assertReasonLine(text: String?) {
        if (text == null) {
            compose.onNodeWithTag(REASON).assertDoesNotExist()
        } else {
            compose.onNodeWithTag(REASON).assertTextEquals(text)
        }
    }

    /** Types [password] into the password screen's field and taps "Unlock". */
    fun enterPassword(password: String) {
        compose.onNodeWithTag(PASSWORD_FIELD).performTextInput(password)
        compose.onNodeWithTag(UNLOCK).performClick()
    }

    /** A Back press as the system delivers it: a key event through the window to the `OnBackPressedDispatcher`. */
    fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    fun awaitTag(tag: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    fun awaitText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** How many nodes of the unmerged tree show [text], or contain it with [substring]. */
    fun countText(text: String, substring: Boolean = false): Int =
        compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().size

    /** The bounds of the node tagged [tag] in the root's coordinates. */
    fun boundsOf(tag: String, useUnmergedTree: Boolean = false): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree).fetchSemanticsNode().boundsInRoot

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val PASSWORD_FIELD = "gate_password_field"
        const val UNLOCK = "gate_unlock"
        const val TAB_ALL = "tab_All"
        const val REASON = "gate_password_reason"
        const val USE_FINGERPRINT = "gate_use_fingerprint"
        const val USE_PASSWORD = "gate_use_password"
        const val FORGOT = "gate_forgot"
        const val KEK_LINE = "gate_kek_failed_text"
        const val CHECK_FAILED_LINE = "gate_check_failed_text"
        const val SLOT_FAILED_LINE = "gate_slot_failed_text"
        const val FORGOT_RESET = "forgot_reset_fingerprint"
        const val FORGOT_ERASE = "forgot_erase"
        const val MAINTENANCE = "maintenance_overlay"

        // Literals copied from ui.md § 11.12.
        const val REMINDER_LINE = "Your regular password check: enter it so you keep remembering it."
        const val FINGERPRINT_CHANGED_LINE = "The fingerprints on this phone changed, so your password is needed."
        const val KEK_FAILED_TEXT = "This phone's secure storage failed, so your password cannot open the app right " +
            "now. Your fingerprint still can: as soon as fingerprint unlock works again, \"Use fingerprint\" appears " +
            "here. If it does not come back, a backup is the way back: choose \"Forgot password?\", erase all data, " +
            "then restore the backup from Settings."
        const val FINGERPRINT_AVAILABLE = "Fingerprint unlock is available again."
        const val PASSWORD_BODY = "Doro Organizer is protected by a startup password."

        const val KEY_UNAVAILABLE_TITLE = "The database cannot be unlocked"

        /** `gate_locked_body`, ui.md § 11.12. */
        const val LOCKED_BODY = "Doro Organizer is locked."
    }
}

/** The text layout of a `Text` node: its style, its lines and whether it was cut off. */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
    check(action(results)) { "no text layout" }
    return results.single()
}

/** The touch target of a control is at least 48 × 48 dp (testing.md I-35, ui.md § 12.4). */
fun SemanticsNodeInteraction.assertTouchTarget(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
    val bounds = node.touchBoundsInRoot
    assertTrue("touch target ${bounds.width} x ${bounds.height} px < 48 dp", bounds.width >= minimum && bounds.height >= minimum)
    return this
}

/** The `error` color of `DoroTheme` on this phone, in its current light or dark setting (DEC-56). */
fun themeErrorColor(): Color {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val dark = night == Configuration.UI_MODE_NIGHT_YES
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    return scheme.error
}

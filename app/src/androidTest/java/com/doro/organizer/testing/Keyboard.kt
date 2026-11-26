package com.doro.organizer.testing

import android.app.Activity
import android.view.View
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.MainActivity

/**
 * Hides the soft keyboard that focusing [field] showed, the way ui.md § 3.3 does: by the IME action of [field] (a text
 * field that declares one; the default action of `ImeAction.Done` hides the keyboard and keeps the focus). It first
 * waits, advancing the Compose clock, until the insets of the activity that [onActivity] runs a block with show the
 * keyboard, and fails if they never do: the keyboard's show request can arrive after the focus, and an IME action sent
 * before it hides nothing, so the keyboard would come up afterwards and take the Back. Then it waits until the keyboard
 * is gone.
 *
 * A test calls it before a Back meant for the app: while the keyboard is shown, it registers its own back callback
 * after the screen's handlers and at the same priority, so the first Back only hides it (every API level; on API 35+
 * with `enableOnBackInvokedCallback` through `ImeBackAnimationController`).
 */
internal fun ComposeTestRule.closeKeyboard(
    field: SemanticsNodeInteraction,
    onActivity: ((MainActivity) -> Unit) -> Unit,
) {
    waitUntil("the soft keyboard is shown", GateUi.TIMEOUT_MS) { imeVisibleOn(onActivity) }
    field.performImeAction()
    waitUntil("the soft keyboard is hidden", GateUi.TIMEOUT_MS) { !imeVisibleOn(onActivity) }
}

/** Whether the soft keyboard is shown over the activity that [onActivity] runs a block with on the main thread. */
private fun imeVisibleOn(onActivity: ((MainActivity) -> Unit) -> Unit): Boolean {
    var visible = false
    onActivity { visible = it.imeVisible() }
    return visible
}

/** Whether the soft keyboard is shown over this activity's window, as its root window insets say. Main thread. */
private fun Activity.imeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * Waits, advancing the Compose clock, until the IME insets of the root view of the focused dialog window
 * ([focusedDialogRoot]) say the soft keyboard is [shown], or hidden: the dialog's own window, whose insets say
 * whether the keyboard covers it.
 */
internal fun ComposeTestRule.awaitDialogIme(shown: Boolean) {
    val root = focusedDialogRoot()
    val condition = "the soft keyboard is ${if (shown) "shown" else "hidden"} on the dialog"
    waitUntil(condition, GateUi.TIMEOUT_MS) { imeVisibleOn(root) == shown }
}

/**
 * Hides the soft keyboard of the focused dialog window without the field's IME action: the rename dialog's Done
 * confirms the rename (DEC-113), so [closeKeyboard] would rename. It waits until the keyboard is shown on the dialog,
 * hides it through `WindowInsetsControllerCompat` on the window of the dialog's `DialogWindowProvider`, and waits until
 * it is gone; the field keeps its focus.
 */
internal fun ComposeTestRule.hideDialogKeyboard() {
    awaitDialogIme(shown = true)
    val root = focusedDialogRoot()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        WindowCompat.getInsetsController(dialogWindowOf(root), root).hide(WindowInsetsCompat.Type.ime())
    }
    awaitDialogIme(shown = false)
}

/** Whether the soft keyboard is shown over [view]'s window, as its root window insets say. */
private fun imeVisibleOn(view: View): Boolean {
    var visible = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    return visible
}

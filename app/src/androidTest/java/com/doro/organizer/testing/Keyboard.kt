package com.doro.organizer.testing

import android.app.Activity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

package com.doro.organizer.testing

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

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
 * hides it on the window of the dialog's `DialogWindowProvider` ([hideIme]), and waits until it is gone; the field
 * keeps its focus.
 */
internal fun ComposeTestRule.hideDialogKeyboard() {
    awaitDialogIme(shown = true)
    val root = focusedDialogRoot()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { hideIme(dialogWindowOf(root), root) }
    awaitDialogIme(shown = false)
}

/**
 * Hides the soft keyboard of [window], whose decor view is [root], with the IME calls that
 * `WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())` makes (androidx.core 1.19.1,
 * `SoftwareKeyboardControllerCompat.Impl30` and `Impl20`), not through androidx.core: code that the `benchmark` test
 * APK compiles calls only library members the app keeps (DEC-103, BL-11). API 30+: [root]'s insets controller (the
 * window's, since [root] is its decor view) hides the IME, after `InputMethodManager.hideSoftInputFromWindow` when
 * the window does not control the IME insets at that moment; below API 30, or without a controller,
 * `hideSoftInputFromWindow` alone. androidx.core's following `hide(0)` on the window's controller is a no-op and is
 * left out. Main thread.
 */
private fun hideIme(window: Window, root: View) {
    check(root === window.decorView) { "the root view is not the dialog window's decor view" }
    val inputMethods = root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = root.windowInsetsController
        if (controller != null) {
            // The listener is called at once with the insets the window controls now.
            val controlsIme = AtomicBoolean(false)
            val listener = WindowInsetsController.OnControllableInsetsChangedListener { _, types ->
                controlsIme.set((types and WindowInsets.Type.ime()) != 0)
            }
            controller.addOnControllableInsetsChangedListener(listener)
            if (!controlsIme.get()) inputMethods.hideSoftInputFromWindow(root.windowToken, 0)
            controller.removeOnControllableInsetsChangedListener(listener)
            controller.hide(WindowInsets.Type.ime())
            return
        }
    }
    inputMethods.hideSoftInputFromWindow(root.windowToken, 0)
}

/** Whether the soft keyboard is shown over [view]'s window, as its root window insets say. */
private fun imeVisibleOn(view: View): Boolean {
    var visible = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    return visible
}

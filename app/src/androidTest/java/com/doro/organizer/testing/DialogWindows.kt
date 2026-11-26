package com.doro.organizer.testing

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Description
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Asserts the window of the dialog that has focus, the topmost one (security.md § 11, § 9.4): autofill is excluded on
 * its own decor view; the Compose view of its content sits in a `DialogWindowProvider`, the assumption
 * `SecureDialogWindow` builds on; and it requests that overlay windows be hidden exactly when [hidesOverlays] and the
 * device runs API 31+, where the request exists. [name] names the dialog in failure messages.
 */
fun assertFocusedDialogWindow(hidesOverlays: Boolean, name: String = "dialog") {
    onFocusedDialog().check { root, noView ->
        if (root == null) throw noView
        val autofill = root.rootView.importantForAutofill
        assertEquals("$name: autofill", View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, autofill)
        val composeView = checkNotNull(root.findDescendant { it is ViewRootForTest }) { "$name: no Compose view" }
        val provider = composeView.parent as? DialogWindowProvider
        assertNotNull("$name: the Compose view is not in a DialogWindowProvider", provider)
        val expected = hidesOverlays && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val hidden = checkNotNull(provider).window.attributes.hidesOverlays()
        assertEquals("$name: overlay windows hidden", expected, hidden)
    }
}

/**
 * Asserts that the focused field of the dialog that has focus asks for no personalized learning (testing.md L-25,
 * DEC-71): the `EditorInfo` that `onCreateInputConnection` fills on the focused view of that dialog's window, as a
 * keyboard gets it, carries `IME_FLAG_NO_PERSONALIZED_LEARNING`. [name] names the field in failure messages.
 */
fun assertFocusedDialogFieldNeverLearns(name: String) {
    onFocusedDialog().check { root, noView ->
        if (root == null) throw noView
        val info = EditorInfo()
        val connection = root.rootView.findFocus()?.onCreateInputConnection(info)
        assertNotNull("$name: no focused field with an input connection", connection)
        assertTrue("$name learns", info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}

/**
 * Asserts the window of the menu that has focus, a popup (sub-window) of the activity window (V30, security.md § 11):
 * autofill is excluded on its own root view, which neither the activity's decor view nor any dialog window reaches.
 */
fun assertFocusedMenuWindow(name: String) {
    onView(isRoot()).inRoot(withDecorView(hasWindowFocus())).check { root, noView ->
        if (root == null) throw noView
        val params = root.rootView.layoutParams as WindowManager.LayoutParams
        val subWindows = WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
        assertTrue("$name: not a popup window (type ${params.type})", params.type in subWindows)
        val autofill = root.rootView.importantForAutofill
        assertEquals("$name: autofill", View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, autofill)
    }
}

/** The root view of the dialog window that has focus, the topmost dialog, as [assertFocusedDialogWindow] finds it. */
internal fun focusedDialogRoot(): View {
    var found: View? = null
    onFocusedDialog().check { root, noView ->
        if (root == null) throw noView
        found = root.rootView
    }
    return checkNotNull(found)
}

/**
 * The window of the dialog whose root view is [root]: the window of the `DialogWindowProvider` that holds its Compose
 * view, the window `SecureDialogWindow` configures.
 */
internal fun dialogWindowOf(root: View): Window {
    val composeView = checkNotNull(root.findDescendant { it is ViewRootForTest }) { "the dialog has no Compose view" }
    val provider = checkNotNull(composeView.parent as? DialogWindowProvider) { "the Compose view is not in a dialog" }
    return provider.window
}

private fun onFocusedDialog() = onView(isRoot()).inRoot(allOf(isDialog(), withDecorView(hasWindowFocus())))

/**
 * Whether `Window.setHideOverlayWindows(true)` was requested: it sets a private flag with no public getter, which
 * `LayoutParams.toString()` names `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`.
 */
private fun WindowManager.LayoutParams.hidesOverlays(): Boolean = toString().contains("HIDE_NON_SYSTEM_OVERLAY_WINDOWS")

private fun View.findDescendant(predicate: (View) -> Boolean): View? {
    if (predicate(this)) return this
    if (this !is ViewGroup) return null
    for (i in 0 until childCount) getChildAt(i).findDescendant(predicate)?.let { return it }
    return null
}

private fun hasWindowFocus() = object : TypeSafeMatcher<View>() {
    override fun describeTo(description: Description) {
        description.appendText("has window focus")
    }

    override fun matchesSafely(item: View) = item.hasWindowFocus()
}

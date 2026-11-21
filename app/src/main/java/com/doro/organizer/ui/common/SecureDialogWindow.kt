package com.doro.organizer.ui.common

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Keeps autofill out of a dialog, sheet or menu window (security.md § 11, DEC-71, V30: every window). Compose
 * `Dialog`, `AlertDialog`, `ModalBottomSheet` and `DropdownMenu` create their own windows, which the
 * `importantForAutofill` that `MainActivity` sets on its decor view does not reach, so each of them wraps its content
 * in this: inside the window's content lambda (for an `AlertDialog`, its `text` slot), where [LocalView] is that
 * window's own view; around the `AlertDialog` it would reach only the activity's window. Security dialogs pass
 * [hideOverlays], which hides overlay windows over their own window on API 31+ (security.md § 9.4, DEC-66).
 */
@Composable
fun SecureDialogWindow(hideOverlays: Boolean = false, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view, hideOverlays) {
        view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (hideOverlays && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view.parent as? DialogWindowProvider)?.window?.setHideOverlayWindows(true)
        }
        onDispose { }
    }
    content()
}

package com.doro.organizer.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps autofill out of a dialog or sheet window (security.md § 11, DEC-71). Compose `Dialog`, `AlertDialog` and
 * `ModalBottomSheet` create their own windows, which the `importantForAutofill` that `MainActivity` sets on its decor
 * view does not reach, so each of them wraps its content in this. The `hideOverlays` option of security.md § 11 comes
 * with the first security dialog that needs it (SPEC T20).
 */
@Composable
fun SecureDialogWindow(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { }
    }
    content()
}

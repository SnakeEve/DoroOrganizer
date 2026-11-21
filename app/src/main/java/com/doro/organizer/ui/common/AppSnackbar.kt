package com.doro.organizer.ui.common

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.doro.organizer.ui.navigation.LocalIsTopEntry

/** The shell's one snackbar host state, `appSnackbar` (ui.md § 2.4). */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("the shell provides appSnackbar") }

/**
 * The `snackbarHost` of every screen `Scaffold` in the shell: drawn only by the top entry, so a snackbar never shows
 * twice during a pop animation or a predictive-back gesture (ui.md § 2.4).
 */
@Composable
fun AppSnackbarHost() {
    if (LocalIsTopEntry.current) SnackbarHost(LocalAppSnackbar.current)
}

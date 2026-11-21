package com.doro.organizer.testing

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import com.doro.organizer.MainActivity
import com.doro.organizer.ui.theme.DoroTheme

/**
 * Replaces the content of this `MainActivity` with [content] alone in `DoroTheme`, so a test shows one dialog with its
 * real composable and window, and no screen state has to lead to it. Call it on the main thread (`onActivity`).
 */
fun MainActivity.show(content: @Composable () -> Unit) = setContent { DoroTheme { content() } }

/**
 * Shows [dialog] alone (`show`) in a window of its own: the content before it is removed and its dialog window gone
 * first, so Compose cannot hand the previous dialog's window, already configured, to this one.
 */
fun ScriptedGateComposeTest.showDialog(dialog: @Composable () -> Unit) {
    scene.onActivity { it.show {} }
    compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    scene.onActivity { it.show(dialog) }
    compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
}

/** The tab tags (ui.md § 12.8) and their labels (Q10); literals copied from ui.md § 11. */
val TAB_LABELS = listOf(
    "tab_All" to "All",
    "tab_Individual" to "People",
    "tab_Project" to "Projects",
    "tab_Date" to "Calendar",
    "tab_Orphan" to "Orphans",
)

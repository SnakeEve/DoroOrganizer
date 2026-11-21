package com.doro.organizer.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.ui.GateGraph
import com.doro.organizer.ui.navigation.LocalIsTopEntry

/**
 * Shows a screen's [message] in the shell's snackbar while its entry is on top, Long for errors and Short otherwise,
 * then reports it through [onShown]; a message whose screen left first shows when it returns (ui.md § 2.4).
 */
@Composable
fun MessageEffect(message: UserMessage?, graph: GateGraph, onShown: (Long) -> Unit) {
    val snackbar = LocalAppSnackbar.current
    val isTop = LocalIsTopEntry.current
    val resources = LocalResources.current
    LaunchedEffect(message?.id, isTop) {
        if (message == null || !isTop) return@LaunchedEffect
        snackbar.showSnackbar(
            message = resources.resolve(message.text, graph.appTime),
            duration = if (message.isError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        onShown(message.id)
    }
}

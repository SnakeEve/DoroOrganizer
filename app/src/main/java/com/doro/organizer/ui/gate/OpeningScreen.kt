package com.doro.organizer.ui.gate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.doro.organizer.R
import com.doro.organizer.ui.common.DelayedProgress

/** `Starting` after the splash has gone, and every blank moment: the surface without progress or text (ui.md § 10.8). */
@Composable
internal fun BlankScreen() {
    Surface(Modifier.fillMaxSize()) {}
}

/** `Opening` without a password and without an error: blank, then a progress indicator after 500 ms (ui.md § 10.5). */
@Composable
internal fun OpeningScreen() {
    Surface(Modifier.fillMaxSize()) { DelayedProgress(Modifier.fillMaxSize()) }
}

/** `Opening(transientError)` (ui.md § 10.5): nothing was changed, and "Try again" dispatches `Retry` (T20). */
@Composable
internal fun OpenErrorScreen(onRetry: () -> Unit) {
    GateColumn(stringResource(R.string.gate_open_error_title), header = { ErrorIcon() }) {
        GateBody(stringResource(R.string.gate_open_error_body))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_try_again)) }
    }
}

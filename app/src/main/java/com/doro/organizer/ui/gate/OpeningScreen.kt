package com.doro.organizer.ui.gate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.doro.organizer.R
import kotlinx.coroutines.delay

/** How long `Opening` stays blank before its progress indicator appears (ui.md § 10.5). */
private const val PROGRESS_DELAY_MS = 500L

/** `Starting` after the splash has gone, and every blank moment: the surface without progress or text (ui.md § 10.8). */
@Composable
internal fun BlankScreen() {
    Surface(Modifier.fillMaxSize()) {}
}

/** `Opening` without a password and without an error: blank, then a progress indicator after 500 ms (ui.md § 10.5). */
@Composable
internal fun OpeningScreen() {
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DELAY_MS)
        showProgress = true
    }
    Surface(Modifier.fillMaxSize()) {
        if (showProgress) {
            val loading = stringResource(R.string.a11y_loading)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
        }
    }
}

/** `Opening(transientError)` (ui.md § 10.5): nothing was changed, and "Try again" dispatches `Retry` (T20). */
@Composable
internal fun OpenErrorScreen(onRetry: () -> Unit) {
    GateColumn(stringResource(R.string.gate_open_error_title), header = { ErrorIcon() }) {
        GateBody(stringResource(R.string.gate_open_error_body))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_try_again)) }
    }
}

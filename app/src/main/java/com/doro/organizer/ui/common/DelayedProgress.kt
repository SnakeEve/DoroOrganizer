package com.doro.organizer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
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

/** How long a loading area stays blank before its progress indicator appears (ui.md § 3.6, § 10.5). */
private const val PROGRESS_DELAY_MS = 500L

/**
 * A loading area: nothing for 500 ms, then a centered indeterminate progress indicator described as "Loading"
 * (ui.md § 3.6, § 10.5), so a fast load never flashes a spinner.
 */
@Composable
fun DelayedProgress(modifier: Modifier = Modifier) {
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DELAY_MS)
        showProgress = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (showProgress) {
            val loading = stringResource(R.string.a11y_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
        }
    }
}

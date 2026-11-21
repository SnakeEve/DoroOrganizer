package com.doro.organizer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.doro.organizer.core.time.AppTime
import java.util.Locale
import kotlinx.coroutines.delay

private const val SECOND_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60

/**
 * A backoff's remaining time as `m:ss`: minutes without padding, seconds with two digits, rounded up so that `0:00`
 * never shows while the wait still runs (ui.md § 11.14). Never negative.
 */
fun countdownText(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0) + SECOND_MS - 1) / SECOND_MS
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

/**
 * How long a countdown showing [remainingMs] waits before it recomputes: until the shown second changes, which for the
 * last second is the moment the wait ends. A fixed one-second tick would show `0:01` and keep the field disabled for
 * up to a second after `retryAt`. [remainingMs] is positive.
 */
fun nextCountdownTickMs(remainingMs: Long): Long = (remainingMs - 1) % SECOND_MS + 1

/**
 * [retryAt] minus the current `AppTime.elapsedMillis()`, recomputed every second, on the second boundaries of the
 * remaining time, until it reaches zero (ui.md § 10.2). Both values share the monotonic base, so nothing converts
 * between clocks (security.md § 5.3). A new [retryAt] starts from its own value at once.
 */
@Composable
fun rememberRemainingMillis(retryAt: Long, appTime: AppTime): Long {
    var remaining by remember(retryAt, appTime) { mutableLongStateOf(retryAt - appTime.elapsedMillis()) }
    LaunchedEffect(retryAt, appTime) {
        while (remaining > 0) {
            delay(nextCountdownTickMs(remaining))
            remaining = retryAt - appTime.elapsedMillis()
        }
    }
    return remaining
}

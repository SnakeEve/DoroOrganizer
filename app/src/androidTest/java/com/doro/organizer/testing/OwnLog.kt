package com.doro.organizer.testing

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The output lines of [command], run through `UiAutomation` with the shell's rights. No shell parses it, so the
 * command holds no quotes or pipes.
 */
fun shellLines(command: String): List<String> {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readLines() }
}

/**
 * This process's log lines, oldest first, as `logcat -d -v raw` prints them (each message alone), filtered by
 * [filter] when given (for example `-s Doro:D`). The log is read again until a line holds [marker], for at most
 * [limit], because logd may make a line readable only some time after it was written; the caller asserts that the
 * marker is there. The wait runs in real time, also under `runTest`.
 */
suspend fun ownLogThrough(marker: String, filter: String = "", limit: Duration = 10.seconds): List<String> =
    withContext(Dispatchers.Default) {
        var lines = readOwnLog(filter)
        withTimeoutOrNull(limit) {
            while (lines.none { marker in it }) {
                delay(LOG_POLL_MS)
                lines = readOwnLog(filter)
            }
        }
        lines
    }

private fun readOwnLog(filter: String): List<String> =
    shellLines("logcat -d -v raw --pid=${Process.myPid()} $filter".trimEnd())

private const val LOG_POLL_MS = 100L

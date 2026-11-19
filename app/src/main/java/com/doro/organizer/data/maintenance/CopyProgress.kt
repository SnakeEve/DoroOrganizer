package com.doro.organizer.data.maintenance

import java.io.InputStream
import java.io.OutputStream

/**
 * Copies [input] to [output] and reports the copied share of [length] to [onProgress] in whole percents, each at
 * most once and in increasing order, for `MaintenanceStatus.Running.fraction` (db-lifecycle.md § Interface and
 * status). A [length] of 0 or less is unknown: no progress then. [onBlock] receives the bytes copied so far after every
 * block; it may throw to stop the copy (the restore's cancellation and size bounds, db-lifecycle.md § Restore step 4).
 * Returns the number of bytes copied; the caller compares it with [length].
 */
internal fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    length: Long,
    onProgress: (Float) -> Unit,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    onBlock: (copied: Long) -> Unit = {},
): Long {
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    var reported = -1L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return copied
        output.write(buffer, 0, read)
        copied += read
        onBlock(copied)
        if (length > 0) {
            val percent = (copied * 100 / length).coerceAtMost(100)
            if (percent > reported) {
                reported = percent
                onProgress(percent / 100f)
            }
        }
    }
}

package com.doro.organizer.core.io

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.doro.organizer.core.log.AppLog
import java.io.File

/**
 * Best effort: makes the renames and deletions inside [dir] durable (db-lifecycle.md § Generation commit). Never
 * throws; a failure is logged by class and errno only.
 */
fun syncDirectory(dir: File) {
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (e: ErrnoException) {
        AppLog.d("syncDirectory failed: ${e.javaClass.simpleName} errno=${e.errno}")
    } catch (e: Exception) {
        AppLog.d("syncDirectory failed: ${e.javaClass.simpleName}")
    }
}

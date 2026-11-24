package com.doro.organizer.core.io

import android.system.ErrnoException
import android.system.Os
import java.io.FileDescriptor
import java.io.SyncFailedException

/**
 * Syncs the file open on [fd] to the storage and keeps the errno of a failed sync: `Os.fsync` throws an
 * [ErrnoException], which becomes the cause of the [SyncFailedException] thrown here, so DbMaintenance classifies the
 * failure by its errno, `ENOSPC` and `EDQUOT` as storage full (db-lifecycle.md § Generation commit, § Failure
 * classification; DEC-107 (4)). Production code syncs a file only through this function (architecture.md § Boundary
 * test, rule 10): on ART `FileDescriptor.sync()` is native and throws a `SyncFailedException` without a cause, so the
 * errno is lost (`FileDescriptorSyncLosesErrnoTest`). The message is constant; the cause names only the call and the
 * errno, never a path (DEC-70).
 */
fun syncFile(fd: FileDescriptor) {
    try {
        Os.fsync(fd)
    } catch (e: ErrnoException) {
        throw SyncFailedException("fsync failed").apply { initCause(e) }
    }
}

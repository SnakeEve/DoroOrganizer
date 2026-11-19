package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.BackupRejection

// The bounds of a restore's COPY_INPUT (db-lifecycle.md § Restore steps 2 and 4, § Free-space precheck, § Restore
// validation check 1). [hasFreeSpace] is MaintenanceDeps.hasFreeSpace: the bytes needed besides the margin.

/**
 * Step 2: the document's `OpenableColumns.SIZE` as the restore uses it. A value of 0 or less is unknown: providers
 * report it when they do not know the size, and an empty document is rejected by check 2 anyway.
 */
internal fun restoreInputSize(reported: Long?): Long? = reported?.takeIf { it > 0 }

/**
 * After every block: a broken provider can deliver more than it reported, or without end, while Room is live. Null
 * lets the copy go on.
 */
internal fun copyBlockError(copied: Long, size: Long?, hasFreeSpace: (Long) -> Boolean): AppError? = when {
    !hasFreeSpace(2 * copied) -> AppError.StorageFull
    size != null && copied > size -> AppError.InvalidBackup(BackupRejection.Damaged)
    else -> null
}

/**
 * At the end of the stream: a known size must have been copied completely (check 1: a stream that ended early is the
 * provider's failure, not a damaged backup); an unknown one must leave room for the migration journal and the export.
 */
internal fun copyEndError(copied: Long, size: Long?, hasFreeSpace: (Long) -> Boolean): AppError? = when {
    size != null && copied < size -> AppError.FileAccess
    size == null && !hasFreeSpace(2 * copied) -> AppError.StorageFull
    else -> null
}

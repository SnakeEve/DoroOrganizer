package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.BackupRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** db-lifecycle.md § Restore steps 2 and 4: the size a restore trusts and the bounds of its `COPY_INPUT`. */
class RestoreInputBoundsTest {
    /** A filesystem with [free] usable bytes besides the margin. */
    private fun freeSpace(free: Long): (Long) -> Boolean = { needed -> needed <= free }

    @Test
    fun aSizeOf0OrLess_isUnknown() {
        assertNull(restoreInputSize(null))
        assertNull(restoreInputSize(0))
        assertNull(restoreInputSize(-1))
        assertEquals(4096L, restoreInputSize(4096))
    }

    @Test
    fun everyBlock_isStorageFullOnceTwiceTheCopyNoLongerFits_beforeAnyOtherBound() {
        assertNull(copyBlockError(copied = 500, size = 1000, hasFreeSpace = freeSpace(1000)))
        assertEquals(AppError.StorageFull, copyBlockError(copied = 501, size = 1000, hasFreeSpace = freeSpace(1000)))
        assertEquals(AppError.StorageFull, copyBlockError(copied = 2000, size = 1000, hasFreeSpace = freeSpace(1000)))
    }

    @Test
    fun aBlockBeyondTheKnownSize_isDamaged_andWithoutAKnownSizeOnlyFreeSpaceBoundsTheCopy() {
        val plenty = freeSpace(Long.MAX_VALUE)

        assertNull(copyBlockError(copied = 1000, size = 1000, hasFreeSpace = plenty))
        assertEquals(AppError.InvalidBackup(BackupRejection.Damaged), copyBlockError(1001, 1000, plenty))
        assertNull(copyBlockError(copied = 1L shl 40, size = null, hasFreeSpace = plenty))
    }

    @Test
    fun aStreamEndingBeforeTheKnownSize_isFileAccess_andAtTheSizeItIsComplete() {
        assertEquals(AppError.FileAccess, copyEndError(copied = 999, size = 1000, hasFreeSpace = freeSpace(0)))
        assertNull(copyEndError(copied = 1000, size = 1000, hasFreeSpace = freeSpace(0))) // the rolling bound covered it
    }

    @Test
    fun anUnknownSize_isCheckedOnceAtTheEnd_forTwiceTheCopy() {
        assertNull(copyEndError(copied = 500, size = null, hasFreeSpace = freeSpace(1000)))
        assertEquals(AppError.StorageFull, copyEndError(copied = 501, size = null, hasFreeSpace = freeSpace(1000)))
    }
}

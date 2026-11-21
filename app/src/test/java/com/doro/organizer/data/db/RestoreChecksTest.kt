package com.doro.organizer.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** data-layer.md § Restore checks: the R-checks are chosen by the backup's own schema version. */
class RestoreChecksTest {
    /**
     * Version 1 runs R-1 to R-6 (DEC-84, DEC-106): R-6 compares the stored preview with [PREVIEW_EXPR], the constant
     * the write path uses, and R-4 checks the preview's type. The behaviour of both is tested on SQLCipher (L-11).
     */
    @Test
    fun version1_checksR1ToR6_withTheStoredPreview() {
        val checks = RestoreChecks.forVersion(1)

        assertEquals(6, checks.size)
        assertEquals("SELECT COUNT(*) FROM notes WHERE preview IS NOT $PREVIEW_EXPR", checks.last())
        assertTrue(checks[3], checks[3].contains("typeof(preview) <> 'text'"))
    }

    @Test
    fun aVersionWithoutItsOwnList_usesTheNewestListBelowIt() {
        assertSame(RestoreChecks.forVersion(1), RestoreChecks.forVersion(AppDatabase.VERSION + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVersionBelowOne_hasNoChecks() {
        RestoreChecks.forVersion(0)
    }
}

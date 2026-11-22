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

    /**
     * Version 1's R-6 keeps this meaning for good (data-layer.md § Restore checks). R-6 interpolates [PREVIEW_EXPR],
     * which interpolates `WHITESPACE_CHARS`, so a change to either fails here until the version-1 list holds a literal
     * copy of this text and the new version gets its own R-6. Whitespace runs are compared as one space.
     */
    @Test
    fun version1_R6_isPinnedToItsLiteralText() {
        val r6 = RestoreChecks.forVersion(1).last()

        assertEquals(VERSION_1_R6, r6.replace(Regex("\\s+"), " "))
    }

    @Test
    fun aVersionWithoutItsOwnList_usesTheNewestListBelowIt() {
        assertSame(RestoreChecks.forVersion(1), RestoreChecks.forVersion(AppDatabase.VERSION + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVersionBelowOne_hasNoChecks() {
        RestoreChecks.forVersion(0)
    }

    private companion object {
        /** R-6 of version 1 as data-layer.md § Restore checks spells it out. */
        const val VERSION_1_R6 = "SELECT COUNT(*) FROM notes WHERE preview IS NOT substr(ltrim(content, char(9, 10, 11, " +
            "12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, " +
            "8232, 8233, 8239, 8287, 12288)), 1, 300)"
    }
}

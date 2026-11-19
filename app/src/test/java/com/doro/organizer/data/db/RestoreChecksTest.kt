package com.doro.organizer.data.db

import org.junit.Assert.assertSame
import org.junit.Test

/** data-layer.md § Restore checks: the R-checks are chosen by the backup's own schema version. */
class RestoreChecksTest {
    @Test
    fun aVersionWithoutItsOwnList_usesTheNewestListBelowIt() {
        assertSame(RestoreChecks.forVersion(1), RestoreChecks.forVersion(AppDatabase.VERSION + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVersionBelowOne_hasNoChecks() {
        RestoreChecks.forVersion(0)
    }
}

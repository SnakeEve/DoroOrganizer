package com.doro.organizer.model

import com.doro.organizer.testing.FakeAppTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** testing.md L-08, the name: `doro-organizer-backup-YYYY-MM-DD.db` with the local date (DEC-83). */
class BackupFileNameTest {
    @Test
    fun theNameCarriesTheLocalDate() {
        val time = FakeAppTime(Instant.parse("2026-11-17T11:00:00Z"), ZoneId.of("Pacific/Kiritimati")) // UTC+14
        assertEquals("doro-organizer-backup-2026-11-18.db", backupFileName(time.today()))
    }
}

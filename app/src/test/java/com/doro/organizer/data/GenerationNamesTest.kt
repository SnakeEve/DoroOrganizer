package com.doro.organizer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** db-lifecycle.md § Files on disk and § Generation numbers: which names are generation files, and the next number. */
class GenerationNamesTest {
    @Test
    fun generationFiles_areTheMainFileAndItsSidecars() {
        assertEquals(7L, generationOf("doro-7.db"))
        assertEquals(7L, generationOf("doro-7.db-wal"))
        assertEquals(7L, generationOf("doro-7.db-shm"))
        assertEquals(7L, generationOf("doro-7.db-journal"))
        assertEquals(12L, generationOf("doro-12.db-mj1A2b3C"))
    }

    @Test
    fun everyOtherName_isNoGenerationFile() {
        listOf(
            "doro.db", "doro-.db", "doro-7.db-", "doro-7.db-mj", "doro-7.db-mjxyz", "doro-7.db.bak", "xdoro-7.db",
            "doro-7.dbx", "doro--7.db", "doro-7.DB", "backup.db", "doro-99999999999999999999.db",
        ).forEach { assertNull(it, generationOf(it)) }
    }

    @Test
    fun theNextGeneration_isAboveTheCommittedOne_andEveryNumberOnDisk() {
        assertEquals(1L, nextGeneration(committed = 0, inUse = emptySet()))
        assertEquals(4L, nextGeneration(committed = 3, inUse = setOf(3)))
        assertEquals(10L, nextGeneration(committed = 3, inUse = setOf(3, 9))) // a stale sidecar or staged file
        assertEquals(6L, nextGeneration(committed = 5, inUse = setOf(2)))
    }
}

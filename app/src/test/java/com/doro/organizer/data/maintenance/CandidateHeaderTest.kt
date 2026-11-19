package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.BackupRejection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks 2–4 of db-lifecycle.md § Restore validation: what the length and the header allow before anything opens. */
class CandidateHeaderTest {
    @Test
    fun shorterThanTheSmallestPage_isNotABackup_whateverItsHeader() {
        assertEquals(rejected(BackupRejection.NotABackup), candidateHeader(0, ByteArray(0)))
        assertEquals(rejected(BackupRejection.NotABackup), candidateHeader(511, plainHeader(512)))
    }

    @Test
    fun aPlaintextHeader_needsAPowerOfTwoPageSize_thatDividesTheLength() {
        assertEquals(CandidateHeader.Plaintext, candidateHeader(4096 * 3L, plainHeader(4096)))
        assertEquals(CandidateHeader.Plaintext, candidateHeader(512, plainHeader(512)))
        assertEquals(CandidateHeader.Plaintext, candidateHeader(65_536 * 2L, plainHeader(1))) // 1 means 65536
        assertEquals(rejected(BackupRejection.Damaged), candidateHeader(4096 * 3L, plainHeader(3000)))
        assertEquals(rejected(BackupRejection.Damaged), candidateHeader(4096 * 3L, plainHeader(256)))
        assertEquals(rejected(BackupRejection.Damaged), candidateHeader(4096 * 3L, plainHeader(0)))
        assertEquals(rejected(BackupRejection.Damaged), candidateHeader(4096 * 3L + 1, plainHeader(4096)))
    }

    @Test
    fun withoutAPlaintextHeader_onlyWholeSqlcipherPagesAskForAPassword() {
        val random = ByteArray(CANDIDATE_HEADER_BYTES) { (it * 37 + 11).toByte() }

        assertEquals(CandidateHeader.Encrypted, candidateHeader(4096, random))
        assertEquals(CandidateHeader.Encrypted, candidateHeader(4096 * 7L, random))
        assertEquals(rejected(BackupRejection.NotABackup), candidateHeader(4096 * 7L + 512, random))
        assertEquals(rejected(BackupRejection.NotABackup), candidateHeader(2048, random))
    }

    private fun rejected(reason: BackupRejection) = CandidateHeader.Rejected(reason)

    /** `SQLite format 3\0` followed by [pageSize] as a big-endian u16. */
    private fun plainHeader(pageSize: Int): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf((pageSize shr 8).toByte(), pageSize.toByte())
}

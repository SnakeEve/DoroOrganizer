package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.BackupRejection

/** What the length and the first bytes of a restore candidate allow (db-lifecycle.md § Restore validation, checks 2–4). */
internal sealed interface CandidateHeader {
    /** `SQLite format 3\0` with a valid page size: opened with the empty key. */
    data object Plaintext : CandidateHeader

    /** No plaintext header and a SQLCipher 4 length: opened with the backup password. */
    data object Encrypted : CandidateHeader

    data class Rejected(val reason: BackupRejection) : CandidateHeader {
        override fun toString() = "Rejected(reason=$reason)"
    }
}

/** The bytes [candidateHeader] reads: the magic and the page size at offset 16. */
internal const val CANDIDATE_HEADER_BYTES = 18

private val PLAINTEXT_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
private const val MIN_PAGE_SIZE = 512
private const val MAX_PAGE_SIZE = 65_536
private const val CIPHER_PAGE_SIZE = 4096

/**
 * Checks 2–4 on a candidate of [length] bytes whose file starts with [header] (at most [CANDIDATE_HEADER_BYTES]
 * bytes; fewer when the file is shorter). Nothing is opened before these pass, and a file without a plaintext header
 * that cannot be a SQLCipher 4 file is rejected without asking for a password.
 */
internal fun candidateHeader(length: Long, header: ByteArray): CandidateHeader {
    if (length < MIN_PAGE_SIZE) return CandidateHeader.Rejected(BackupRejection.NotABackup) // check 2
    val plaintext = header.size >= CANDIDATE_HEADER_BYTES &&
        header.copyOf(PLAINTEXT_MAGIC.size).contentEquals(PLAINTEXT_MAGIC)
    if (plaintext) {
        // Check 3: big-endian u16 at offset 16, where 1 means 65536.
        val stored = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
        val pageSize = if (stored == 1) MAX_PAGE_SIZE else stored
        val valid = pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE && pageSize and (pageSize - 1) == 0 &&
            length % pageSize == 0L
        return if (valid) CandidateHeader.Plaintext else CandidateHeader.Rejected(BackupRejection.Damaged)
    }
    // Check 4: SQLCipher 4 writes whole 4096-byte pages (security.md § 3.4).
    val cipherShaped = length >= CIPHER_PAGE_SIZE && length % CIPHER_PAGE_SIZE == 0L
    return if (cipherShaped) CandidateHeader.Encrypted else CandidateHeader.Rejected(BackupRejection.NotABackup)
}

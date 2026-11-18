package com.doro.organizer.testing

/** The key of a test database in each of SQLCipher's three keying forms (DEC-60, DEC-83). */
sealed interface TestKey {
    /** The key bytes SQLCipher receives, a fresh array per call. */
    fun bytes(): ByteArray

    /** Plain mode: the empty key (DEC-08). */
    data object Plain : TestKey {
        override fun bytes() = ByteArray(0)
    }

    /**
     * A 32-byte DEK in SQLCipher's raw-key form `x'<64 hex>'`, as Device and Password modes use it (DEC-60). Must
     * equal production's `sqlcipherRawKey` (security.md § 3.4); delegate to it once it exists.
     */
    class Raw(private val dek: ByteArray) : TestKey {
        init {
            require(dek.size == 32) { "a DEK has 32 bytes" }
        }

        override fun bytes(): ByteArray =
            dek.joinToString("", prefix = "x'", postfix = "'") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
    }

    /**
     * A passphrase, as backup files use it (DEC-83). Plain UTF-8 equals production's `passwordBytes` (security.md
     * § 6.3) only for NFC text; delegate to it once it exists.
     */
    class Passphrase(private val password: String) : TestKey {
        override fun bytes(): ByteArray = password.toByteArray(Charsets.UTF_8)
    }
}

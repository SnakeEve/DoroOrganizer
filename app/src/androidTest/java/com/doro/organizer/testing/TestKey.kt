package com.doro.organizer.testing

import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.security.sqlcipherRawKey

/** The key of a test database in each of SQLCipher's three keying forms (DEC-60, DEC-83). */
sealed interface TestKey {
    /** The key bytes SQLCipher receives, a fresh array per call. */
    fun bytes(): ByteArray

    /** Plain mode: the empty key (DEC-08). */
    data object Plain : TestKey {
        override fun bytes() = ByteArray(0)
    }

    /** A 32-byte DEK in SQLCipher's raw-key form `x'<64 hex>'`, encoded by production's `sqlcipherRawKey` (DEC-60). */
    class Raw(private val dek: ByteArray) : TestKey {
        init {
            require(dek.size == 32) { "a DEK has 32 bytes" }
        }

        override fun bytes(): ByteArray = sqlcipherRawKey(dek, ::ByteArray)
    }

    /** A passphrase, as backup files use it (DEC-83): production's `passwordBytes` (UTF-8 of NFC, security.md § 6.3). */
    class Passphrase(private val password: String) : TestKey {
        override fun bytes(): ByteArray = checkNotNull(passwordBytes(password.toCharArray())) { "malformed passphrase" }
    }
}

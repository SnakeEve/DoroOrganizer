package com.doro.organizer.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md L-04: the raw key is the 67 ASCII bytes `x'<64 hex>'` (security.md § 3.4); `dekFromRawKey` is its
 * inverse for the biometric wrap (§ 3.7.2).
 */
class RawKeyEncodingTest {
    @Test
    fun encodesEveryByteAsTwoLowercaseHexDigits() {
        val dek = ByteArray(32) { (it * 8 + 7).toByte() } // 0x07, 0x0f, … 0xff

        val raw = sqlcipherRawKey(dek) { ByteArray(it) }

        val expected = "x'" + dek.joinToString("") { "%02x".format(it) } + "'"
        assertEquals(67, raw.size)
        assertEquals(expected, String(raw, Charsets.US_ASCII))
        assertEquals("x'070f171f", String(raw, 0, 10, Charsets.US_ASCII))
        assertEquals("ff'", String(raw, 64, 3, Charsets.US_ASCII))
    }

    @Test
    fun allocatesThroughTheSeam_andLeavesTheDekAlone() {
        val dek = ByteArray(32) { 0x5A }
        val handedOut = mutableListOf<ByteArray>()

        val raw = sqlcipherRawKey(dek) { size -> ByteArray(size).also { handedOut += it } }

        assertSame(handedOut.single(), raw)
        assertEquals(List(32) { 0x5A.toByte() }, dek.toList())
    }

    @Test
    fun rejectsAKeyThatIsNot32Bytes() {
        assertThrows(IllegalArgumentException::class.java) { sqlcipherRawKey(ByteArray(31)) { ByteArray(it) } }
    }

    @Test
    fun dekFromRawKey_invertsTheEncoding_forEveryByteValue() {
        val handedOut = mutableListOf<ByteArray>()
        for (first in 0 until 256 step 32) {
            val dek = ByteArray(32) { (first + it).toByte() }

            val back = dekFromRawKey(sqlcipherRawKey(dek) { ByteArray(it) }) { size -> ByteArray(size).also { handedOut += it } }

            assertArrayEquals(dek, back)
            assertSame(handedOut.last(), back)
        }
    }

    @Test
    fun dekFromRawKey_rejectsAnythingButTheRawKeyForm() {
        val raw = sqlcipherRawKey(ByteArray(32) { 0x7F }) { ByteArray(it) }
        val malformed = listOf(
            raw.copyOf(66),
            raw.copyOf().also { it[0] = 'X'.code.toByte() },
            raw.copyOf().also { it[66] = '"'.code.toByte() },
            raw.copyOf().also { it[2] = 'A'.code.toByte() }, // uppercase hex is not what sqlcipherRawKey writes
            raw.copyOf().also { it[65] = 'g'.code.toByte() },
        )

        malformed.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { dekFromRawKey(bad) { ByteArray(it) } } }
    }

    @Test
    fun dekFromRawKey_zeroesThePartialDek_whenADigitIsInvalid() {
        val raw = sqlcipherRawKey(ByteArray(32) { 0x5A }) { ByteArray(it) }.also { it[65] = 'z'.code.toByte() }
        val handedOut = mutableListOf<ByteArray>()

        assertThrows(IllegalArgumentException::class.java) {
            dekFromRawKey(raw) { size -> ByteArray(size).also { handedOut += it } }
        }

        assertTrue(handedOut.single().all { it == 0.toByte() })
    }
}

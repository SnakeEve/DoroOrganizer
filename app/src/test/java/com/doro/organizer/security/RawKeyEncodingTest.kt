package com.doro.organizer.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** testing.md L-04: the raw key is the 67 ASCII bytes `x'<64 hex>'` (security.md § 3.4). */
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
}

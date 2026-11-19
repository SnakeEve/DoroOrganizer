package com.doro.organizer.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * testing.md L-27 (security.md § 3.3). It lives in `src/sharedTest`, so it runs on the JVM and on the device: the two
 * runs cover both `Mac` providers.
 */
class Pbkdf2KatTest {
    @Test
    fun rfc7914Vector1_firstBlock() {
        val dk = pbkdf2Sha256(ascii("passwd"), ascii("salt"), 1)

        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(dk))
    }

    @Test
    fun rfc7914Vector2_firstBlock_andThePlatformAgrees() {
        val dk = pbkdf2Sha256(ascii("Password"), ascii("NaCl"), 80_000)
        val spec = PBEKeySpec("Password".toCharArray(), ascii("NaCl"), 80_000, 256)

        assertEquals("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56", hex(dk))
        assertArrayEquals(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, dk)
    }

    @Test
    fun equalsThePlatformPbkdf2_forAnAsciiPassword_atTheIterationFloor() {
        val password = "correct horse 42"
        val salt = ByteArray(16) { (it * 31).toByte() }
        val spec = PBEKeySpec(password.toCharArray(), salt, MIN_ITERATIONS, 256)

        val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded

        assertArrayEquals(platform, pbkdf2Sha256(ascii(password), salt, MIN_ITERATIONS))
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

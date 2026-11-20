package com.doro.organizer.security

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The biometric key aliases of DEC-98: a fresh alias per arming, the only form a `bioSlot` may name. */
class KeyAliasesTest {
    /** A source that hands out 0x00, 0x01, … so the alias is known in advance. */
    private class CountingRandom : SecureRandom() {
        private var next = 0

        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (next++).toByte()
        }
    }

    @Test
    fun aFreshAlias_isThePrefixAnd32LowercaseHexDigitsOfItsRandomBytes() {
        val random = CountingRandom()
        val aliases = List(16) { newBioKeyAlias(random) } // every byte value 0x00..0xff once

        assertEquals("doro_bio_v1_000102030405060708090a0b0c0d0e0f", aliases.first())
        assertEquals("doro_bio_v1_f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", aliases.last())
        aliases.forEach { alias ->
            assertTrue(alias, isSlotKeyAlias(alias))
            assertTrue(alias, isBioKeyAlias(alias))
        }
    }

    @Test
    fun theConstantOfEarlierBuilds_isABiometricAliasToSweep_butNoSlotAlias() {
        assertTrue(isBioKeyAlias("doro_bio_v1"))
        assertFalse(isSlotKeyAlias("doro_bio_v1"))
    }

    @Test
    fun theKek_isNeitherABiometricNorASlotAlias() {
        assertFalse(isBioKeyAlias(KEK_ALIAS))
        assertFalse(isSlotKeyAlias(KEK_ALIAS))
    }
}

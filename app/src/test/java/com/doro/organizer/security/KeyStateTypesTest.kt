package com.doro.organizer.security

import com.doro.organizer.model.KeyMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sameAs` of security.md § 3.6 and db-lifecycle.md § Generation commit: byte content, never identity. */
class KeyStateTypesTest {
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it + seed).toByte() }

    private fun passwordSlot(
        outerIv: ByteArray = bytes(12, 1),
        wrappedDek: ByteArray = bytes(64, 2),
        innerIv: ByteArray? = bytes(12, 3),
        kdf: Kdf? = Kdf(600_000, bytes(16, 4)),
    ) = KeySlot(KeyMode.PASSWORD, outerIv, wrappedDek, innerIv, kdf)

    @Test
    fun keySlot_equalContentInOtherArrays_isTheSame() {
        assertTrue(passwordSlot().sameAs(passwordSlot()))
        assertTrue(KeySlot(KeyMode.PLAIN, null, null, null, null).sameAs(KeySlot(KeyMode.PLAIN, null, null, null, null)))
    }

    @Test
    fun keySlot_anyDifferingField_isNotTheSame() {
        val base = passwordSlot()
        val others = listOf(
            KeySlot(KeyMode.DEVICE, base.outerIv, base.wrappedDek, base.innerIv, base.kdf),
            passwordSlot(outerIv = bytes(12, 9)),
            passwordSlot(wrappedDek = bytes(64, 9)),
            passwordSlot(innerIv = bytes(12, 9)),
            passwordSlot(kdf = Kdf(610_000, bytes(16, 4))),
            passwordSlot(kdf = Kdf(600_000, bytes(16, 9))),
        )

        others.forEachIndexed { i, other -> assertFalse("variant $i", base.sameAs(other)) }
    }

    @Test
    fun keySlot_absentEqualsOnlyAbsent() {
        assertFalse(passwordSlot().sameAs(passwordSlot(innerIv = null)))
        assertFalse(passwordSlot(innerIv = null).sameAs(passwordSlot()))
        assertFalse(passwordSlot().sameAs(passwordSlot(kdf = null)))
        assertTrue(passwordSlot(kdf = null).sameAs(passwordSlot(kdf = null)))
    }

    @Test
    fun bioSlot_comparesBothArrays() {
        val slot = BioSlot(bytes(12, 1), bytes(48, 2))

        assertTrue(slot.sameAs(BioSlot(bytes(12, 1), bytes(48, 2))))
        assertFalse(slot.sameAs(BioSlot(bytes(12, 9), bytes(48, 2))))
        assertFalse(slot.sameAs(BioSlot(bytes(12, 1), bytes(48, 9))))
    }
}

package com.doro.organizer.security

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-30, `BiometricKeySpecTest` on the device: the production spec of the biometric key (security.md
 * § 3.7.1, DEC-60), built without generating a key, so no enrolled biometric is needed. The API 28–29 branch is the
 * JVM half of this test.
 */
@RunWith(AndroidJUnit4::class)
class BiometricKeySpecTest {
    private val alias = newBioKeyAlias(SecureRandom())
    private val spec = bioKeySpec(alias)

    @Test
    fun aes256Gcm_noPadding_forEncryptAndDecrypt_underTheSlotsOwnAlias() {
        assertEquals(alias, spec.keystoreAlias) // each slot has its own key (DEC-98)
        assertTrue(isSlotKeyAlias(spec.keystoreAlias))
        assertNotEquals(KEK_ALIAS, spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
        assertEquals(256, spec.keySize)
        assertTrue(spec.isRandomizedEncryptionRequired) // the Keystore chooses every IV
    }

    @Test
    fun everyUseNeedsAStrongBiometric_andEnrollmentInvalidatesTheKey() {
        assertTrue(spec.isUserAuthenticationRequired)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertFalse(spec.isUserAuthenticationValidWhileOnBody)
        assertFalse(spec.isUnlockedDeviceRequired)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun fromApi30_perUseWithBiometricStrongOnly() {
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
    }

    @Test
    fun teeBacked_neverStrongBox() {
        assertFalse(spec.isStrongBoxBacked)
    }
}

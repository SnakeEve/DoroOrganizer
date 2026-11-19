package com.doro.organizer.security

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * testing.md L-30, the JVM half of `BiometricKeySpecTest`: the choice of the biometric key's authentication
 * parameters by API level (security.md § 3.7.1). No API 28–29 device runs (DEC-15), so that branch is pinned here.
 */
class BiometricKeySpecTest {
    @Test
    fun api28And29_validityMinusOne_theLegacyPerOperationBiometricOnlyCall() {
        assertEquals(BioKeyAuth(validitySeconds = -1, type = null), bioKeyAuth(28))
        assertEquals(BioKeyAuth(validitySeconds = -1, type = null), bioKeyAuth(29))
    }

    @Test
    fun api30AndLater_zeroSecondsWithBiometricStrongOnly() {
        listOf(30, 31, 33, 35, 37).forEach { sdk ->
            assertEquals("API $sdk", BioKeyAuth(validitySeconds = 0, type = KeyProperties.AUTH_BIOMETRIC_STRONG), bioKeyAuth(sdk))
        }
        assertEquals(2, KeyProperties.AUTH_BIOMETRIC_STRONG) // the platform constant, never the device credential (1)
    }
}

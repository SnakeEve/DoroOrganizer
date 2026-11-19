package com.doro.organizer.security

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** L-19 `PromptInfoTest` (API 30+): the strong prompt and the device-credential prompt of security.md § 7.2 build. */
@RunWith(AndroidJUnit4::class)
class PromptInfoTest {
    @Test
    fun strongPrompt_buildsWithItsNegativeButton() {
        val info = DefaultBiometricGateway.promptInfo(PromptRequest("Unlock", credentialOnly = false, negativeText = "Cancel"))
        assertEquals(Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
        assertEquals("Cancel", info.negativeButtonText)
    }

    @Test
    fun credentialPrompt_buildsWithoutANegativeButton() {
        assumeTrue("DEVICE_CREDENTIAL alone needs API 30", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val info = DefaultBiometricGateway.promptInfo(PromptRequest("Unlock", credentialOnly = true, negativeText = null))
        assertEquals(Authenticators.DEVICE_CREDENTIAL, info.allowedAuthenticators)
    }

    @Test
    fun enrollIntent_carriesTheStrongAuthenticators_onApi30Plus() {
        val intent = DefaultBiometricGateway.enrollIntent(Build.VERSION_CODES.R)
        assertEquals(android.provider.Settings.ACTION_BIOMETRIC_ENROLL, intent.action)
        assertEquals(Authenticators.BIOMETRIC_STRONG, intent.getIntExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, 0))
        @Suppress("DEPRECATION")
        assertEquals(android.provider.Settings.ACTION_FINGERPRINT_ENROLL, DefaultBiometricGateway.enrollIntent(Build.VERSION_CODES.Q).action)
    }
}

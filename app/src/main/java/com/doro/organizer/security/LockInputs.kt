package com.doro.organizer.security

import androidx.biometric.BiometricPrompt
import com.doro.organizer.model.KeyMode

/**
 * The live inputs of `UnlockPolicy` (security.md § 5.1). [mode] is null while the key-state is missing or invalid;
 * [reminderDue] is read only when the policy consults it (`PASSWORD`, lock on, usable, slot `Live`).
 */
internal data class LockInputs(
    val mode: KeyMode?,
    val lockOn: Boolean,
    val deviceSecure: Boolean,
    val biometric: BiometricAvailability,
    val bio: BioSlotStatus,
    val reminderDue: Boolean,
) {
    /** A strong biometric can prompt: `Success` or `StatusUnknown` on a device with a secure lock screen. */
    val usable: Boolean
        get() = deviceSecure &&
            (biometric == BiometricAvailability.Success || biometric == BiometricAvailability.StatusUnknown)

    /** The fingerprint route of `PASSWORD` mode is open, whatever the reminder says (security.md § 7.3). */
    val liveFingerprint: Boolean get() = fingerprintRoute && bio == BioSlotStatus.Live

    /** As [liveFingerprint], but the slot's key could not be looked up (DEC-77): it may still be good. */
    val unknownFingerprint: Boolean get() = fingerprintRoute && bio == BioSlotStatus.Unknown

    private val fingerprintRoute: Boolean get() = mode == KeyMode.PASSWORD && lockOn && usable
}

/** The `BiometricPrompt` error codes as security.md § 7.4 groups them. */
internal object PromptCodes {
    /** 5, 10, 13, 3: a cancel, never an error text. */
    val CANCEL = setOf(
        BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_TIMEOUT,
    )

    /** 1, 9, 11, 12, 15: no strong biometric can be used now; the fallback starts at once. */
    val NO_BIOMETRIC = setOf(
        BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
    )

    /** 13: "Use password" on a slot prompt. */
    const val USE_PASSWORD = BiometricPrompt.ERROR_NEGATIVE_BUTTON

    /** 14 (credential prompt only): no screen lock exists, so the lock turns itself off (§ 7.6). */
    const val NO_DEVICE_CREDENTIAL = BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL

    /** A prompt that could not start because another one is shown: handled like a system cancel. */
    const val BUSY = BiometricPrompt.ERROR_CANCELED
}

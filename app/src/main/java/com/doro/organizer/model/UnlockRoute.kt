package com.doro.organizer.model

/**
 * The one-line reason of the password screen (security.md § 5.1; texts: ui.md § 10.2). `KeyStoreFailed`: the
 * password's KEK failed while a live biometric slot exists but no strong biometric is usable (DEC-86).
 */
enum class PasswordReason { None, Reminder, FingerprintChanged, KeyStoreFailed }

/** The public unlock route (security.md § 5.1) that the gate states and events carry (architecture.md). */
sealed interface UnlockRoute {
    /** A strong prompt without a `CryptoObject`; the device credential is its fallback (Q7). */
    data object Biometric : UnlockRoute

    /** `PASSWORD` mode: the slot prompt with a DECRYPT `CryptoObject` (security.md § 3.7.3). */
    data object Slot : UnlockRoute

    /** The startup password; [fingerprintUsable] offers "Use fingerprint" back to the slot prompt. */
    data class Password(val reason: PasswordReason, val fingerprintUsable: Boolean) : UnlockRoute {
        override fun toString() = "Password(reason=$reason, fingerprintUsable=$fingerprintUsable)"
    }
}

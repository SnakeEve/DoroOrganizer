package com.doro.organizer.security

import android.content.Intent
import androidx.fragment.app.FragmentActivity

/**
 * The platform adapter beneath `AppLockController` (security.md § 7.3): the only code that touches
 * `BiometricManager`, `BiometricPrompt`, `KeyguardManager` and the system intents. Production
 * [DefaultBiometricGateway]; tests bind a fake, so the real re-lock rules run above it.
 */
interface BiometricGateway {
    /** `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`, mapped as in security.md § 7.1. */
    fun canAuthenticate(): BiometricAvailability

    fun isDeviceSecure(): Boolean

    /** One `BiometricPrompt` per activity instance; only the newest receives callbacks. */
    fun attach(activity: FragmentActivity)

    /**
     * A prompt that dies with a finishing [activity] (not a configuration change) is answered at once with
     * `ERROR_CANCELED`, because the platform does not promise a callback for it.
     */
    fun detach(activity: FragmentActivity)

    /**
     * Main thread. [onResult] is called exactly once, on the main thread, unless [cancel] withdraws the request. A
     * prompt that cannot be shown (no attached activity, or one that is stopped or has saved its state, where
     * androidx.biometric drops the request without a callback) and a request still waiting when a newer one arrives
     * are answered with `ERROR_CANCELED`.
     */
    fun authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit)

    /** Main thread. Withdraws the last request, whose caller is gone, and dismisses its prompt; it is never answered. */
    fun cancel()

    /** API 28–29: `createConfirmDeviceCredentialIntent(title, null)`; null without a secure lock screen. */
    fun credentialIntent(title: String): Intent?

    /** The API-dependent enrollment intent (security.md § 7.1) and the `ACTION_SECURITY_SETTINGS` fallback. */
    fun enrollIntents(): Pair<Intent, Intent>
}

/**
 * One prompt (security.md § 7.2): a strong prompt, a slot prompt ([crypto] set, `PASSWORD` mode) or the API 30+
 * device-credential prompt ([credentialOnly], no negative button).
 */
class PromptRequest(
    val title: String,
    val credentialOnly: Boolean,
    val negativeText: String?,
    val crypto: SlotCipher? = null,
) {
    init {
        require(credentialOnly == (negativeText == null)) { "a negative button exactly without DEVICE_CREDENTIAL" }
        require(!(credentialOnly && crypto != null)) { "a CryptoObject is never combined with DEVICE_CREDENTIAL" }
    }

    override fun toString() = "PromptRequest(credentialOnly=$credentialOnly, crypto=${crypto != null})"
}

sealed interface PromptResult {
    /** [cryptoMatches]: the result's cipher is the very cipher of the request (always true without crypto). */
    class Succeeded(val cryptoMatches: Boolean) : PromptResult {
        override fun toString() = "Succeeded(cryptoMatches=$cryptoMatches)"
    }

    /** A BiometricPrompt error code and the system's text. */
    class Error(val code: Int, val message: String) : PromptResult {
        override fun toString() = "Error(code=$code)"
    }
}

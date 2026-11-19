package com.doro.organizer.security

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle

/** The production [BiometricGateway] over androidx.biometric 1.1.0 and `KeyguardManager` (security.md § 7). */
class DefaultBiometricGateway(context: Context) : BiometricGateway {
    private val context = context.applicationContext
    private val keyguard = checkNotNull(this.context.getSystemService(KeyguardManager::class.java))
    private var prompt: BiometricPrompt? = null
    private var owner: FragmentActivity? = null
    private var pending: Pending? = null

    override fun canAuthenticate(): BiometricAvailability =
        availabilityOf(BiometricManager.from(context).canAuthenticate(Authenticators.BIOMETRIC_STRONG))

    override fun isDeviceSecure(): Boolean = keyguard.isDeviceSecure

    override fun attach(activity: FragmentActivity) {
        // androidx.biometric routes a prompt that outlives a rotation to the callback of the newest instance.
        prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        owner = activity
    }

    override fun detach(activity: FragmentActivity) {
        if (owner !== activity) return // a newer instance is already attached
        val shown = prompt
        prompt = null
        owner = null
        // Across a configuration change the prompt reports to the next instance; otherwise it dies here.
        if (!activity.isChangingConfigurations) {
            takePending()?.let { waiting ->
                shown?.cancelAuthentication()
                waiting.onResult(CANCELED)
            }
        }
    }

    override fun authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit) {
        val current = prompt
        val activity = owner
        // androidx.biometric 1.1.0 logs and drops a request once the fragment manager has saved its state.
        if (current == null || activity == null || activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            onResult(CANCELED) // silent everywhere; the next ON_START prompts again (security.md § 7.4)
            return
        }
        takePending()?.onResult(CANCELED)
        pending = Pending(request, onResult)
        val crypto = request.crypto
        if (crypto == null) current.authenticate(promptInfo(request)) else {
            current.authenticate(promptInfo(request), BiometricPrompt.CryptoObject(crypto.cipher))
        }
    }

    override fun cancel() {
        if (takePending() != null) prompt?.cancelAuthentication()
    }

    private fun takePending(): Pending? = pending.also { pending = null }

    @Suppress("DEPRECATION") // the API 28–29 branch of security.md § 7.6; API 30+ uses the credential prompt
    override fun credentialIntent(title: String): Intent? = keyguard.createConfirmDeviceCredentialIntent(title, null)

    override fun enrollIntents(): Pair<Intent, Intent> =
        enrollIntent(Build.VERSION.SDK_INT) to Intent(Settings.ACTION_SECURITY_SETTINGS)

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val done = takePending() ?: return
            val expected = done.request.crypto?.cipher
            done.onResult(PromptResult.Succeeded(cryptoMatches = expected == null || result.cryptoObject?.cipher === expected))
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val done = takePending() ?: return
            done.onResult(PromptResult.Error(errorCode, errString.toString()))
        }
        // onAuthenticationFailed: one rejected attempt, the prompt stays open; nothing changes (security.md § 7.4).
    }

    private class Pending(val request: PromptRequest, val onResult: (PromptResult) -> Unit)

    internal companion object {
        private val CANCELED = PromptResult.Error(BiometricPrompt.ERROR_CANCELED, "")

        /** security.md § 7.1: `Unavailable` covers every hardware or platform reason a strong biometric cannot run. */
        fun availabilityOf(code: Int): BiometricAvailability = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Success
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> BiometricAvailability.StatusUnknown
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
            else -> BiometricAvailability.Unavailable
        }

        /** security.md § 7.2: the strong or slot prompt, or the API 30+ device-credential prompt. */
        fun promptInfo(request: PromptRequest): BiometricPrompt.PromptInfo {
            val builder = BiometricPrompt.PromptInfo.Builder().setTitle(request.title)
            return if (request.credentialOnly) {
                builder.setAllowedAuthenticators(Authenticators.DEVICE_CREDENTIAL).build()
            } else {
                builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(checkNotNull(request.negativeText))
                    .setConfirmationRequired(false) // a hint: passive face unlock needs no extra tap
                    .build()
            }
        }

        /** The enrollment action by API level (security.md § 7.1); a pure function of [sdkInt] (DEC-15). */
        @SuppressLint("InlinedApi") // the constant is only used on API 30+
        fun enrollAction(sdkInt: Int): String =
            if (sdkInt >= Build.VERSION_CODES.R) Settings.ACTION_BIOMETRIC_ENROLL else enrollActionBeforeR()

        @Suppress("DEPRECATION") // the API 28–29 action
        private fun enrollActionBeforeR() = Settings.ACTION_FINGERPRINT_ENROLL

        @SuppressLint("InlinedApi") // the extra is only added on API 30+
        fun enrollIntent(sdkInt: Int): Intent = Intent(enrollAction(sdkInt)).apply {
            if (sdkInt >= Build.VERSION_CODES.R) {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, Authenticators.BIOMETRIC_STRONG)
            }
        }
    }
}

package com.doro.organizer.testing

import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.security.BiometricGateway
import com.doro.organizer.security.PromptRequest
import com.doro.organizer.security.PromptResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform side of `AppLockController` without system UI (testing.md § 7): a settable availability and
 * device-secure flag, scripted prompt outcomes and a record of every prompt. A prompt with nothing scripted stays
 * open until the test calls [answer]; like the production gateway it answers an open prompt with `ERROR_CANCELED`
 * when a newer prompt replaces it or its activity is detached for good, and [cancel] withdraws it unanswered. A slot
 * prompt succeeds with `cryptoMatches = true` unless a test scripts otherwise. [credentialIntent] answers
 * [credentialIntentFactory], null by default (no secure lock screen); [enrollIntents] counts its calls.
 */
class FakeBiometricGateway : BiometricGateway {
    /** What one prompt looked like. */
    data class Prompt(val title: String, val credentialOnly: Boolean, val negativeText: String?, val withCrypto: Boolean)

    private val lock = Any()
    private val outcomes = ArrayDeque<PromptResult>()
    private val shown = mutableListOf<Prompt>()
    private var open: ((PromptResult) -> Unit)? = null
    private val count = MutableStateFlow(0)

    /** The number of prompts shown so far, to wait for one. */
    val shownCount: StateFlow<Int> = count.asStateFlow()

    @Volatile var availability = BiometricAvailability.Success

    @Volatile var deviceSecure = true

    /** The API 28–29 confirm-credential intent; null means no secure lock screen. */
    @Volatile var credentialIntentFactory: (String) -> Intent? = { null }

    @Volatile var credentialIntentCalls = 0
        private set

    @Volatile var attached = 0
        private set

    @Volatile var cancelled = 0
        private set

    /** Calls of [enrollIntents]: the enrollment launches Settings asked for. */
    @Volatile var enrollLaunches = 0
        private set

    /** Every prompt shown, strong, slot and credential alike, in order. */
    val prompts: List<Prompt> get() = synchronized(lock) { shown.toList() }

    /** Strong and slot prompts. */
    val biometricPromptCount: Int get() = prompts.count { !it.credentialOnly }

    /** API 30+ device-credential prompts. */
    val credentialPromptCount: Int get() = prompts.count { it.credentialOnly }

    val hasOpenPrompt: Boolean get() = synchronized(lock) { open != null }

    /** The next prompt ends with [result]. */
    fun next(result: PromptResult) = synchronized(lock) { outcomes.addLast(result) }

    /** The next prompt succeeds. */
    fun succeed(cryptoMatches: Boolean = true) = next(PromptResult.Succeeded(cryptoMatches))

    /** The next prompt ends with BiometricPrompt error [code]. */
    fun fail(code: Int, message: String = "error $code") = next(PromptResult.Error(code, message))

    /** Answers the prompt that is open now. */
    fun answer(result: PromptResult) {
        val callback = takeOpen() ?: error("no prompt is open")
        callback(result)
    }

    override fun canAuthenticate(): BiometricAvailability = availability

    override fun isDeviceSecure(): Boolean = deviceSecure

    override fun attach(activity: FragmentActivity) {
        attached++
    }

    override fun detach(activity: FragmentActivity) {
        if (!activity.isChangingConfigurations) takeOpen()?.invoke(CANCELED)
    }

    override fun authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit) {
        takeOpen()?.invoke(CANCELED)
        val scripted = synchronized(lock) {
            shown += Prompt(request.title, request.credentialOnly, request.negativeText, request.crypto != null)
            count.value = shown.size
            outcomes.removeFirstOrNull().also { if (it == null) open = onResult }
        }
        scripted?.let(onResult)
    }

    override fun cancel() {
        if (takeOpen() != null) cancelled++
    }

    private fun takeOpen(): ((PromptResult) -> Unit)? = synchronized(lock) { open.also { open = null } }

    override fun credentialIntent(title: String): Intent? {
        credentialIntentCalls++
        return credentialIntentFactory(title)
    }

    override fun enrollIntents(): Pair<Intent, Intent> {
        enrollLaunches++
        return Intent(ENROLL_ACTION) to Intent(ENROLL_FALLBACK_ACTION)
    }

    override fun toString() = "FakeBiometricGateway"

    companion object {
        private val CANCELED = PromptResult.Error(BiometricPrompt.ERROR_CANCELED, "")

        /** Stub actions without a handler, so a launch of either is a reported failure. */
        const val ENROLL_ACTION = "com.doro.organizer.test.ENROLL"
        const val ENROLL_FALLBACK_ACTION = "com.doro.organizer.test.ENROLL_FALLBACK"
    }
}

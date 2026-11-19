package com.doro.organizer.security

import com.doro.organizer.model.PasswordReason

/**
 * What the gate currently shows, as far as prompting is concerned (architecture.md § Event wiring). Only
 * [Prompt], [PromptToOpen] and [PromptWithoutKey] ever prompt for unlocking.
 */
enum class LockScreen {
    Content, Other, PromptToOpen, Prompt, PromptWithoutKey, Password;

    val prompts: Boolean get() = this == PromptToOpen || this == Prompt || this == PromptWithoutKey
}

/**
 * A failed unlock shown on the lock screen: the BiometricPrompt error code and the system's `errString`
 * (security.md § 7.4), never user data. A null [message] is a silent error: the screen shows no text.
 */
class LockError(val code: Int, val message: String?) {
    override fun toString() = "LockError(code=$code)"

    companion object {
        /** Not a BiometricPrompt code: the slot cipher failed (T44, T11); the screen shows `gate_slot_failed`. */
        const val SLOT_FAILED = -1
    }
}

/**
 * The gate events `AppLockController` raises (architecture.md § Event wiring), implemented by `SessionGate`. Every
 * call arrives on the main thread.
 */
interface LockListener {
    fun onLockNow()

    fun onUnlocked()

    fun onAuthError(error: LockError)

    /** A successful slot prompt while the screen is `PromptToOpen`: [cipher] goes into `Opening` (T42). */
    fun onSlotUnlocked(cipher: SlotCipher)

    /** The password route; [failures] and [retryAt] (elapsed base) from the stored backoff (T45, T46). */
    fun onPasswordRequired(reason: PasswordReason, fingerprintUsable: Boolean, failures: Int, retryAt: Long)

    /** The ON_START re-check of the key-store password screen (DEC-86; T61, T62). */
    fun onFingerprintRechecked(usable: Boolean)
}

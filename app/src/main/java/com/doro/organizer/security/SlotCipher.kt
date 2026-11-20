package com.doro.organizer.security

import javax.crypto.Cipher

// The biometric-slot types of security.md § 3.6 and § 3.7. None of them prints array content (DEC-70).

/** What a [SlotCipher] is for: releasing the DEK (DECRYPT) or arming a new slot (ENCRYPT). */
enum class SlotUse { Unlock, Arm }

/**
 * A Cipher on the biometric key, initialized by `KeyVault` and authorized by exactly one BiometricPrompt
 * (security.md § 3.7). Single use. Only `KeyVault` creates one; `AppLockController` and the gateway pass it on
 * unopened, and it is never stored in a ViewModel, `SavedStateHandle`, Bundle or navigation key (§ 3.6).
 */
class SlotCipher internal constructor(
    val use: SlotUse,
    internal val cipher: Cipher,
    /** The alias of the key the cipher was initialized on: the committed slot's (`Unlock`) or a fresh one (`Arm`). */
    internal val keyAlias: String,
    /** The committed generation the cipher was prepared for. */
    internal val generation: Long,
    /** A copy of that state's `slot.outerIv`, the AAD binding of the biometric slot (§ 3.7.2). */
    internal val outerIv: ByteArray,
    /**
     * A copy of the `bioSlot.iv` an `Unlock` cipher was initialized for, so a slot re-armed during the prompt is a
     * race and not a dead slot; null for `Arm`.
     */
    internal val bioIv: ByteArray?,
) {
    override fun toString() = "SlotCipher(use=$use)"
}

/** The answer of `biometricUnlockCipher` and `biometricArmCipher` (security.md § 3.7). */
sealed interface SlotCipherResult {
    class Ready(val slotCipher: SlotCipher) : SlotCipherResult {
        override fun toString() = "Ready($slotCipher)"
    }

    /** The committed mode is not `PASSWORD`, or (unlock only) there is no usable `bioSlot`. */
    data object NoSlot : SlotCipherResult

    /** The key was invalidated, its alias is missing or the slot was judged dead (§ 3.7.4); the slot's key is gone. */
    data object Invalidated : SlotCipherResult

    /** Any other Keystore failure, after one retry for an unlock cipher; the slot is kept. */
    data object Unavailable : SlotCipherResult
}

/** The answer of `wrapForBiometric` (security.md § 3.7.2); nothing is persisted either way. */
sealed interface WrapResult {
    class Wrapped(val bioSlot: BioSlot) : WrapResult {
        override fun toString() = "Wrapped"
    }

    /** The cipher failed, or a key change was committed since it was prepared; the cipher's key is deleted. */
    data object BiometricUnusable : WrapResult
}

/** The condition of the biometric slot in the committed state (security.md § 3.7.5). */
enum class BioSlotStatus {
    /** No file, an invalid file, a mode other than `PASSWORD`, or no usable `bioSlot` (a malformed one included). */
    None,

    /** A `bioSlot` whose own key, named by `bioSlot.keyAlias`, is present (DEC-98). */
    Live,

    /**
     * A `bioSlot` whose key is missing, or whose alias check failed twice: the password unlocks and the re-creation
     * offer is due.
     */
    Dead,
}

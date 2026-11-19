package com.doro.organizer.session

import com.doro.organizer.security.SlotCipher

/** The effects the reducer asks for (architecture.md § States, events and effects); each reports back as events. */
sealed interface GateEffect {
    /** E1: SQLCipher init, settings, startup reconciliation, the cold-start route. */
    data object RunStartup : GateEffect

    /**
     * E2: unwrap the key and open. At most one of [password] (owned and zeroed by the effect), [cipher] (the one
     * authorized decryption) and [resetKey] (the key a fingerprint reset committed, T55).
     */
    class UnlockAndOpen(
        val password: CharArray? = null,
        val cipher: SlotCipher? = null,
        val resetKey: Boolean = false,
    ) : GateEffect {
        init {
            require(listOfNotNull(password, cipher, resetKey.takeIf { it }).size <= 1) { "one key source at most" }
        }

        override fun toString() = "UnlockAndOpen(password=${password != null}, cipher=${cipher != null}, resetKey=$resetKey)"
    }

    /** E3: close Room and zero the key; no file is deleted (DEC-85). */
    data object CloseDatabase : GateEffect

    /** E4: check [password] without reopening; owned and zeroed by the effect. */
    class VerifyPassword(val password: CharArray) : GateEffect {
        override fun toString() = "VerifyPassword(<redacted>)"
    }

    /** E5: `AppLockController.offerSlotSetup()`. */
    data object OfferSlotSetup : GateEffect

    /** E6: `AppLockController.clearKekBroken()` after a committed fingerprint reset (DEC-86). */
    data object ClearKekBroken : GateEffect
}

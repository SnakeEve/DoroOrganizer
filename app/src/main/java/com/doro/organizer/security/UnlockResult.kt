package com.doro.organizer.security

/**
 * The answer of a `KeyVault` key or password operation (security.md § 3.6). The `retryAtElapsedMs` values are on the
 * `AppTime.elapsedMillis()` base (security.md § 5.3).
 */
sealed interface UnlockResult {
    /** [rawKey] belongs to the receiver; `ByteArray(0)` for `PLAIN`. [kekBroken]: biometric path only (§ 3.7.3). */
    class Key(val rawKey: ByteArray, val kekBroken: Boolean = false) : UnlockResult {
        override fun toString() = "Key(kekBroken=$kekBroken)"
    }

    /** `verifyPassword` or `verifyBiometric`: correct, nothing returned. [kekBroken]: `verifyBiometric` only. */
    data class Verified(val kekBroken: Boolean = false) : UnlockResult

    /** A wrong password (counted), or empty or malformed input (not counted). */
    data class PasswordRejected(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult

    /** A backoff runs: no key derivation was attempted. */
    data class Throttled(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult

    /**
     * The attempt could not be counted, because `SettingsStore` could not be written (a full disk), so its password
     * was not checked (DEC-75). [failures] and [retryAtElapsedMs] are the backoff in force, unchanged.
     */
    data class StorageFull(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult

    /** A `PASSWORD` slot and no password supplied. */
    data object PasswordNeeded : UnlockResult

    /**
     * The biometric slot path failed (security.md § 3.7.4); never KeyUnavailable. [invalidated]: the slot is dead
     * (tag failure, wrong length, judged dead after repeated failures) and its alias is deleted.
     */
    data class SlotFailed(val invalidated: Boolean) : UnlockResult

    /** KeyUnavailable (security.md § 5.2): the Keystore layer failed or the key-state is unusable. */
    data object KeyFailed : UnlockResult
}

enum class KeyFailure { KeyUnavailable, InvalidState }

/** Constant message and no cause, so no key-state content can reach a log (DEC-70). */
class KeyVaultException(val failure: KeyFailure) : Exception(failure.name)

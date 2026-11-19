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

    /** `verifyPassword`: correct, nothing returned. */
    data class Verified(val kekBroken: Boolean = false) : UnlockResult

    /** A wrong password (counted), or empty or malformed input (not counted). */
    data class PasswordRejected(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult

    /** A backoff runs: no key derivation was attempted. */
    data class Throttled(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult

    /** A `PASSWORD` slot and no password supplied. */
    data object PasswordNeeded : UnlockResult

    /** KeyUnavailable (security.md § 5.2): the Keystore layer failed or the key-state is unusable. */
    data object KeyFailed : UnlockResult
}

enum class KeyFailure { KeyUnavailable, InvalidState }

/** Constant message and no cause, so no key-state content can reach a log (DEC-70). */
class KeyVaultException(val failure: KeyFailure) : Exception(failure.name)

package com.doro.organizer.security

import com.doro.organizer.model.KeyMode

/**
 * Owner of the key-state file `noBackupFilesDir/db-state.json`, the Keystore KEK, PBKDF2 and the DEK wraps
 * (DEC-60, DEC-80, security.md § 3.6). Every member runs under one non-reentrant mutex and off the main thread.
 * Password arrays passed in stay the caller's (it zeroes them); arrays returned belong to the receiver.
 */
interface KeyVault {
    /** The committed state; null = no file. Throws [KeyVaultException] (`InvalidState`) for an unreadable or invalid file. */
    suspend fun readState(): KeyState?

    /**
     * Effect E2, password path: `PLAIN` gives `Key(ByteArray(0))`, `DEVICE` the unwrapped key, `PASSWORD` without
     * a password [UnlockResult.PasswordNeeded]. A missing or invalid file gives [UnlockResult.KeyFailed]. An empty
     * [password] means "supplied but unusable" and is rejected without counting.
     */
    suspend fun unlock(password: ByteArray?): UnlockResult

    /** The key of [slot]: the key-change current password and the restore from Corrupt (db-lifecycle.md). */
    suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult

    /**
     * Proves the password of the committed `PASSWORD` slot and zeroes the key it built: [UnlockResult.Verified],
     * `PasswordRejected`, `Throttled` or `KeyFailed` (also for a slot that is not `PASSWORD`).
     */
    suspend fun verifyPassword(password: ByteArray): UnlockResult

    /**
     * A new DEK and its slot for [target]; [newPassword] is required (non-empty) for `PASSWORD` and must be null
     * otherwise. Persists nothing but a missing KEK, or a broken one while the committed mode is `PLAIN` (security.md
     * § 3.1). Throws [KeyVaultException] (`KeyUnavailable`) when the KEK or the password layer fails.
     */
    suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey

    /**
     * The commit point (DEC-80): atomically rewrites the key-state with exactly these inputs, after parsing its own
     * serialization back (an [IllegalStateException] with nothing written when that fails). Requires
     * `bio == null || slot.mode == PASSWORD`.
     */
    suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?)

    /** Erase (security.md § 5.4): deletes the key-state file, then the KEK; failed deletes are ignored. */
    suspend fun eraseState()

    /** Deletes the KEK; failures are ignored. */
    suspend fun deleteKek()

    /** Whether the 14-day reminder is due (DEC-74, security.md § 5.6). */
    suspend fun isPasswordReminderDue(): Boolean
}

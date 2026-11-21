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
     * [password] means "supplied but unusable" and is rejected without counting. A password is counted before it is
     * checked; one whose count cannot be written is not checked: [UnlockResult.StorageFull] (DEC-75).
     */
    suspend fun unlock(password: ByteArray?): UnlockResult

    /** The key of [slot]: the key-change current password and the restore from Corrupt (db-lifecycle.md). */
    suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult

    /**
     * Proves the password of the committed `PASSWORD` slot and zeroes the key it built: [UnlockResult.Verified],
     * `PasswordRejected`, `Throttled`, `StorageFull` or `KeyFailed` (also for a slot that is not `PASSWORD`).
     */
    suspend fun verifyPassword(password: ByteArray): UnlockResult

    /**
     * A new DEK and its slot for [target]; [newPassword] is required (non-empty) for `PASSWORD` and must be null
     * otherwise. Persists nothing but a missing KEK, or a replaced one: a broken KEK while the committed mode is
     * `PLAIN`, or, once and for `PASSWORD` only, the KEK a fingerprint unlock found broken for the committed generation
     * and that still fails when checked again (the fingerprint reset, security.md § 3.1, § 4.3). Throws [KeyVaultException] (`KeyUnavailable`) when the KEK or the password
     * layer fails.
     */
    suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey

    /**
     * The commit point (DEC-80): atomically rewrites the key-state with exactly these inputs, after parsing its own
     * serialization back (an [IllegalStateException] with nothing written when that fails). Requires
     * `bio == null || slot.mode == PASSWORD`.
     */
    suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?)

    /**
     * Erase (security.md § 5.4): deletes the key-state file, then the KEK, then every biometric key; failed deletes
     * are ignored.
     */
    suspend fun eraseState()

    /** Deletes the KEK; failures are ignored. */
    suspend fun deleteKek()

    // The biometric slot (security.md § 3.7). These never touch the backoff values or `passwordVerifiedAt`, never
    // rewrite the key-state file, and answer a Keystore failure of the biometric key with a result, never a throw.

    /**
     * The committed slot's condition: `Live` only while its own key (`bioSlot.keyAlias`, DEC-98) exists. Asks the
     * Keystore only whether that alias exists (no operation, no authentication): the single slot query of
     * `UnlockPolicy`, availability and `reconcileLock`. An alias check that fails twice (retried after 1 s, § 5.2) is
     * `Unknown` (DEC-77): a slot that cannot be looked up is not live, and not known to be dead either.
     */
    suspend fun biometricSlotStatus(): BioSlotStatus

    /**
     * A DECRYPT cipher on the committed `bioSlot` (security.md § 3.7.3): `NoSlot`, `Invalidated` (alias deleted, the
     * dead `bioSlot` stays in the file) or `Unavailable` (slot kept) when none can be prepared.
     */
    suspend fun biometricUnlockCipher(): SlotCipherResult

    /**
     * The raw key through the slot with an authorized `Unlock` cipher: `Key` (with `kekBroken` when the password
     * slot's KEK layer fails), `SlotFailed` or `KeyFailed`. Cold start, Restore from Corrupt, fingerprint reset.
     */
    suspend fun unlockWithBiometric(cipher: SlotCipher): UnlockResult

    /** As [unlockWithBiometric] but builds no raw key: `Verified(kekBroken)`. Return from background, lock off. */
    suspend fun verifyBiometric(cipher: SlotCipher): UnlockResult

    /**
     * A new biometric key under a fresh alias and its ENCRYPT cipher (security.md § 3.7.2, DEC-98); the committed slot's
     * key is left alone. `NoSlot` unless the mode is `PASSWORD`.
     */
    suspend fun biometricArmCipher(): SlotCipherResult

    /**
     * Wraps the DEK of [liveRawKey] with an authorized `Arm` cipher (security.md § 3.7.2). Persists nothing:
     * `DbMaintenance` commits the slot. [liveRawKey] stays the caller's.
     */
    suspend fun wrapForBiometric(cipher: SlotCipher, liveRawKey: ByteArray): WrapResult

    /**
     * Deletes the key an `Arm` [cipher] was created on unless the committed `bioSlot` names it (DEC-98), best effort;
     * never touches the file. A slot creation that ends without committing that slot; over an invalid key-state too,
     * because no file can name a fresh alias that no commit wrote.
     */
    suspend fun discardArmCipher(cipher: SlotCipher)

    /**
     * Deletes every biometric key, the committed slot's included (that one even when the Keystore cannot list its
     * keys), best effort and idempotent; never touches the file. Slot removal, the `DELETE_BIO_KEY` step after a
     * new-DEK commit, and a slot found dead outside the vault.
     */
    suspend fun deleteBiometricKey()

    /**
     * Deletes every biometric key that the committed `bioSlot` does not name (DEC-98), best effort; never touches the
     * file. A missing key-state names none; an invalid one deletes nothing. After a slot creation's commit and at
     * startup reconciliation; every arming does the same inside the vault.
     */
    suspend fun deleteUnreferencedBiometricKeys()

    /** Whether the 14-day reminder is due (DEC-74, security.md § 5.6). */
    suspend fun isPasswordReminderDue(): Boolean
}

package com.doro.organizer.security

import com.doro.organizer.core.result.AppResult

/**
 * The slot-only key-state writes of the biometric slot (security.md § 7.3, db-lifecycle.md § Biometric slot commits),
 * implemented by `DbMaintenance` and injected into `AppLockController` as a provider.
 */
interface BiometricSlotWriter {
    /** Wraps the live DEK with an authorized `Arm` cipher and commits the slot; gate `Ready`; `Err(Busy)` while busy. */
    suspend fun createBiometricSlot(cipher: SlotCipher): AppResult<Unit>

    /** Deletes the biometric key, then removes the slot from the key-state; any gate state; always `Ok(Unit)` in v1. */
    suspend fun removeBiometricSlot(): AppResult<Unit>
}

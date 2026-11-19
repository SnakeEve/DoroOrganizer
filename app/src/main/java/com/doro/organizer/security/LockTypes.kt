package com.doro.organizer.security

// The Settings-facing vocabulary of AppLockController (security.md § 7.1, § 7.3, § 7.8).

/** What a prompt is for. `SetUpSlot`, `ResetPassword` and `RestoreKey` exist in `PASSWORD` mode only. */
enum class AuthPurpose { Unlock, EnableLock, DisableLock, SetUpSlot, ResetPassword, RestoreKey }

/** The result of `confirm` and `confirmWithPassword`. */
enum class ToggleOutcome { Done, Cancelled, PasswordRequired }

/** `NotUsed`: mode is not `PASSWORD` or the lock is off; otherwise the slot's `BioSlotStatus` (Live, None, Dead). */
enum class SlotState { NotUsed, Ready, Missing, Invalidated }

/** The Settings row state of the biometric lock (security.md § 7.1). */
data class LockAvailability(val biometric: BiometricAvailability, val deviceSecure: Boolean, val slot: SlotState)

/** The re-creation offer (security.md § 7.8): `BioSlotStatus` None / Dead when it was made. */
enum class SlotOffer { Missing, Invalidated }

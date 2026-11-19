package com.doro.organizer.security

import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute

/**
 * The finer causes behind a password decision (security.md § 5.1). Internal to the decision: the gate sees only
 * [PasswordReason] and `fingerprintUsable` through [UnlockPolicy.toRoute].
 */
internal enum class PasswordCause { LockOff, NoBiometric, SlotMissing, SlotInvalidated, SlotUnavailable, Reminder, UserChose }

/** What an unlock asks for (security.md § 5.1, § 5.5). */
internal sealed interface PolicyDecision {
    /** No lock: a cold start goes to `Opening`, a return from background shows the content. */
    data object None : PolicyDecision

    /** `PLAIN`/`DEVICE`, lock on without a secure lock screen: § 7.6 turns the lock off. Never for `PASSWORD`. */
    data object AutoOff : PolicyDecision

    /** `PLAIN`/`DEVICE`: a strong prompt without a `CryptoObject`. */
    data object Biometric : PolicyDecision

    /** `PLAIN`/`DEVICE` without a usable strong biometric: the device credential (Q7). */
    data object DeviceCredential : PolicyDecision

    /** `PASSWORD`: the slot prompt with a DECRYPT `CryptoObject`. */
    data object Slot : PolicyDecision

    data class Password(val cause: PasswordCause) : PolicyDecision
}

/**
 * The unlock method (security.md § 5.1, DEC-62, DEC-65, DEC-74), pure. Effect E1 evaluates it for the cold start and
 * `AppLockController` again right before every prompt, because availability, enrollment and the clock can change.
 */
object UnlockPolicy {
    internal fun route(
        mode: KeyMode,
        coldStart: Boolean,
        lockOn: Boolean,
        deviceSecure: Boolean,
        biometric: BiometricAvailability,
        bio: BioSlotStatus,
        reminderDue: Boolean,
    ): PolicyDecision {
        val usable = deviceSecure &&
            (biometric == BiometricAvailability.Success || biometric == BiometricAvailability.StatusUnknown)
        if (mode != KeyMode.PASSWORD) {
            return when {
                !lockOn -> PolicyDecision.None
                !deviceSecure -> PolicyDecision.AutoOff
                usable -> PolicyDecision.Biometric
                else -> PolicyDecision.DeviceCredential
            }
        }
        return when {
            !lockOn -> if (coldStart) PolicyDecision.Password(PasswordCause.LockOff) else PolicyDecision.None
            // Includes a device without a secure lock screen: never AutoOff in PASSWORD mode, which would let someone
            // who knows the device PIN remove the screen lock and bypass the password (§ 5.1).
            !usable -> PolicyDecision.Password(PasswordCause.NoBiometric)
            bio == BioSlotStatus.None -> PolicyDecision.Password(PasswordCause.SlotMissing)
            bio == BioSlotStatus.Dead -> PolicyDecision.Password(PasswordCause.SlotInvalidated)
            reminderDue -> PolicyDecision.Password(PasswordCause.Reminder)
            else -> PolicyDecision.Slot
        }
    }

    /**
     * Over `Locked(KeyUnavailable)` (and a `Maintenance` resume of that origin or of an Erase) no key can be obtained
     * and the mode is unknown, so every mode behaves like `PLAIN`/`DEVICE` (§ 5.1): never Slot, never Password.
     */
    internal fun routeOverKeyUnavailable(
        lockOn: Boolean,
        deviceSecure: Boolean,
        biometric: BiometricAvailability,
    ): PolicyDecision =
        route(KeyMode.PLAIN, coldStart = false, lockOn, deviceSecure, biometric, BioSlotStatus.None, reminderDue = false)

    /**
     * The public route. Everything that is neither Slot nor Password is the Biometric route: `AppLockController`
     * handles the device credential and the auto-off at prompt time. [kekFailedWithLiveSlot]: its `kekBroken` flag is
     * set and the slot is `Live` (DEC-86); a cause `NoBiometric` then explains the failed key store.
     */
    internal fun toRoute(d: PolicyDecision, kekFailedWithLiveSlot: Boolean = false): UnlockRoute = when (d) {
        PolicyDecision.Slot -> UnlockRoute.Slot
        is PolicyDecision.Password -> when (d.cause) {
            // Not a voluntary choice: the reminder screen never offers a way around itself.
            PasswordCause.Reminder -> UnlockRoute.Password(PasswordReason.Reminder, fingerprintUsable = false)
            PasswordCause.SlotInvalidated -> UnlockRoute.Password(PasswordReason.FingerprintChanged, fingerprintUsable = false)
            // The slot is still good: "Use fingerprint" returns to the prompt.
            PasswordCause.SlotUnavailable, PasswordCause.UserChose ->
                UnlockRoute.Password(PasswordReason.None, fingerprintUsable = true)
            PasswordCause.NoBiometric -> UnlockRoute.Password(
                if (kekFailedWithLiveSlot) PasswordReason.KeyStoreFailed else PasswordReason.None,
                fingerprintUsable = false,
            )
            PasswordCause.LockOff, PasswordCause.SlotMissing ->
                UnlockRoute.Password(PasswordReason.None, fingerprintUsable = false)
        }
        PolicyDecision.None, PolicyDecision.AutoOff, PolicyDecision.Biometric, PolicyDecision.DeviceCredential ->
            UnlockRoute.Biometric
    }
}

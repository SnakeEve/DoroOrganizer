package com.doro.organizer.session

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.LockError
import com.doro.organizer.security.SlotCipher

/**
 * The inputs of the gate's reducer (architecture.md § States, events and effects). No `toString()` prints a password
 * or a cipher (DEC-70); `retryAt` values are on the `AppTime.elapsedMillis()` base.
 */
sealed interface GateEvent {
    /** E1. [route]: `Slot` or `Password(…)` in `PASSWORD` mode; `Biometric` otherwise, which no row reads. */
    data class StartupLoaded(
        val mode: KeyMode,
        val lockEnabled: Boolean,
        val failures: Int,
        val retryAt: Long,
        val route: UnlockRoute,
    ) : GateEvent {
        override fun toString() =
            "StartupLoaded(mode=$mode, lockEnabled=$lockEnabled, failures=$failures, retryAt=$retryAt, route=$route)"
    }

    data class StartupKeyFailed(val lockEnabled: Boolean) : GateEvent {
        override fun toString() = "StartupKeyFailed(lockEnabled=$lockEnabled)"
    }

    /** The gate UI hands over [password]; the gate zeroes it unless an effect takes it over. */
    class PasswordSubmitted(val password: CharArray) : GateEvent {
        override fun toString() = "PasswordSubmitted(<redacted>)"
    }

    data object Retry : GateEvent

    data object UseFingerprintChosen : GateEvent

    data object Opened : GateEvent

    data class OpenFailed(val kind: OpenFailureKind) : GateEvent {
        override fun toString() = "OpenFailed(kind=$kind)"
    }

    data class PasswordRejected(val failures: Int, val retryAt: Long) : GateEvent {
        override fun toString() = "PasswordRejected(failures=$failures, retryAt=$retryAt)"
    }

    /** The password was not checked, because its attempt could not be counted (DEC-75); the backoff is unchanged. */
    data class PasswordStorageFull(val failures: Int, val retryAt: Long) : GateEvent {
        override fun toString() = "PasswordStorageFull(failures=$failures, retryAt=$retryAt)"
    }

    data class PasswordNeeded(val failures: Int, val retryAt: Long, val reason: PasswordReason) : GateEvent {
        override fun toString() = "PasswordNeeded(failures=$failures, retryAt=$retryAt, reason=$reason)"
    }

    data object PasswordVerified : GateEvent

    data object KeyFailed : GateEvent

    /** A Keystore failure of the password slot while a live biometric slot exists (DEC-86). */
    data object KekFailedWithSlot : GateEvent

    data object SlotUnlockFailed : GateEvent

    data object LockNow : GateEvent

    data object Unlocked : GateEvent

    data class AuthError(val error: LockError) : GateEvent {
        override fun toString() = "AuthError(error=$error)"
    }

    /** A successful slot prompt at `Locked(Open)`; [cipher] authorizes exactly one decryption. */
    class SlotUnlocked(val cipher: SlotCipher) : GateEvent {
        override fun toString() = "SlotUnlocked"
    }

    data class PasswordRequired(
        val reason: PasswordReason,
        val fingerprintUsable: Boolean,
        val failures: Int,
        val retryAt: Long,
    ) : GateEvent {
        override fun toString() =
            "PasswordRequired(reason=$reason, fingerprintUsable=$fingerprintUsable, failures=$failures, retryAt=$retryAt)"
    }

    data class FingerprintRechecked(val usable: Boolean) : GateEvent {
        override fun toString() = "FingerprintRechecked(usable=$usable)"
    }

    data object CorruptAtRuntime : GateEvent

    /** [fingerprintReset]: true only from `resetPasswordWithBiometric` (DEC-63). */
    data class MaintenanceStarted(val op: MaintenanceOp, val fingerprintReset: Boolean = false) : GateEvent {
        override fun toString() = "MaintenanceStarted(op=$op, fingerprintReset=$fingerprintReset)"
    }

    data class MaintenanceFinished(val outcome: MaintenanceOutcome) : GateEvent {
        override fun toString() = "MaintenanceFinished(outcome=$outcome)"
    }
}

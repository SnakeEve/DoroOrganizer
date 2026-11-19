package com.doro.organizer.session

import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.LockError

/**
 * The startup and lock state (DEC-90, architecture.md § States, events and effects). Never saved: after process death
 * the gate starts at [Starting]. Every `toString()` prints class names, booleans, counts and enums only (DEC-70).
 * `retryAt` values are on the `AppTime.elapsedMillis()` base.
 */
sealed interface GateState {
    /** [authenticated]: a recovery operation restarted the app (T30); skips the lock in `PLAIN` and `DEVICE` only. */
    data class Starting(val authenticated: Boolean) : GateState {
        override fun toString() = "Starting(authenticated=$authenticated)"
    }

    /** `PASSWORD` mode with the database closed: the password screen (DEC-62). */
    data class NeedsPassword(
        val failures: Int,
        val retryAt: Long,
        val couldNotOpen: Boolean,
        val reason: PasswordReason = PasswordReason.None,
        val fingerprintUsable: Boolean = false,
    ) : GateState {
        override fun toString() = "NeedsPassword(failures=$failures, retryAt=$retryAt, couldNotOpen=$couldNotOpen, " +
            "reason=$reason, fingerprintUsable=$fingerprintUsable)"
    }

    /** The app is locked; [resume] returns on unlock. With a [password] entry it is the password screen. */
    data class Locked(
        val resume: Resume,
        val authError: LockError? = null,
        val password: PasswordEntry? = null,
    ) : GateState {
        override fun toString() = "Locked(resume=$resume, authError=$authError, password=$password)"
    }

    /** Unwrap the key, open, verify. At most one of [viaPassword], [viaSlot] and [viaReset] is true. */
    data class Opening(
        val viaPassword: Boolean,
        val lockPending: Boolean,
        val transientError: Boolean,
        val viaSlot: Boolean = false,
        val reason: PasswordReason = PasswordReason.None,
        val fingerprintUsable: Boolean = false,
        val viaReset: Boolean = false,
    ) : GateState {
        override fun toString() = "Opening(viaPassword=$viaPassword, lockPending=$lockPending, " +
            "transientError=$transientError, viaSlot=$viaSlot, reason=$reason, fingerprintUsable=$fingerprintUsable, " +
            "viaReset=$viaReset)"
    }

    data object Ready : GateState

    /**
     * A DbMaintenance operation started from [origin]. [resetFrom] is set exactly for a fingerprint password reset
     * (DEC-63): the password screen it started from; [lockPending] is used only then (T53, T54).
     */
    data class Maintenance(
        val op: MaintenanceOp,
        val origin: Origin,
        val corruptPending: Boolean = false,
        val resetFrom: PasswordEntry? = null,
        val lockPending: Boolean = false,
    ) : GateState {
        override fun toString() = "Maintenance(op=$op, origin=$origin, corruptPending=$corruptPending, " +
            "resetFrom=$resetFrom, lockPending=$lockPending)"
    }

    data object Corrupt : GateState

    data object KeyUnavailable : GateState
}

/** The password screen of a `Locked` state; it holds no password. [checkFailed]: the last check hit a Keystore failure (T50). */
data class PasswordEntry(
    val reason: PasswordReason,
    val fingerprintUsable: Boolean,
    val failures: Int,
    val retryAt: Long,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
) {
    override fun toString() = "PasswordEntry(reason=$reason, fingerprintUsable=$fingerprintUsable, failures=$failures, " +
        "retryAt=$retryAt, checking=$checking, checkFailed=$checkFailed)"
}

/** What a `Locked` state returns to on unlock. */
sealed interface Resume {
    data object Open : Resume

    data object Ready : Resume

    data class Maintenance(val state: GateState.Maintenance) : Resume {
        override fun toString() = "Maintenance(state=$state)"
    }

    data object Corrupt : Resume

    data object KeyUnavailable : Resume
}

/** The state a maintenance operation started from. */
enum class Origin { Ready, Corrupt, KeyUnavailable, NeedsPassword }

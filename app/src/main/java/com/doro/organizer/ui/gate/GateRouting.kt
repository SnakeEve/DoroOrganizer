package com.doro.organizer.ui.gate

import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Resume

/**
 * What the password screen shows (ui.md § 10.2), from `NeedsPassword`, from a `Locked` password entry or, busy, from
 * `Opening(viaPassword)`. [maintenance]: the operation running behind a locked password screen, whose text replaces
 * the reason line; the field is disabled and "Forgot password?" hidden meanwhile. `retryAt` is on the
 * `AppTime.elapsedMillis()` base.
 */
data class PasswordScreenModel(
    val reason: PasswordReason,
    val fingerprintUsable: Boolean,
    val failures: Int,
    val retryAt: Long,
    val couldNotOpen: Boolean = false,
    val busy: Boolean = false,
    val checkFailed: Boolean = false,
    val maintenance: MaintenanceOp? = null,
) {
    override fun toString() = "PasswordScreenModel(reason=$reason, fingerprintUsable=$fingerprintUsable, " +
        "failures=$failures, retryAt=$retryAt, couldNotOpen=$couldNotOpen, busy=$busy, checkFailed=$checkFailed, " +
        "maintenance=$maintenance)"
}

/** The password screen of [gate], or null when the gate shows another screen (architecture.md § Rendering). */
fun passwordScreenOf(gate: GateState): PasswordScreenModel? = when (gate) {
    is GateState.NeedsPassword ->
        PasswordScreenModel(gate.reason, gate.fingerprintUsable, gate.failures, gate.retryAt, gate.couldNotOpen)
    is GateState.Locked -> gate.password?.let { entry ->
        PasswordScreenModel(
            entry.reason, entry.fingerprintUsable, entry.failures, entry.retryAt,
            busy = entry.checking,
            checkFailed = entry.checkFailed,
            maintenance = (gate.resume as? Resume.Maintenance)?.state?.op,
        )
    }
    // The open error screen replaces the busy password screen after a transient failure (ui.md § 10.5).
    is GateState.Opening -> if (gate.viaPassword && !gate.transientError) {
        PasswordScreenModel(gate.reason, gate.fingerprintUsable, failures = 0, retryAt = 0, busy = true)
    } else {
        null
    }
    else -> null
}

/**
 * The origin of an Erase started from the Forgot password screen over [gate] (ui.md § 10.3), which is also where that
 * screen can be shown: `NeedsPassword`, and a password entry over `Locked(Ready)` or `Locked(Corrupt)`. Null on every
 * other screen, including a password entry over a running operation, where "Forgot password?" is hidden (DEC-63).
 */
fun eraseOriginOf(gate: GateState): MaintenanceOrigin? = when {
    gate is GateState.NeedsPassword -> MaintenanceOrigin.NeedsPassword
    gate !is GateState.Locked || gate.password == null -> null
    gate.resume == Resume.Ready -> MaintenanceOrigin.Ready
    gate.resume == Resume.Corrupt -> MaintenanceOrigin.Corrupt
    else -> null
}

/**
 * The origin of a fingerprint password reset over [gate]: the two origins `resetPasswordWithBiometric` accepts, never
 * `Corrupt`, where the database cannot be checked or exported (ui.md § 10.3, DEC-63).
 */
fun resetOriginOf(gate: GateState): MaintenanceOrigin? =
    eraseOriginOf(gate)?.takeIf { it != MaintenanceOrigin.Corrupt }

/**
 * Whether reaching [this] state closes the Forgot password screen (ui.md § 10.3). `Starting` and an Erase keep it, so
 * a failed erase (`NeedsPassword` -> `Maintenance(Erase)` -> `Starting` -> `NeedsPassword`) returns to it with the
 * failure; a password screen keeps it too, so only the states that leave the password screens close it.
 */
fun GateState.closesForgotScreen(): Boolean = when (this) {
    is GateState.Starting, is GateState.NeedsPassword -> false
    is GateState.Maintenance -> op != MaintenanceOp.Erase
    is GateState.Locked -> password == null
    is GateState.Opening, GateState.Ready, GateState.Corrupt, GateState.KeyUnavailable -> true
}

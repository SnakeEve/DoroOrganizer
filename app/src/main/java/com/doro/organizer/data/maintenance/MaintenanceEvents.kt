package com.doro.organizer.data.maintenance

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.model.KeyMode

/** The operation of `GateState.Maintenance(op, origin)` (db-lifecycle.md § Interface and status). */
enum class MaintenanceOp { Backup, Restore, KeyChange, StartFresh, Erase }

/**
 * The state an operation starts from; the same four names as SessionGate's `Origin`, defined here because
 * `data.maintenance` does not import `session`. Only the screen that started an operation knows it (frame step 4).
 */
enum class MaintenanceOrigin { Ready, Corrupt, KeyUnavailable, NeedsPassword }

/** How an operation that raised `MaintenanceStarted` ended: `GateEvent.MaintenanceFinished(outcome)`. */
sealed interface MaintenanceOutcome {
    data object Reopened : MaintenanceOutcome

    data class ReopenFailed(val kind: OpenFailureKind) : MaintenanceOutcome {
        override fun toString() = "ReopenFailed(kind=$kind)"
    }

    data object Restart : MaintenanceOutcome

    data object Aborted : MaintenanceOutcome

    /** Fingerprint reset from `NeedsPassword` only, after `Committed`: the new raw key waits for E2 (`takeResetKey`). */
    data object OpenWithNewKey : MaintenanceOutcome
}

/** Implemented by SessionGate; DbMaintenance calls it on the main dispatcher, so the gate changes before it goes on. */
interface MaintenanceListener {
    /** [fingerprintReset]: true only from `resetPasswordWithBiometric` (DEC-63). */
    fun onMaintenanceStarted(op: MaintenanceOp, fingerprintReset: Boolean = false)

    fun onMaintenanceFinished(outcome: MaintenanceOutcome)
}

/** What startup reconciliation found (db-lifecycle.md § Startup reconciliation). */
sealed interface StartupDecision {
    /** A valid key-state names [generation]. */
    data class Proceed(val generation: Long, val mode: KeyMode) : StartupDecision {
        override fun toString() = "Proceed(generation=$generation, mode=$mode)"
    }

    /** No key-state and no database file. */
    data object FirstRun : StartupDecision

    /** No key-state and only plaintext files: the highest-numbered one is adopted. */
    data class AdoptPlain(val generation: Long) : StartupDecision {
        override fun toString() = "AdoptPlain(generation=$generation)"
    }

    /** DEC-86: an invalid key-state, or a missing one next to a file that is not recognizably plaintext. */
    data object KeyUnavailable : StartupDecision
}

/** The result of `completeStartup` (E2): continue, `OpenFailed(Transient)` or `KeyFailed`. */
enum class StartupCompletion { Done, Transient, KeyUnavailable }

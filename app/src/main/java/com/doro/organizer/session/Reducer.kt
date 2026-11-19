package com.doro.organizer.session

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.LockError
import com.doro.organizer.session.GateEffect.ClearKekBroken
import com.doro.organizer.session.GateEffect.CloseDatabase
import com.doro.organizer.session.GateEffect.OfferSlotSetup
import com.doro.organizer.session.GateEffect.RunStartup
import com.doro.organizer.session.GateEffect.UnlockAndOpen
import com.doro.organizer.session.GateEffect.VerifyPassword
import com.doro.organizer.session.GateEvent.AuthError
import com.doro.organizer.session.GateEvent.CorruptAtRuntime
import com.doro.organizer.session.GateEvent.FingerprintRechecked
import com.doro.organizer.session.GateEvent.KekFailedWithSlot
import com.doro.organizer.session.GateEvent.KeyFailed
import com.doro.organizer.session.GateEvent.LockNow
import com.doro.organizer.session.GateEvent.MaintenanceFinished
import com.doro.organizer.session.GateEvent.MaintenanceStarted
import com.doro.organizer.session.GateEvent.OpenFailed
import com.doro.organizer.session.GateEvent.Opened
import com.doro.organizer.session.GateEvent.PasswordNeeded
import com.doro.organizer.session.GateEvent.PasswordRejected
import com.doro.organizer.session.GateEvent.PasswordRequired
import com.doro.organizer.session.GateEvent.PasswordSubmitted
import com.doro.organizer.session.GateEvent.PasswordVerified
import com.doro.organizer.session.GateEvent.Retry
import com.doro.organizer.session.GateEvent.SlotUnlockFailed
import com.doro.organizer.session.GateEvent.SlotUnlocked
import com.doro.organizer.session.GateEvent.StartupKeyFailed
import com.doro.organizer.session.GateEvent.StartupLoaded
import com.doro.organizer.session.GateEvent.Unlocked
import com.doro.organizer.session.GateEvent.UseFingerprintChosen
import com.doro.organizer.session.GateState.Corrupt
import com.doro.organizer.session.GateState.KeyUnavailable
import com.doro.organizer.session.GateState.Locked
import com.doro.organizer.session.GateState.Maintenance
import com.doro.organizer.session.GateState.NeedsPassword
import com.doro.organizer.session.GateState.Opening
import com.doro.organizer.session.GateState.Ready
import com.doro.organizer.session.GateState.Starting

// The transition table of architecture.md § Transition table, rows T1–T62. Each guard is mutually exclusive with the
// others for its (state, event) pair; a pair no row matches is T35 and leaves the state unchanged without effects.

internal typealias Transition = Pair<GateState, List<GateEffect>>

/** T1: `start()`. */
internal fun startTransition(): Transition = Starting(authenticated = false) to listOf(RunStartup)

/** Pure: [elapsedNow] is the only clock value, on the base of every `retryAt` (T7, T47). */
internal fun reduce(state: GateState, event: GateEvent, elapsedNow: Long): Transition = when (state) {
    is Starting -> starting(state, event)
    is NeedsPassword -> needsPassword(state, event, elapsedNow)
    is Locked -> locked(state, event, elapsedNow)
    is Opening -> opening(state, event)
    Ready -> ready(event)
    is Maintenance -> maintenance(state, event)
    Corrupt, KeyUnavailable -> recovery(state, event)
} ?: (state to emptyList())

private fun starting(s: Starting, e: GateEvent): Transition? = when (e) {
    is StartupLoaded -> when {
        e.mode == KeyMode.PASSWORD && e.route == UnlockRoute.Slot -> Locked(Resume.Open).only() // T40
        e.mode == KeyMode.PASSWORD -> { // T2
            val route = e.route as? UnlockRoute.Password
            NeedsPassword(
                e.failures, e.retryAt, couldNotOpen = false,
                reason = route?.reason ?: PasswordReason.None, fingerprintUsable = route?.fingerprintUsable ?: false,
            ).only()
        }
        e.lockEnabled && !s.authenticated -> Locked(Resume.Open).only() // T3
        else -> openingPlain() // T4
    }
    is StartupKeyFailed ->
        if (e.lockEnabled && !s.authenticated) Locked(Resume.KeyUnavailable).only() else KeyUnavailable.only() // T5, T6
    LockNow -> if (s.authenticated) Starting(authenticated = false).only() else null // T36
    else -> null
}

private fun needsPassword(s: NeedsPassword, e: GateEvent, elapsedNow: Long): Transition? = when (e) {
    is PasswordSubmitted -> if (elapsedNow >= s.retryAt) { // T7
        Opening(
            viaPassword = true, lockPending = false, transientError = false,
            reason = s.reason, fingerprintUsable = s.fingerprintUsable,
        ) to listOf(UnlockAndOpen(password = e.password))
    } else {
        null
    }
    is MaintenanceStarted -> when {
        e.fingerprintReset && e.op == MaintenanceOp.KeyChange -> Maintenance( // T41
            MaintenanceOp.KeyChange, Origin.NeedsPassword,
            resetFrom = PasswordEntry(s.reason, s.fingerprintUsable, s.failures, s.retryAt),
        ).only()
        e.op == MaintenanceOp.Erase && !e.fingerprintReset -> Maintenance(MaintenanceOp.Erase, Origin.NeedsPassword).only() // T8
        else -> null
    }
    UseFingerprintChosen -> if (s.fingerprintUsable) Locked(Resume.Open).only() else null // T51
    is FingerprintRechecked ->
        if (s.reason == PasswordReason.KeyStoreFailed) s.copy(fingerprintUsable = e.usable).only() else null // T61
    else -> null
}

private fun locked(s: Locked, e: GateEvent, elapsedNow: Long): Transition? {
    val resume = s.resume
    val entry = s.password
    val runningReset = resume is Resume.Maintenance && resume.state.resetFrom != null
    return when (e) {
        Unlocked -> when {
            resume == Resume.Open -> openingPlain() // T9
            entry == null && !runningReset -> resumeState(resume).only() // T10
            else -> null
        }
        is AuthError -> s.copy(authError = e.error).only() // T11
        is SlotUnlocked -> if (resume == Resume.Open) { // T42
            Opening(viaPassword = false, lockPending = false, transientError = false, viaSlot = true) to
                listOf(UnlockAndOpen(cipher = e.cipher))
        } else {
            null
        }
        is MaintenanceStarted -> lockedMaintenanceStarted(resume, entry, e) // T12
        CorruptAtRuntime -> when (resume) {
            Resume.Ready -> Locked(Resume.Corrupt) to listOf(CloseDatabase) // T13
            is Resume.Maintenance -> Locked(Resume.Maintenance(resume.state.copy(corruptPending = true))).only() // T39
            else -> null
        }
        is MaintenanceFinished -> if (resume is Resume.Maintenance) lockedFinished(resume.state, e.outcome) else null
        is PasswordRequired -> when {
            resume == Resume.Open -> NeedsPassword(e.failures, e.retryAt, false, e.reason, e.fingerprintUsable).only() // T45
            // T46: resume Ready, Corrupt or a Maintenance that is no fingerprint reset; no check running.
            (resume == Resume.Ready || resume == Resume.Corrupt || (resume is Resume.Maintenance && !runningReset)) &&
                entry?.checking != true ->
                s.copy(authError = null, password = PasswordEntry(e.reason, e.fingerprintUsable, e.failures, e.retryAt)).only()
            else -> null
        }
        is PasswordSubmitted -> if (entry != null && checkable(resume) && !entry.checking && elapsedNow >= entry.retryAt) {
            s.copy(password = entry.copy(checking = true, checkFailed = false)) to listOf(VerifyPassword(e.password)) // T47
        } else {
            null
        }
        PasswordVerified -> if (entry?.checking == true) { // T48
            when (resume) {
                Resume.Ready -> Ready to listOf(OfferSlotSetup)
                Resume.Corrupt -> Corrupt.only()
                else -> null
            }
        } else {
            null
        }
        is PasswordRejected -> if (entry?.checking == true) { // T49
            s.copy(password = entry.copy(failures = e.failures, retryAt = e.retryAt, checking = false)).only()
        } else {
            null
        }
        KeyFailed -> if (entry?.checking == true) { // T50: nothing is closed
            s.copy(password = entry.copy(checking = false, checkFailed = true)).only()
        } else {
            null
        }
        UseFingerprintChosen -> if (entry != null && entry.fingerprintUsable && !entry.checking) {
            s.copy(password = null).only() // T52
        } else {
            null
        }
        LockNow -> if (runningReset) { // T54
            Locked(Resume.Maintenance((resume as Resume.Maintenance).state.copy(lockPending = true))).only()
        } else {
            null
        }
        KekFailedWithSlot -> if (entry?.checking == true) s.copy(password = null).only() else null // T60
        is FingerprintRechecked -> // T62
            if (entry != null && entry.reason == PasswordReason.KeyStoreFailed && checkable(resume) && !entry.checking) {
                s.copy(password = entry.copy(fingerprintUsable = e.usable)).only()
            } else {
                null
            }
        else -> null
    }
}

/** T12: an operation started behind the lock; a fingerprint reset only from the password screen of `Locked(Ready)`. */
private fun lockedMaintenanceStarted(resume: Resume, entry: PasswordEntry?, e: MaintenanceStarted): Transition? {
    val origin = when (resume) {
        Resume.Ready -> Origin.Ready
        Resume.Corrupt -> Origin.Corrupt
        Resume.KeyUnavailable -> Origin.KeyUnavailable
        else -> return null
    }
    if (!e.fingerprintReset) return Locked(Resume.Maintenance(Maintenance(e.op, origin))).only()
    if (e.op != MaintenanceOp.KeyChange || origin != Origin.Ready || entry == null) return null
    val resetFrom = entry.copy(checking = false, checkFailed = false)
    return Locked(Resume.Maintenance(Maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = resetFrom))).only()
}

/** T14a–e, T15, T57, T58. */
private fun lockedFinished(m: Maintenance, outcome: MaintenanceOutcome): Transition? {
    val reset = m.resetFrom
    return when (outcome) {
        MaintenanceOutcome.Reopened -> when {
            m.corruptPending -> Locked(Resume.Corrupt) to listOf(CloseDatabase) // T14b
            reset == null -> Locked(Resume.Ready).only() // T14a
            m.lockPending -> Locked(Resume.Ready) to listOf(ClearKekBroken) // T57
            else -> Ready to listOf(ClearKekBroken, OfferSlotSetup) // T57
        }
        is MaintenanceOutcome.ReopenFailed -> when (outcome.kind) {
            OpenFailureKind.Corrupt -> Locked(Resume.Corrupt) to listOf(CloseDatabase) // T14c
            OpenFailureKind.Transient -> Locked(Resume.Open) to listOf(CloseDatabase) // T14d
        }
        MaintenanceOutcome.Aborted -> when {
            reset != null && m.corruptPending -> Locked(Resume.Corrupt) to listOf(CloseDatabase) // T58
            reset != null -> Locked(Resume.Ready, password = reset).only() // T58
            m.origin == Origin.Ready && m.corruptPending -> Locked(Resume.Corrupt) to listOf(CloseDatabase) // T14e
            else -> resumeOf(m.origin)?.let { Locked(it).only() } // T14e
        }
        MaintenanceOutcome.Restart -> Starting(authenticated = false) to listOf(RunStartup) // T15
        MaintenanceOutcome.OpenWithNewKey -> null
    }
}

private fun opening(s: Opening, e: GateEvent): Transition? = when (e) {
    Opened -> if (s.lockPending) { // T16
        Locked(Resume.Ready).only()
    } else {
        Ready to if (s.viaPassword || s.viaReset) listOf(OfferSlotSetup) else emptyList()
    }
    is OpenFailed -> when (e.kind) {
        OpenFailureKind.Corrupt -> openedCorrupt(s) // T17
        OpenFailureKind.Transient -> if (s.viaPassword) { // T18: a correct password already reset the counters
            NeedsPassword(0, 0, couldNotOpen = true, reason = s.reason, fingerprintUsable = s.fingerprintUsable) to
                listOf(CloseDatabase)
        } else {
            s.copy(transientError = true) to listOf(CloseDatabase) // T19
        }
    }
    CorruptAtRuntime -> openedCorrupt(s) // T37
    Retry -> when {
        !s.transientError -> null
        // T20: the failed open zeroed a reset key, so the retry is an ordinary password-less E2.
        !s.viaSlot -> s.copy(transientError = false, viaReset = false) to listOf(UnlockAndOpen())
        else -> Locked(Resume.Open).only() // T43: the cipher is spent; only a new prompt can produce another
    }
    is PasswordRejected -> NeedsPassword(e.failures, e.retryAt, false, s.reason, s.fingerprintUsable).only() // T21
    is PasswordNeeded -> NeedsPassword(e.failures, e.retryAt, false, e.reason).only() // T22
    KeyFailed -> (if (s.lockPending) Locked(Resume.KeyUnavailable) else KeyUnavailable).only() // T23
    LockNow -> s.copy(lockPending = true).only() // T24
    SlotUnlockFailed -> Locked(Resume.Open, authError = LockError(LockError.SLOT_FAILED, null)).only() // T44
    KekFailedWithSlot -> if (s.viaPassword) Locked(Resume.Open).only() else null // T59
    else -> null
}

private fun openedCorrupt(s: Opening): Transition =
    (if (s.lockPending) Locked(Resume.Corrupt) else Corrupt) to listOf(CloseDatabase)

private fun ready(e: GateEvent): Transition? = when (e) {
    LockNow -> Locked(Resume.Ready).only() // T25: Room stays open
    CorruptAtRuntime -> Corrupt to listOf(CloseDatabase) // T26
    is MaintenanceStarted -> Maintenance(e.op, Origin.Ready).only() // T27
    else -> null
}

private fun maintenance(m: Maintenance, e: GateEvent): Transition? = when (e) {
    is MaintenanceFinished -> maintenanceFinished(m, e.outcome)
    LockNow -> when {
        m.origin != Origin.NeedsPassword -> Locked(Resume.Maintenance(m)).only() // T32: the operation continues
        m.resetFrom != null -> m.copy(lockPending = true).only() // T53
        else -> null
    }
    CorruptAtRuntime -> m.copy(corruptPending = true).only() // T38
    else -> null
}

private fun maintenanceFinished(m: Maintenance, outcome: MaintenanceOutcome): Transition? = when (outcome) {
    MaintenanceOutcome.Reopened -> if (m.corruptPending) {
        Corrupt to listOf(CloseDatabase) // T28b
    } else {
        Ready to if (m.op == MaintenanceOp.KeyChange) listOf(OfferSlotSetup) else emptyList() // T28a: a Restore keeps the slot
    }
    is MaintenanceOutcome.ReopenFailed -> when (outcome.kind) {
        OpenFailureKind.Corrupt -> Corrupt to listOf(CloseDatabase) // T29a
        OpenFailureKind.Transient -> Opening(false, false, transientError = true) to listOf(CloseDatabase) // T29b
    }
    MaintenanceOutcome.Restart -> Starting(authenticated = true) to listOf(RunStartup) // T30
    MaintenanceOutcome.Aborted -> when (m.origin) {
        Origin.Ready -> if (m.corruptPending) Corrupt to listOf(CloseDatabase) else Ready.only() // T31a
        Origin.Corrupt -> Corrupt.only() // T31b
        Origin.KeyUnavailable -> KeyUnavailable.only() // T31c
        Origin.NeedsPassword -> m.resetFrom?.let { e -> // T56: nothing changed, so no E1
            NeedsPassword(e.failures, e.retryAt, false, e.reason, e.fingerprintUsable).only()
        } ?: (Starting(authenticated = false) to listOf(RunStartup)) // T31d
    }
    MaintenanceOutcome.OpenWithNewKey -> if (m.resetFrom != null && m.origin == Origin.NeedsPassword) { // T55
        Opening(viaPassword = false, lockPending = m.lockPending, transientError = false, viaReset = true) to
            listOf(ClearKekBroken, UnlockAndOpen(resetKey = true))
    } else {
        null
    }
}

private fun recovery(s: GateState, e: GateEvent): Transition? {
    val (origin, resume) = if (s == Corrupt) Origin.Corrupt to Resume.Corrupt else Origin.KeyUnavailable to Resume.KeyUnavailable
    return when (e) {
        is MaintenanceStarted -> if (e.op == MaintenanceOp.Restore || e.op == MaintenanceOp.StartFresh) {
            Maintenance(e.op, origin).only() // T33
        } else {
            null
        }
        LockNow -> Locked(resume).only() // T34
        else -> null
    }
}

private fun openingPlain(): Transition =
    Opening(viaPassword = false, lockPending = false, transientError = false) to listOf(UnlockAndOpen())

/** The state a T10 unlock returns to. */
private fun resumeState(resume: Resume): GateState = when (resume) {
    Resume.Open -> error("Locked(Open) unlocks through Opening") // T9 handles it first
    Resume.Ready -> Ready
    is Resume.Maintenance -> resume.state
    Resume.Corrupt -> Corrupt
    Resume.KeyUnavailable -> KeyUnavailable
}

/** A `Locked(Maintenance)` never has origin `NeedsPassword` (T32's guard), so it has no resume. */
private fun resumeOf(origin: Origin): Resume? = when (origin) {
    Origin.Ready -> Resume.Ready
    Origin.Corrupt -> Resume.Corrupt
    Origin.KeyUnavailable -> Resume.KeyUnavailable
    Origin.NeedsPassword -> null
}

/** T47, T62: a password check runs only over a database that is open (`Ready`) or recovering (`Corrupt`). */
private fun checkable(resume: Resume) = resume == Resume.Ready || resume == Resume.Corrupt

private fun GateState.only(): Transition = this to emptyList()

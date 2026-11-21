package com.doro.organizer.session

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.LockError
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.testing.FakeKeyVault

/** Representative gate states and events for the table-driven reducer tests (architecture.md § Transition table). */
internal object GateSamples {
    val entry = PasswordEntry(PasswordReason.None, fingerprintUsable = true, failures = 2, retryAt = 0)
    val checking = entry.copy(checking = true)
    val keyStoreEntry = PasswordEntry(PasswordReason.KeyStoreFailed, fingerprintUsable = false, failures = 0, retryAt = 0)

    fun maintenance(op: MaintenanceOp, origin: Origin, resetFrom: PasswordEntry? = null) =
        GateState.Maintenance(op, origin, resetFrom = resetFrom)

    val states: List<GateState> = listOf(
        GateState.Starting(false), GateState.Starting(true),
        GateState.NeedsPassword(0, 0, false), GateState.NeedsPassword(1, 0, false, PasswordReason.KeyStoreFailed),
        GateState.NeedsPassword(0, 0, false, PasswordReason.None, fingerprintUsable = true),
        GateState.Locked(Resume.Open), GateState.Locked(Resume.Ready), GateState.Locked(Resume.Corrupt),
        GateState.Locked(Resume.KeyUnavailable), GateState.Locked(Resume.Ready, password = entry),
        GateState.Locked(Resume.Ready, password = checking), GateState.Locked(Resume.Corrupt, password = checking),
        GateState.Locked(Resume.Ready, password = keyStoreEntry),
        GateState.Locked(Resume.Maintenance(maintenance(MaintenanceOp.Backup, Origin.Ready))),
        GateState.Locked(Resume.Maintenance(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry))),
        GateState.Locked(Resume.Maintenance(maintenance(MaintenanceOp.Restore, Origin.KeyUnavailable))),
        GateState.Opening(viaPassword = false, lockPending = false, transientError = false),
        GateState.Opening(viaPassword = true, lockPending = false, transientError = false),
        GateState.Opening(viaPassword = false, lockPending = true, transientError = true, viaSlot = true),
        GateState.Opening(viaPassword = false, lockPending = false, transientError = true, viaReset = true),
        GateState.Ready,
        maintenance(MaintenanceOp.Backup, Origin.Ready), maintenance(MaintenanceOp.KeyChange, Origin.Ready),
        maintenance(MaintenanceOp.Restore, Origin.Corrupt), maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable),
        maintenance(MaintenanceOp.Erase, Origin.NeedsPassword),
        maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry),
        GateState.Corrupt, GateState.KeyUnavailable,
    )

    fun events(cipher: SlotCipher): List<GateEvent> = listOf(
        GateEvent.StartupLoaded(KeyMode.PLAIN, lockEnabled = true, failures = 0, retryAt = 0, route = UnlockRoute.Biometric),
        GateEvent.StartupLoaded(KeyMode.PASSWORD, lockEnabled = true, failures = 0, retryAt = 0, route = UnlockRoute.Slot),
        GateEvent.StartupKeyFailed(lockEnabled = false),
        GateEvent.Retry, GateEvent.UseFingerprintChosen, GateEvent.Opened,
        GateEvent.OpenFailed(OpenFailureKind.Corrupt), GateEvent.OpenFailed(OpenFailureKind.Transient),
        GateEvent.PasswordRejected(3, 0), GateEvent.PasswordStorageFull(3, 0), GateEvent.PasswordNeeded(0, 0, PasswordReason.None),
        GateEvent.PasswordVerified,
        GateEvent.KeyFailed, GateEvent.KekFailedWithSlot, GateEvent.SlotUnlockFailed, GateEvent.LockNow, GateEvent.Unlocked,
        GateEvent.AuthError(LockError(7, "locked out")), GateEvent.SlotUnlocked(cipher),
        GateEvent.PasswordRequired(PasswordReason.None, true, 0, 0), GateEvent.FingerprintRechecked(true),
        GateEvent.CorruptAtRuntime,
        GateEvent.MaintenanceStarted(MaintenanceOp.Restore), GateEvent.MaintenanceStarted(MaintenanceOp.Erase),
        GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, fingerprintReset = true),
        GateEvent.MaintenanceFinished(MaintenanceOutcome.Reopened), GateEvent.MaintenanceFinished(MaintenanceOutcome.Aborted),
        GateEvent.MaintenanceFinished(MaintenanceOutcome.Restart), GateEvent.MaintenanceFinished(MaintenanceOutcome.OpenWithNewKey),
        GateEvent.MaintenanceFinished(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Transient)),
        GateEvent.PasswordSubmitted(CharArray(8) { 'x' }),
    )

    fun cipher() = FakeKeyVault().newSlotCipher()
}

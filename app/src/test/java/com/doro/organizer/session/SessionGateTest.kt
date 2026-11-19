package com.doro.organizer.session

import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.LockError
import com.doro.organizer.security.LockScreen
import com.doro.organizer.session.GateSamples.checking
import com.doro.organizer.session.GateSamples.entry
import com.doro.organizer.session.GateSamples.keyStoreEntry
import com.doro.organizer.session.GateSamples.maintenance
import com.doro.organizer.session.GateState.Corrupt
import com.doro.organizer.session.GateState.KeyUnavailable
import com.doro.organizer.session.GateState.Locked
import com.doro.organizer.session.GateState.NeedsPassword
import com.doro.organizer.session.GateState.Opening
import com.doro.organizer.session.GateState.Ready
import com.doro.organizer.session.GateState.Starting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-18: one test per row of architecture.md § Transition table (T1–T62 with sub-rows), the meta-test that every row
 * has one, the sequences of L-18 and the reducer parts of L-53 (a) and L-54 (b).
 */
class SessionGateTest {
    private val cipher = GateSamples.cipher()

    private fun go(state: GateState, event: GateEvent, now: Long = 0) = reduce(state, event, now)

    private fun assertStep(expected: GateState, actual: Transition, vararg effects: String) {
        assertEquals(expected, actual.first)
        assertEquals(effects.toList(), actual.second.map { it.toString() })
    }

    private fun assertIgnored(state: GateState, event: GateEvent, now: Long = 0) = assertStep(state, go(state, event, now))

    private fun loaded(mode: KeyMode, lock: Boolean, route: UnlockRoute = UnlockRoute.Biometric, failures: Int = 0, retryAt: Long = 0) =
        GateEvent.StartupLoaded(mode, lock, failures, retryAt, route)

    private fun finished(outcome: MaintenanceOutcome) = GateEvent.MaintenanceFinished(outcome)

    private fun lockedM(m: GateState.Maintenance) = Locked(Resume.Maintenance(m))

    private val opening = Opening(viaPassword = false, lockPending = false, transientError = false)

    // Rows

    @Test fun t01_start_entersStarting_withE1() = assertStep(Starting(false), startTransition(), E1)

    @Test fun t02_passwordMode_withoutSlotRoute_needsPassword_withTheRoutesReasonAndCounters() {
        val route = UnlockRoute.Password(PasswordReason.Reminder, fingerprintUsable = false)
        assertStep(NeedsPassword(3, 900, false, PasswordReason.Reminder), go(Starting(false), loaded(KeyMode.PASSWORD, true, route, 3, 900)))
        assertStep(NeedsPassword(0, 0, false), go(Starting(true), loaded(KeyMode.PASSWORD, false, UnlockRoute.Password(PasswordReason.None, false))))
    }

    @Test fun t03_plainOrDevice_lockOn_notAuthenticated_locksOpen() {
        assertStep(Locked(Resume.Open), go(Starting(false), loaded(KeyMode.PLAIN, true)))
        assertStep(Locked(Resume.Open), go(Starting(false), loaded(KeyMode.DEVICE, true)))
    }

    @Test fun t04_plainOrDevice_lockOffOrAuthenticated_opens() {
        assertStep(opening, go(Starting(false), loaded(KeyMode.PLAIN, false)), E2)
        assertStep(opening, go(Starting(true), loaded(KeyMode.DEVICE, true)), E2)
    }

    @Test fun t05_keyFailed_lockOn_locksKeyUnavailable() =
        assertStep(Locked(Resume.KeyUnavailable), go(Starting(false), GateEvent.StartupKeyFailed(true)))

    @Test fun t06_keyFailed_lockOffOrAuthenticated_isKeyUnavailable() {
        assertStep(KeyUnavailable, go(Starting(false), GateEvent.StartupKeyFailed(false)))
        assertStep(KeyUnavailable, go(Starting(true), GateEvent.StartupKeyFailed(true)))
    }

    @Test fun t07_passwordSubmitted_afterRetryAt_opensViaPassword_handingOverThePassword() {
        val state = NeedsPassword(5, 1_000, false, PasswordReason.FingerprintChanged, fingerprintUsable = true)
        val password = CharArray(8) { 'p' }
        val t = go(state, GateEvent.PasswordSubmitted(password), now = 1_000)
        assertStep(Opening(true, false, false, reason = PasswordReason.FingerprintChanged, fingerprintUsable = true), t, E2_PASSWORD)
        assertSame(password, (t.second.single() as GateEffect.UnlockAndOpen).password)
        // The guard compares the elapsedNow that dispatch passes in.
        assertIgnored(state, GateEvent.PasswordSubmitted(password), now = 999)
    }

    @Test fun t08_eraseFromNeedsPassword_entersMaintenance() =
        assertStep(maintenance(MaintenanceOp.Erase, Origin.NeedsPassword), go(NeedsPassword(0, 0, false), GateEvent.MaintenanceStarted(MaintenanceOp.Erase)))

    @Test fun t09_unlockedAtLockedOpen_opens() = assertStep(opening, go(Locked(Resume.Open), GateEvent.Unlocked), E2)

    @Test fun t10_unlocked_returnsToTheResume() {
        assertStep(Ready, go(Locked(Resume.Ready), GateEvent.Unlocked))
        assertStep(Corrupt, go(Locked(Resume.Corrupt), GateEvent.Unlocked))
        assertStep(KeyUnavailable, go(Locked(Resume.KeyUnavailable), GateEvent.Unlocked))
        val backup = maintenance(MaintenanceOp.Backup, Origin.Ready)
        assertStep(backup, go(lockedM(backup), GateEvent.Unlocked))
        // Never with a password entry, and never over a running fingerprint reset (DEC-63).
        assertIgnored(Locked(Resume.Ready, password = entry), GateEvent.Unlocked)
        assertIgnored(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)), GateEvent.Unlocked)
    }

    @Test fun t11_authError_isKeptOnTheLock() {
        val error = LockError(7, "Too many attempts")
        assertStep(Locked(Resume.Ready, authError = error), go(Locked(Resume.Ready), GateEvent.AuthError(error)))
    }

    @Test fun t12_maintenanceStartedBehindTheLock_freshLock_andTheFingerprintResetOnlyFromTheLockedReadyPasswordScreen() {
        assertStep(lockedM(maintenance(MaintenanceOp.Erase, Origin.Ready)), go(Locked(Resume.Ready, password = entry), GateEvent.MaintenanceStarted(MaintenanceOp.Erase)))
        assertStep(lockedM(maintenance(MaintenanceOp.Restore, Origin.Corrupt)), go(Locked(Resume.Corrupt), GateEvent.MaintenanceStarted(MaintenanceOp.Restore)))
        assertStep(lockedM(maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable)), go(Locked(Resume.KeyUnavailable), GateEvent.MaintenanceStarted(MaintenanceOp.StartFresh)))
        val reset = GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, fingerprintReset = true)
        val p = entry.copy(checkFailed = true)
        val t = go(Locked(Resume.Ready, password = p), reset)
        assertStep(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = p.copy(checkFailed = false))), t)
        assertEquals(LockScreen.Other, lockScreenOf(t.first))
        assertIgnored(Locked(Resume.Ready), reset)
        assertIgnored(Locked(Resume.Corrupt, password = entry), reset)
    }

    @Test fun t13_runtimeCorruptionWhileLockedOverReady_locksCorrupt_andCloses() =
        assertStep(Locked(Resume.Corrupt), go(Locked(Resume.Ready), GateEvent.CorruptAtRuntime), E3)

    @Test fun t14a_reopenedBehindTheLock_locksReady() =
        assertStep(Locked(Resume.Ready), go(lockedM(maintenance(MaintenanceOp.Backup, Origin.Ready)), finished(MaintenanceOutcome.Reopened)))

    @Test fun t14b_reopenedWithCorruptionPending_locksCorrupt() {
        val m = maintenance(MaintenanceOp.Backup, Origin.Ready).copy(corruptPending = true)
        assertStep(Locked(Resume.Corrupt), go(lockedM(m), finished(MaintenanceOutcome.Reopened)), E3)
        assertStep(Locked(Resume.Corrupt), go(lockedM(m.copy(resetFrom = entry)), finished(MaintenanceOutcome.Reopened)), E3)
    }

    @Test fun t14c_reopenFailedCorrupt_locksCorrupt() =
        assertStep(Locked(Resume.Corrupt), go(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready)), finished(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt))), E3)

    @Test fun t14d_reopenFailedTransient_locksOpen() =
        assertStep(Locked(Resume.Open), go(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready)), finished(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Transient))), E3)

    @Test fun t14e_abortedBehindTheLock_locksTheOrigin() {
        assertStep(Locked(Resume.Corrupt), go(lockedM(maintenance(MaintenanceOp.Restore, Origin.Corrupt)), finished(MaintenanceOutcome.Aborted)))
        assertStep(Locked(Resume.Ready), go(lockedM(maintenance(MaintenanceOp.Erase, Origin.Ready)), finished(MaintenanceOutcome.Aborted)))
        val pending = maintenance(MaintenanceOp.Erase, Origin.Ready).copy(corruptPending = true)
        assertStep(Locked(Resume.Corrupt), go(lockedM(pending), finished(MaintenanceOutcome.Aborted)), E3)
    }

    @Test fun t15_restartBehindTheLock_startsAgain() =
        assertStep(Starting(false), go(lockedM(maintenance(MaintenanceOp.Restore, Origin.Ready)), finished(MaintenanceOutcome.Restart)), E1)

    @Test fun t16_opened_isReady_withE5OnlyAfterAPasswordOrAReset() {
        assertStep(Ready, go(opening, GateEvent.Opened))
        assertStep(Ready, go(opening.copy(viaSlot = true), GateEvent.Opened))
        assertStep(Ready, go(opening.copy(viaPassword = true), GateEvent.Opened), E5)
        assertStep(Ready, go(opening.copy(viaReset = true), GateEvent.Opened), E5)
        assertStep(Locked(Resume.Ready), go(opening.copy(viaPassword = true, lockPending = true), GateEvent.Opened))
    }

    @Test fun t17_openFailedCorrupt_isCorrupt() {
        assertStep(Corrupt, go(opening, GateEvent.OpenFailed(OpenFailureKind.Corrupt)), E3)
        assertStep(Locked(Resume.Corrupt), go(opening.copy(lockPending = true), GateEvent.OpenFailed(OpenFailureKind.Corrupt)), E3)
    }

    @Test fun t18_transientFailureAfterAPassword_returnsToThePasswordScreen_withCountersCleared() {
        val state = Opening(true, false, false, reason = PasswordReason.Reminder, fingerprintUsable = false)
        assertStep(NeedsPassword(0, 0, couldNotOpen = true, reason = PasswordReason.Reminder), go(state, GateEvent.OpenFailed(OpenFailureKind.Transient)), E3)
    }

    @Test fun t19_transientFailureOtherwise_showsTryAgain() =
        assertStep(opening.copy(transientError = true), go(opening, GateEvent.OpenFailed(OpenFailureKind.Transient)), E3)

    @Test fun t20_retry_opensAgain_withoutTheResetKey() {
        assertStep(opening, go(opening.copy(transientError = true), GateEvent.Retry), E2)
        assertStep(opening, go(opening.copy(transientError = true, viaReset = true), GateEvent.Retry), E2)
        assertIgnored(opening, GateEvent.Retry)
    }

    @Test fun t21_passwordRejected_returnsToTheSameScreenVariant() {
        val state = Opening(true, false, false, reason = PasswordReason.None, fingerprintUsable = true)
        assertStep(NeedsPassword(5, 30_000, false, fingerprintUsable = true), go(state, GateEvent.PasswordRejected(5, 30_000)))
    }

    @Test fun t22_passwordNeeded_showsThePasswordScreenWithTheReason() =
        assertStep(NeedsPassword(1, 0, false, PasswordReason.FingerprintChanged), go(opening.copy(viaSlot = true), GateEvent.PasswordNeeded(1, 0, PasswordReason.FingerprintChanged)))

    @Test fun t23_keyFailed_isKeyUnavailable() {
        assertStep(KeyUnavailable, go(opening, GateEvent.KeyFailed))
        assertStep(Locked(Resume.KeyUnavailable), go(opening.copy(lockPending = true), GateEvent.KeyFailed))
    }

    @Test fun t24_lockWhileOpening_isRemembered() = assertStep(opening.copy(lockPending = true), go(opening, GateEvent.LockNow))

    @Test fun t25_lockInReady_keepsRoomOpen() = assertStep(Locked(Resume.Ready), go(Ready, GateEvent.LockNow))

    @Test fun t26_runtimeCorruptionInReady_isCorrupt() = assertStep(Corrupt, go(Ready, GateEvent.CorruptAtRuntime), E3)

    @Test fun t27_maintenanceFromReady() =
        assertStep(maintenance(MaintenanceOp.Backup, Origin.Ready), go(Ready, GateEvent.MaintenanceStarted(MaintenanceOp.Backup)))

    @Test fun t28a_reopened_isReady_withE5OnlyAfterAKeyChange() {
        assertStep(Ready, go(maintenance(MaintenanceOp.KeyChange, Origin.Ready), finished(MaintenanceOutcome.Reopened)), E5)
        assertStep(Ready, go(maintenance(MaintenanceOp.Restore, Origin.Ready), finished(MaintenanceOutcome.Reopened)))
        assertStep(Ready, go(maintenance(MaintenanceOp.Backup, Origin.Ready), finished(MaintenanceOutcome.Reopened)))
    }

    @Test fun t28b_reopenedWithCorruptionPending_isCorrupt() =
        assertStep(Corrupt, go(maintenance(MaintenanceOp.Backup, Origin.Ready).copy(corruptPending = true), finished(MaintenanceOutcome.Reopened)), E3)

    @Test fun t29a_reopenFailedCorrupt() =
        assertStep(Corrupt, go(maintenance(MaintenanceOp.Backup, Origin.Ready), finished(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt))), E3)

    @Test fun t29b_reopenFailedTransient_showsTryAgain() =
        assertStep(opening.copy(transientError = true), go(maintenance(MaintenanceOp.KeyChange, Origin.Ready), finished(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Transient))), E3)

    @Test fun t30_restart_isAuthenticatedStart() =
        assertStep(Starting(true), go(maintenance(MaintenanceOp.Restore, Origin.Corrupt), finished(MaintenanceOutcome.Restart)), E1)

    @Test fun t31a_abortedFromReady_isReady_orCorruptWhenPending() {
        assertStep(Ready, go(maintenance(MaintenanceOp.Backup, Origin.Ready), finished(MaintenanceOutcome.Aborted)))
        assertStep(Corrupt, go(maintenance(MaintenanceOp.Backup, Origin.Ready).copy(corruptPending = true), finished(MaintenanceOutcome.Aborted)), E3)
    }

    @Test fun t31b_abortedFromCorrupt() = assertStep(Corrupt, go(maintenance(MaintenanceOp.Restore, Origin.Corrupt), finished(MaintenanceOutcome.Aborted)))

    @Test fun t31c_abortedFromKeyUnavailable() =
        assertStep(KeyUnavailable, go(maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable), finished(MaintenanceOutcome.Aborted)))

    @Test fun t31d_eraseAbortedFromNeedsPassword_startsAgain() =
        assertStep(Starting(false), go(maintenance(MaintenanceOp.Erase, Origin.NeedsPassword), finished(MaintenanceOutcome.Aborted)), E1)

    @Test fun t32_lockDuringAnOperation_locksOverIt_exceptAnEraseFromNeedsPassword() {
        val m = maintenance(MaintenanceOp.Restore, Origin.Corrupt)
        assertStep(lockedM(m), go(m, GateEvent.LockNow))
        assertIgnored(maintenance(MaintenanceOp.Erase, Origin.NeedsPassword), GateEvent.LockNow)
    }

    @Test fun t33_recoveryOperations_startFromCorruptAndKeyUnavailable() {
        assertStep(maintenance(MaintenanceOp.Restore, Origin.Corrupt), go(Corrupt, GateEvent.MaintenanceStarted(MaintenanceOp.Restore)))
        assertStep(maintenance(MaintenanceOp.StartFresh, Origin.KeyUnavailable), go(KeyUnavailable, GateEvent.MaintenanceStarted(MaintenanceOp.StartFresh)))
        assertIgnored(Corrupt, GateEvent.MaintenanceStarted(MaintenanceOp.Backup))
    }

    @Test fun t34_lockOnARecoveryScreen() {
        assertStep(Locked(Resume.Corrupt), go(Corrupt, GateEvent.LockNow))
        assertStep(Locked(Resume.KeyUnavailable), go(KeyUnavailable, GateEvent.LockNow))
    }

    @Test fun t35_unmatchedPairs_areIgnored() {
        assertIgnored(NeedsPassword(0, 0, false), GateEvent.LockNow)
        assertIgnored(Locked(Resume.Ready, password = entry), GateEvent.LockNow)
        assertIgnored(Starting(false), GateEvent.LockNow)
        assertIgnored(Ready, GateEvent.Opened)
        assertIgnored(Corrupt, GateEvent.KekFailedWithSlot)
        assertIgnored(Locked(Resume.Ready), GateEvent.SlotUnlocked(cipher))
    }

    @Test fun t36_lockWhileStartingAuthenticated_withdrawsTheExemption() =
        assertStep(Starting(false), go(Starting(true), GateEvent.LockNow))

    @Test fun t37_runtimeCorruptionWhileOpening_isCorrupt() =
        assertStep(Corrupt, go(opening, GateEvent.CorruptAtRuntime), E3)

    @Test fun t38_runtimeCorruptionDuringAnOperation_isPending() {
        val m = maintenance(MaintenanceOp.Backup, Origin.Ready)
        assertStep(m.copy(corruptPending = true), go(m, GateEvent.CorruptAtRuntime))
    }

    @Test fun t39_runtimeCorruptionDuringALockedOperation_isPending() {
        val m = maintenance(MaintenanceOp.Backup, Origin.Ready)
        assertStep(lockedM(m.copy(corruptPending = true)), go(lockedM(m), GateEvent.CorruptAtRuntime))
    }

    @Test fun t40_passwordMode_slotRoute_locksOpen() =
        assertStep(Locked(Resume.Open), go(Starting(false), loaded(KeyMode.PASSWORD, true, UnlockRoute.Slot)))

    @Test fun t41_fingerprintResetFromNeedsPassword_keepsThePasswordScreen() {
        val state = NeedsPassword(4, 77, couldNotOpen = true, reason = PasswordReason.Reminder, fingerprintUsable = false)
        val reset = PasswordEntry(PasswordReason.Reminder, false, 4, 77)
        assertStep(maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = reset), go(state, GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, true)))
        // Only a KeyChange is a fingerprint reset: any other pair is unmatched (T35), as in the locked row T12.
        assertIgnored(state, GateEvent.MaintenanceStarted(MaintenanceOp.Erase, true))
        assertIgnored(state, GateEvent.MaintenanceStarted(MaintenanceOp.Restore, true))
    }

    @Test fun t42_slotUnlocked_opensViaSlot_withTheCipher() {
        val t = go(Locked(Resume.Open), GateEvent.SlotUnlocked(cipher))
        assertStep(opening.copy(viaSlot = true), t, E2_CIPHER)
        assertSame(cipher, (t.second.single() as GateEffect.UnlockAndOpen).cipher)
    }

    @Test fun t43_retryAfterAFailedSlotOpen_returnsToTheLock() =
        assertStep(Locked(Resume.Open), go(opening.copy(viaSlot = true, transientError = true), GateEvent.Retry))

    @Test fun t44_slotUnlockFailed_locksOpenWithTheSlotError() {
        val t = go(opening.copy(viaSlot = true), GateEvent.SlotUnlockFailed)
        val locked = t.first as Locked
        assertEquals(Resume.Open, locked.resume)
        assertEquals(LockError.SLOT_FAILED, locked.authError?.code)
        assertEquals(null, locked.authError?.message)
        assertTrue(t.second.isEmpty())
    }

    @Test fun t45_passwordRequiredAtLockedOpen_needsPassword() =
        assertStep(NeedsPassword(2, 50, false, PasswordReason.None, true), go(Locked(Resume.Open), GateEvent.PasswordRequired(PasswordReason.None, true, 2, 50)))

    @Test fun t46_passwordRequiredOverAnOpenOrRecoveringDatabase_showsThePasswordEntry() {
        val event = GateEvent.PasswordRequired(PasswordReason.FingerprintChanged, false, 1, 9)
        val expected = PasswordEntry(PasswordReason.FingerprintChanged, false, 1, 9)
        assertStep(Locked(Resume.Ready, password = expected), go(Locked(Resume.Ready, LockError(7, "x")), event))
        assertStep(Locked(Resume.Corrupt, password = expected), go(Locked(Resume.Corrupt), event))
        val m = maintenance(MaintenanceOp.Backup, Origin.Ready)
        assertStep(Locked(Resume.Maintenance(m), password = expected), go(lockedM(m), event))
        assertIgnored(Locked(Resume.KeyUnavailable), event)
        assertIgnored(Locked(Resume.Ready, password = checking), event)
        assertIgnored(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)), event)
    }

    @Test fun t47_passwordSubmittedOnTheEntry_checksIt_andClearsCheckFailed() {
        val p = entry.copy(checkFailed = true, retryAt = 500)
        val password = CharArray(8) { 'q' }
        val t = go(Locked(Resume.Ready, password = p), GateEvent.PasswordSubmitted(password), now = 500)
        assertStep(Locked(Resume.Ready, password = p.copy(checking = true, checkFailed = false)), t, E4)
        assertSame(password, (t.second.single() as GateEffect.VerifyPassword).password)
        assertIgnored(Locked(Resume.Ready, password = p), GateEvent.PasswordSubmitted(password), now = 499)
        assertIgnored(Locked(Resume.Ready, password = checking), GateEvent.PasswordSubmitted(password))
        assertIgnored(lockedM(maintenance(MaintenanceOp.Backup, Origin.Ready)).copy(password = entry), GateEvent.PasswordSubmitted(password))
    }

    @Test fun t48_passwordVerified_returnsToTheResume_withE5OverReady() {
        assertStep(Ready, go(Locked(Resume.Ready, password = checking), GateEvent.PasswordVerified), E5)
        assertStep(Corrupt, go(Locked(Resume.Corrupt, password = checking), GateEvent.PasswordVerified))
        assertIgnored(Locked(Resume.Ready, password = entry), GateEvent.PasswordVerified)
    }

    @Test fun t49_passwordRejectedOnTheEntry_updatesTheCounters() =
        assertStep(Locked(Resume.Ready, password = checking.copy(failures = 6, retryAt = 60_000, checking = false)), go(Locked(Resume.Ready, password = checking), GateEvent.PasswordRejected(6, 60_000)))

    @Test fun t50_keyFailedOnTheEntry_showsTheRetryLine_andClosesNothing() =
        assertStep(Locked(Resume.Ready, password = checking.copy(checking = false, checkFailed = true)), go(Locked(Resume.Ready, password = checking), GateEvent.KeyFailed))

    @Test fun t51_useFingerprintOnNeedsPassword_locksOpen() {
        assertStep(Locked(Resume.Open), go(NeedsPassword(0, 0, false, fingerprintUsable = true), GateEvent.UseFingerprintChosen))
        assertIgnored(NeedsPassword(0, 0, false), GateEvent.UseFingerprintChosen)
    }

    @Test fun t52_useFingerprintOnTheEntry_dropsIt() {
        assertStep(Locked(Resume.Ready), go(Locked(Resume.Ready, password = entry), GateEvent.UseFingerprintChosen))
        assertIgnored(Locked(Resume.Ready, password = checking), GateEvent.UseFingerprintChosen)
        assertIgnored(Locked(Resume.Ready, password = keyStoreEntry), GateEvent.UseFingerprintChosen)
    }

    @Test fun t53_lockDuringAResetFromNeedsPassword_isPending() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry)
        assertStep(m.copy(lockPending = true), go(m, GateEvent.LockNow))
    }

    @Test fun t54_lockDuringALockedReset_isPending() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)
        assertStep(lockedM(m.copy(lockPending = true)), go(lockedM(m), GateEvent.LockNow))
    }

    @Test fun t55_openWithNewKey_opensViaReset_afterE6() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry)
        assertStep(opening.copy(viaReset = true), go(m, finished(MaintenanceOutcome.OpenWithNewKey)), E6, E2_RESET)
        assertStep(opening.copy(viaReset = true, lockPending = true), go(m.copy(lockPending = true), finished(MaintenanceOutcome.OpenWithNewKey)), E6, E2_RESET)
    }

    @Test fun t56_resetAbortedFromNeedsPassword_rebuildsTheSameScreen_withoutE1() {
        val e = PasswordEntry(PasswordReason.Reminder, false, 3, 40)
        assertStep(NeedsPassword(3, 40, false, PasswordReason.Reminder, false), go(maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = e), finished(MaintenanceOutcome.Aborted)))
    }

    @Test fun t57_lockedResetReopened_isReady_withE6AndE5_orLockedWithE6Only() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)
        assertStep(Ready, go(lockedM(m), finished(MaintenanceOutcome.Reopened)), E6, E5)
        assertStep(Locked(Resume.Ready), go(lockedM(m.copy(lockPending = true)), finished(MaintenanceOutcome.Reopened)), E6)
    }

    @Test fun t58_lockedResetAborted_returnsToItsPasswordEntry_orCorrupt() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)
        assertStep(Locked(Resume.Ready, password = entry), go(lockedM(m), finished(MaintenanceOutcome.Aborted)))
        assertStep(Locked(Resume.Corrupt), go(lockedM(m.copy(corruptPending = true)), finished(MaintenanceOutcome.Aborted)), E3)
    }

    @Test fun t59_kekFailedWithSlotAfterAColdStartPassword_locksOpen_withoutClosing() {
        assertStep(Locked(Resume.Open), go(opening.copy(viaPassword = true), GateEvent.KekFailedWithSlot))
        assertIgnored(opening, GateEvent.KekFailedWithSlot)
    }

    @Test fun t60_kekFailedWithSlotOnTheEntry_dropsIt() {
        assertStep(Locked(Resume.Ready), go(Locked(Resume.Ready, password = checking), GateEvent.KekFailedWithSlot))
        assertIgnored(Locked(Resume.Ready, password = entry), GateEvent.KekFailedWithSlot)
    }

    @Test fun t61_recheckOnTheKeyStoreScreen_setsFingerprintUsable() {
        val state = NeedsPassword(1, 0, false, PasswordReason.KeyStoreFailed)
        assertStep(state.copy(fingerprintUsable = true), go(state, GateEvent.FingerprintRechecked(true)))
        assertStep(state, go(state.copy(fingerprintUsable = true), GateEvent.FingerprintRechecked(false)))
    }

    @Test fun t62_recheckOnTheKeyStoreEntry_setsFingerprintUsable() {
        for (resume in listOf(Resume.Ready, Resume.Corrupt)) {
            assertStep(Locked(resume, password = keyStoreEntry.copy(fingerprintUsable = true)), go(Locked(resume, password = keyStoreEntry), GateEvent.FingerprintRechecked(true)))
        }
    }

    // Meta-test and properties of the whole table

    @Test fun everyRowOfTheTable_hasItsTest() {
        val rows = (1..62).flatMap { n ->
            when (n) {
                14 -> listOf("14a", "14b", "14c", "14d", "14e")
                28 -> listOf("28a", "28b")
                29 -> listOf("29a", "29b")
                31 -> listOf("31a", "31b", "31c", "31d")
                else -> listOf(n.toString().padStart(2, '0'))
            }
        }
        val names = javaClass.declaredMethods.map { it.name }
        val missing = rows.filter { row -> names.none { it.startsWith("t${row}_") } }
        assertTrue("rows without a test: $missing", missing.isEmpty())
    }

    @Test fun everyPair_isTotal_andE6RunsOnlyAtT55AndT57() {
        for (state in GateSamples.states) for (event in GateSamples.events(cipher)) {
            val (_, effects) = go(state, event, now = Long.MAX_VALUE)
            if (GateEffect.ClearKekBroken in effects) {
                val m = when (state) {
                    is Locked -> (state.resume as? Resume.Maintenance)?.state
                    is GateState.Maintenance -> state
                    else -> null
                }
                assertTrue("E6 outside T55/T57 for $state + $event", m?.resetFrom != null && event is GateEvent.MaintenanceFinished)
            }
        }
    }

    @Test fun lockNow_inEveryState_givesTheRowsTransition() {
        for (state in GateSamples.states) {
            val next = go(state, GateEvent.LockNow).first
            val expected = when {
                state == Starting(true) -> Starting(false)
                state is Opening -> state.copy(lockPending = true)
                state == Ready -> Locked(Resume.Ready)
                state == Corrupt -> Locked(Resume.Corrupt)
                state == KeyUnavailable -> Locked(Resume.KeyUnavailable)
                state is GateState.Maintenance && state.origin != Origin.NeedsPassword -> lockedM(state)
                state is GateState.Maintenance && state.resetFrom != null -> state.copy(lockPending = true)
                state is Locked && state.resume is Resume.Maintenance && (state.resume as Resume.Maintenance).state.resetFrom != null ->
                    lockedM((state.resume as Resume.Maintenance).state.copy(lockPending = true))
                else -> state // T35: NeedsPassword, Locked, Starting(false), an Erase from NeedsPassword
            }
            assertEquals("LockNow in $state", expected, next)
        }
    }

    // Sequences (L-18)

    private fun run(start: GateState, vararg events: GateEvent): Transition =
        events.fold(start to emptyList<GateEffect>()) { (state, _), event -> go(state, event) }

    @Test fun lockPendingTransientRetry_endsLocked() =
        assertEquals(Locked(Resume.Ready), run(opening, GateEvent.LockNow, GateEvent.OpenFailed(OpenFailureKind.Transient), GateEvent.Retry, GateEvent.Opened).first)

    @Test fun maintenanceCorruptionThenReopened_endsCorrupt() =
        assertEquals(Corrupt, run(maintenance(MaintenanceOp.Backup, Origin.Ready), GateEvent.CorruptAtRuntime, finished(MaintenanceOutcome.Reopened)).first)

    @Test fun lockedMaintenanceCorruptionThenReopened_endsLockedCorrupt() =
        assertEquals(Locked(Resume.Corrupt), run(lockedM(maintenance(MaintenanceOp.Backup, Origin.Ready)), GateEvent.CorruptAtRuntime, finished(MaintenanceOutcome.Reopened)).first)

    @Test fun passwordModeFingerprintFirst() {
        val states = listOf(loaded(KeyMode.PASSWORD, true, UnlockRoute.Slot), GateEvent.SlotUnlocked(cipher), GateEvent.Opened)
            .runningFold(Starting(false) as GateState) { s, e -> go(s, e).first }
        assertEquals(listOf(Starting(false), Locked(Resume.Open), opening.copy(viaSlot = true), Ready), states)
        assertTrue(states.none { it is NeedsPassword })
    }

    @Test fun passwordModeWithoutFingerprint() {
        for (route in listOf(UnlockRoute.Password(PasswordReason.None, false), UnlockRoute.Password(PasswordReason.FingerprintChanged, false))) {
            for (lock in listOf(true, false)) {
                val next = go(Starting(false), loaded(KeyMode.PASSWORD, lock, route)).first
                assertTrue(next is NeedsPassword)
                assertEquals(route.reason, (next as NeedsPassword).reason)
            }
        }
        // A slot found unusable at prompt time goes through Locked(Open) and T45.
        assertEquals(NeedsPassword(0, 0, false, PasswordReason.FingerprintChanged), run(Starting(false), loaded(KeyMode.PASSWORD, true, UnlockRoute.Slot), GateEvent.PasswordRequired(PasswordReason.FingerprintChanged, false, 0, 0)).first)
    }

    @Test fun passwordModeReminderDue() {
        val (next, effects) = go(Starting(false), loaded(KeyMode.PASSWORD, true, UnlockRoute.Password(PasswordReason.Reminder, false)))
        assertEquals(NeedsPassword(0, 0, false, PasswordReason.Reminder), next)
        assertTrue(effects.isEmpty())
        assertEquals(LockScreen.Password, lockScreenOf(next)) // never prompts
    }

    @Test fun passwordModeUsePassword() {
        val usePassword = GateEvent.PasswordRequired(PasswordReason.None, true, 0, 0)
        assertEquals(NeedsPassword(0, 0, false, fingerprintUsable = true), go(Locked(Resume.Open), usePassword).first)
        val back = run(Ready, GateEvent.LockNow, usePassword).first
        assertEquals(Locked(Resume.Ready, password = PasswordEntry(PasswordReason.None, true, 0, 0)), back)
    }

    @Test fun resetFromColdStart() {
        val t = run(NeedsPassword(0, 0, false, fingerprintUsable = true), GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, true), finished(MaintenanceOutcome.OpenWithNewKey), GateEvent.Opened)
        assertStep(Ready, t, E5)
    }

    @Test fun resetFromLock() {
        val t = run(Locked(Resume.Ready, password = entry), GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, true), finished(MaintenanceOutcome.Reopened))
        assertStep(Ready, t, E6, E5)
    }

    @Test fun resetFailsColdStart() {
        val start = NeedsPassword(2, 0, false, PasswordReason.Reminder)
        assertStep(start, run(start, GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, true), finished(MaintenanceOutcome.Aborted)))
    }

    @Test fun resetFailsLocked() =
        assertStep(Locked(Resume.Ready, password = entry), run(Locked(Resume.Ready, password = entry), GateEvent.MaintenanceStarted(MaintenanceOp.KeyChange, true), finished(MaintenanceOutcome.Aborted)))

    @Test fun resetThenTransientRetry_asksTheNewPassword_withoutE5() {
        val m = maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry)
        val t = run(m, finished(MaintenanceOutcome.OpenWithNewKey), GateEvent.OpenFailed(OpenFailureKind.Transient), GateEvent.Retry, GateEvent.PasswordNeeded(0, 0, PasswordReason.None))
        assertStep(NeedsPassword(0, 0, false), t)
    }

    @Test fun resetRows_doNotMatchT14aT14eT31d() {
        val locked = lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry))
        assertTrue(go(locked, finished(MaintenanceOutcome.Reopened)).first == Ready) // T57, not T14a
        assertTrue(go(locked, finished(MaintenanceOutcome.Aborted)).first == Locked(Resume.Ready, password = entry)) // T58, not T14e
        assertTrue(go(maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry), finished(MaintenanceOutcome.Aborted)).first is NeedsPassword) // T56, not T31d
    }

    // lockScreenOf and showsContent

    @Test fun lockScreenOf_coversEveryGateScreen() {
        assertEquals(LockScreen.Content, lockScreenOf(Ready))
        assertEquals(LockScreen.Password, lockScreenOf(NeedsPassword(0, 0, false)))
        assertEquals(LockScreen.Password, lockScreenOf(Locked(Resume.Ready, password = entry)))
        assertEquals(LockScreen.PromptToOpen, lockScreenOf(Locked(Resume.Open)))
        assertEquals(LockScreen.Prompt, lockScreenOf(Locked(Resume.Ready)))
        assertEquals(LockScreen.Prompt, lockScreenOf(Locked(Resume.Corrupt)))
        assertEquals(LockScreen.Prompt, lockScreenOf(lockedM(maintenance(MaintenanceOp.Backup, Origin.Ready))))
        assertEquals(LockScreen.PromptWithoutKey, lockScreenOf(Locked(Resume.KeyUnavailable)))
        assertEquals(LockScreen.PromptWithoutKey, lockScreenOf(lockedM(maintenance(MaintenanceOp.Restore, Origin.KeyUnavailable))))
        assertEquals(LockScreen.PromptWithoutKey, lockScreenOf(lockedM(maintenance(MaintenanceOp.Erase, Origin.Ready))))
        assertEquals(LockScreen.PromptWithoutKey, lockScreenOf(lockedM(maintenance(MaintenanceOp.Erase, Origin.Corrupt))))
        assertEquals(LockScreen.Other, lockScreenOf(lockedM(maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry))))
        for (other in listOf(Starting(false), opening, Corrupt, KeyUnavailable, maintenance(MaintenanceOp.Backup, Origin.Ready))) {
            assertEquals(LockScreen.Other, lockScreenOf(other))
        }
    }

    @Test fun showsContent_onlyInReady_andMaintenanceFromReadyExceptErase() {
        assertTrue(Ready.showsContent())
        assertTrue(maintenance(MaintenanceOp.Backup, Origin.Ready).showsContent())
        assertFalse(maintenance(MaintenanceOp.Erase, Origin.Ready).showsContent())
        assertFalse(maintenance(MaintenanceOp.Restore, Origin.Corrupt).showsContent())
        assertFalse(Locked(Resume.Ready, password = entry).showsContent())
    }

    // L-53 (a): the re-check never changes another reason or a running check

    @Test fun fingerprintRechecked_isDroppedOutsideTheKeyStoreScreens() {
        val dropped = listOf(
            NeedsPassword(0, 0, false, PasswordReason.None, fingerprintUsable = true),
            NeedsPassword(0, 0, false, PasswordReason.Reminder),
            NeedsPassword(0, 0, false, PasswordReason.FingerprintChanged),
            Locked(Resume.Ready, password = entry),
            Locked(Resume.Ready, password = keyStoreEntry.copy(checking = true)),
            Locked(Resume.Maintenance(maintenance(MaintenanceOp.Backup, Origin.Ready)), password = keyStoreEntry),
            Ready, Locked(Resume.Open), Corrupt,
        )
        for (state in dropped) {
            assertIgnored(state, GateEvent.FingerprintRechecked(false))
            assertIgnored(state, GateEvent.FingerprintRechecked(true))
        }
    }

    // State and event texts never carry a password or a cipher (DEC-70)

    @Test fun toString_redactsPasswordsAndCiphers() {
        val password = CharArray(8) { 'S' }
        assertEquals("PasswordSubmitted(<redacted>)", GateEvent.PasswordSubmitted(password).toString())
        assertEquals("SlotUnlocked", GateEvent.SlotUnlocked(cipher).toString())
        assertFalse(GateEffect.VerifyPassword(password).toString().contains("SSSS"))
        assertFalse(GateEffect.UnlockAndOpen(password = password).toString().contains("SSSS"))
    }

    private companion object {
        const val E1 = "RunStartup"
        const val E2 = "UnlockAndOpen(password=false, cipher=false, resetKey=false)"
        const val E2_PASSWORD = "UnlockAndOpen(password=true, cipher=false, resetKey=false)"
        const val E2_CIPHER = "UnlockAndOpen(password=false, cipher=true, resetKey=false)"
        const val E2_RESET = "UnlockAndOpen(password=false, cipher=false, resetKey=true)"
        const val E3 = "CloseDatabase"
        const val E4 = "VerifyPassword(<redacted>)"
        const val E5 = "OfferSlotSetup"
        const val E6 = "ClearKekBroken"
    }
}

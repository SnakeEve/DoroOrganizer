package com.doro.organizer.ui.gate

import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.session.GateState
import com.doro.organizer.session.Origin
import com.doro.organizer.session.PasswordEntry
import com.doro.organizer.session.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which gate state shows which password screen and Forgot password screen (ui.md § 10.1–§ 10.3, § 10.5). */
class GateRoutingTest {
    private val entry = PasswordEntry(PasswordReason.Reminder, fingerprintUsable = true, failures = 3, retryAt = 42)
    private val backup = GateState.Maintenance(MaintenanceOp.Backup, Origin.Ready)
    private val reset = GateState.Maintenance(MaintenanceOp.KeyChange, Origin.Ready, resetFrom = entry)

    @Test
    fun needsPassword_showsItsFields() {
        val gate = GateState.NeedsPassword(
            5, 99, couldNotOpen = true, reason = PasswordReason.KeyStoreFailed, fingerprintUsable = true,
        )

        assertEquals(
            PasswordScreenModel(PasswordReason.KeyStoreFailed, true, 5, 99, couldNotOpen = true),
            passwordScreenOf(gate),
        )
    }

    @Test
    fun lockedPasswordEntry_isBusyWhileChecking_andShowsACheckFailure() {
        val checking = GateState.Locked(Resume.Ready, password = entry.copy(checking = true))
        val failed = GateState.Locked(Resume.Corrupt, password = entry.copy(checkFailed = true))

        assertEquals(PasswordScreenModel(PasswordReason.Reminder, true, 3, 42, busy = true), passwordScreenOf(checking))
        assertEquals(
            PasswordScreenModel(PasswordReason.Reminder, true, 3, 42, checkFailed = true),
            passwordScreenOf(failed),
        )
    }

    @Test
    fun anUncountedAttempt_showsTheStorageLine_onBothPasswordScreens() {
        val needs = GateState.NeedsPassword(2, 0, couldNotOpen = false, storageFull = true)
        val locked = GateState.Locked(Resume.Ready, password = entry.copy(storageFull = true))

        assertEquals(PasswordScreenModel(PasswordReason.None, false, 2, 0, storageFull = true), passwordScreenOf(needs))
        assertEquals(PasswordScreenModel(PasswordReason.Reminder, true, 3, 42, storageFull = true), passwordScreenOf(locked))
    }

    @Test
    fun lockedPasswordEntry_overARunningOperation_carriesItsOp() {
        val gate = GateState.Locked(Resume.Maintenance(backup), password = entry)

        assertEquals(MaintenanceOp.Backup, passwordScreenOf(gate)?.maintenance)
    }

    @Test
    fun openingViaPassword_isTheBusyPasswordScreen_untilATransientError() {
        val opening = GateState.Opening(
            viaPassword = true, lockPending = false, transientError = false,
            reason = PasswordReason.FingerprintChanged,
        )

        assertEquals(
            PasswordScreenModel(PasswordReason.FingerprintChanged, false, 0, 0, busy = true),
            passwordScreenOf(opening),
        )
        assertNull(passwordScreenOf(opening.copy(transientError = true)))
    }

    @Test
    fun noOtherState_showsThePasswordScreen() {
        val others = listOf(
            GateState.Starting(false), GateState.Locked(Resume.Ready), GateState.Locked(Resume.Open),
            GateState.Opening(viaPassword = false, lockPending = false, transientError = false, viaSlot = true),
            GateState.Ready, backup, GateState.Corrupt, GateState.KeyUnavailable,
        )

        others.forEach { assertNull(it.toString(), passwordScreenOf(it)) }
    }

    @Test
    fun eraseOrigin_followsThePasswordScreenItStartsFrom() {
        assertEquals(MaintenanceOrigin.NeedsPassword, eraseOriginOf(GateState.NeedsPassword(0, 0, false)))
        assertEquals(MaintenanceOrigin.Ready, eraseOriginOf(GateState.Locked(Resume.Ready, password = entry)))
        assertEquals(MaintenanceOrigin.Corrupt, eraseOriginOf(GateState.Locked(Resume.Corrupt, password = entry)))
    }

    @Test
    fun eraseOrigin_isNull_withoutAPasswordScreen_orOverARunningOperation() {
        assertNull(eraseOriginOf(GateState.Locked(Resume.Ready)))
        assertNull(eraseOriginOf(GateState.Locked(Resume.Maintenance(backup), password = entry)))
        assertNull(eraseOriginOf(GateState.Corrupt))
        assertNull(eraseOriginOf(GateState.Opening(viaPassword = true, lockPending = false, transientError = false)))
    }

    @Test
    fun resetOrigin_neverOverCorrupt() {
        assertEquals(MaintenanceOrigin.NeedsPassword, resetOriginOf(GateState.NeedsPassword(0, 0, false)))
        assertEquals(MaintenanceOrigin.Ready, resetOriginOf(GateState.Locked(Resume.Ready, password = entry)))
        assertNull(resetOriginOf(GateState.Locked(Resume.Corrupt, password = entry)))
    }

    @Test
    fun forgotScreen_survivesAFailedErase_andThePasswordScreens() {
        val kept = listOf(
            GateState.NeedsPassword(0, 0, false),
            GateState.Maintenance(MaintenanceOp.Erase, Origin.NeedsPassword),
            GateState.Maintenance(MaintenanceOp.Erase, Origin.Ready),
            GateState.Starting(authenticated = true),
            GateState.Locked(Resume.Ready, password = entry),
            GateState.Locked(Resume.Corrupt, password = entry),
        )

        kept.forEach { assertFalse(it.toString(), it.closesForgotScreen()) }
    }

    @Test
    fun forgotScreen_closesOnEveryStateThatLeavesThePasswordScreens() {
        val closing = listOf(
            GateState.Opening(viaPassword = true, lockPending = false, transientError = false),
            GateState.Ready, GateState.Corrupt, GateState.KeyUnavailable,
            GateState.Locked(Resume.Ready), GateState.Locked(Resume.Maintenance(reset)),
            GateState.Maintenance(MaintenanceOp.KeyChange, Origin.NeedsPassword, resetFrom = entry),
        )

        closing.forEach { assertTrue(it.toString(), it.closesForgotScreen()) }
    }

    @Test
    fun passwordScreenModel_printsNoSecret() {
        val text = PasswordScreenModel(PasswordReason.None, false, 1, 2).toString()

        assertEquals(
            "PasswordScreenModel(reason=None, fingerprintUsable=false, failures=1, retryAt=2, couldNotOpen=false, " +
                "busy=false, checkFailed=false, maintenance=null, storageFull=false)",
            text,
        )
    }
}

package com.doro.organizer.security

import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.model.UnlockRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md L-42 and L-52 (a): `UnlockPolicy` (security.md § 5.1, § 5.5; DEC-62, DEC-65, DEC-74, DEC-86). The
 * tables of § 5.1 and § 5.5 cell by cell, then sweeps over every input combination for the rules that hold
 * everywhere.
 */
class UnlockPolicyTest {
    private val usable = listOf(BiometricAvailability.Success, BiometricAvailability.StatusUnknown)
    private val notUsable = listOf(BiometricAvailability.NoneEnrolled, BiometricAvailability.Unavailable)

    private fun route(
        mode: KeyMode,
        coldStart: Boolean = true,
        lockOn: Boolean = true,
        deviceSecure: Boolean = true,
        biometric: BiometricAvailability = BiometricAvailability.Success,
        bio: BioSlotStatus = BioSlotStatus.Live,
        reminderDue: Boolean = false,
    ) = UnlockPolicy.route(mode, coldStart, lockOn, deviceSecure, biometric, bio, reminderDue)

    private fun forEveryInput(block: (KeyMode, Boolean, Boolean, Boolean, BiometricAvailability, BioSlotStatus, Boolean) -> Unit) {
        for (mode in KeyMode.entries) for (coldStart in listOf(true, false)) for (lockOn in listOf(true, false))
            for (secure in listOf(true, false)) for (biometric in BiometricAvailability.entries)
                for (bio in BioSlotStatus.entries) for (due in listOf(true, false))
                    block(mode, coldStart, lockOn, secure, biometric, bio, due)
    }

    // --- PLAIN and DEVICE (§ 5.1 and § 5.5, rows PLAIN and DEVICE) ---

    @Test
    fun plainAndDevice_lockOff_askNothing() {
        listOf(KeyMode.PLAIN, KeyMode.DEVICE).forEach { mode ->
            listOf(true, false).forEach { cold -> assertEquals(PolicyDecision.None, route(mode, coldStart = cold, lockOn = false)) }
        }
    }

    @Test
    fun plainAndDevice_lockOn_usableStrongBiometric_isTheStrongPromptWithoutCrypto() {
        listOf(KeyMode.PLAIN, KeyMode.DEVICE).forEach { mode ->
            usable.forEach { availability -> assertEquals(PolicyDecision.Biometric, route(mode, biometric = availability)) }
        }
    }

    @Test
    fun plainAndDevice_lockOn_noUsableStrongBiometric_isTheDeviceCredential_Q7() {
        listOf(KeyMode.PLAIN, KeyMode.DEVICE).forEach { mode ->
            notUsable.forEach { availability ->
                assertEquals(PolicyDecision.DeviceCredential, route(mode, biometric = availability))
            }
        }
    }

    @Test
    fun plainAndDevice_lockOn_withoutASecureLockScreen_isTheAutoOff() {
        listOf(KeyMode.PLAIN, KeyMode.DEVICE).forEach { mode ->
            BiometricAvailability.entries.forEach { availability ->
                assertEquals(PolicyDecision.AutoOff, route(mode, deviceSecure = false, biometric = availability))
            }
        }
    }

    // --- PASSWORD (§ 5.1 and § 5.5, row PASSWORD) ---

    @Test
    fun password_lockOff_asksThePasswordAtColdStart_andNothingOnReturn() {
        assertEquals(PolicyDecision.Password(PasswordCause.LockOff), route(KeyMode.PASSWORD, lockOn = false))
        assertEquals(PolicyDecision.None, route(KeyMode.PASSWORD, coldStart = false, lockOn = false))
    }

    @Test
    fun password_lockOn_usable_liveSlot_reminderNotDue_isTheSlot() {
        listOf(true, false).forEach { cold ->
            usable.forEach { availability ->
                assertEquals(PolicyDecision.Slot, route(KeyMode.PASSWORD, coldStart = cold, biometric = availability))
            }
        }
    }

    @Test
    fun password_lockOn_noUsableStrongBiometric_isThePasswordAlone_neverTheDeviceCredential() {
        notUsable.forEach { availability ->
            assertEquals(PolicyDecision.Password(PasswordCause.NoBiometric), route(KeyMode.PASSWORD, biometric = availability))
        }
    }

    @Test
    fun password_withoutASecureLockScreen_isThePassword_onColdStartAndReturn_neverAutoOff() {
        listOf(true, false).forEach { cold ->
            assertEquals(
                PolicyDecision.Password(PasswordCause.NoBiometric),
                route(KeyMode.PASSWORD, coldStart = cold, deviceSecure = false),
            )
        }
    }

    @Test
    fun password_noSlot_isThePassword_SlotMissing() {
        assertEquals(PolicyDecision.Password(PasswordCause.SlotMissing), route(KeyMode.PASSWORD, bio = BioSlotStatus.None))
    }

    @Test
    fun password_deadSlot_isThePassword_SlotInvalidated() {
        assertEquals(PolicyDecision.Password(PasswordCause.SlotInvalidated), route(KeyMode.PASSWORD, bio = BioSlotStatus.Dead))
    }

    @Test
    fun password_reminderDue_isThePassword_Reminder() {
        listOf(true, false).forEach { cold ->
            assertEquals(
                PolicyDecision.Password(PasswordCause.Reminder),
                route(KeyMode.PASSWORD, coldStart = cold, reminderDue = true),
            )
        }
    }

    @Test
    fun password_aMissingOrDeadSlot_winsOverADueReminder() {
        assertEquals(
            PolicyDecision.Password(PasswordCause.SlotMissing),
            route(KeyMode.PASSWORD, bio = BioSlotStatus.None, reminderDue = true),
        )
        assertEquals(
            PolicyDecision.Password(PasswordCause.SlotInvalidated),
            route(KeyMode.PASSWORD, bio = BioSlotStatus.Dead, reminderDue = true),
        )
    }

    // --- over KeyUnavailable (§ 5.1, last row) ---

    @Test
    fun overKeyUnavailable_everyModeBehavesLikePlain_neverSlotOrPassword() {
        for (lockOn in listOf(true, false)) for (secure in listOf(true, false)) for (availability in BiometricAvailability.entries) {
            val decision = UnlockPolicy.routeOverKeyUnavailable(lockOn, secure, availability)

            assertEquals(route(KeyMode.PLAIN, coldStart = false, lockOn = lockOn, deviceSecure = secure, biometric = availability), decision)
        }
        assertEquals(PolicyDecision.Biometric, UnlockPolicy.routeOverKeyUnavailable(true, true, BiometricAvailability.Success))
        assertEquals(PolicyDecision.DeviceCredential, UnlockPolicy.routeOverKeyUnavailable(true, true, BiometricAvailability.NoneEnrolled))
        assertEquals(PolicyDecision.AutoOff, UnlockPolicy.routeOverKeyUnavailable(true, false, BiometricAvailability.Success))
    }

    // --- sweeps over every input ---

    @Test
    fun everyInput_passwordModeIsNeverAutoOffBiometricOrDeviceCredential() {
        forEveryInput { mode, cold, lockOn, secure, biometric, bio, due ->
            if (mode != KeyMode.PASSWORD) return@forEveryInput
            val decision = UnlockPolicy.route(mode, cold, lockOn, secure, biometric, bio, due)
            assertTrue(
                "$decision",
                decision == PolicyDecision.None || decision == PolicyDecision.Slot || decision is PolicyDecision.Password,
            )
        }
    }

    @Test
    fun everyInput_plainAndDeviceAreNeverSlotOrPassword_andIgnoreSlotReminderAndColdStart() {
        forEveryInput { mode, cold, lockOn, secure, biometric, bio, due ->
            if (mode == KeyMode.PASSWORD) return@forEveryInput
            val decision = UnlockPolicy.route(mode, cold, lockOn, secure, biometric, bio, due)
            assertEquals(UnlockPolicy.routeOverKeyUnavailable(lockOn, secure, biometric), decision)
        }
    }

    @Test
    fun everyInput_theSlotRouteNeedsALiveSlot_aUsableBiometric_aSecureDevice_theLock_andNoDueReminder() {
        forEveryInput { mode, cold, lockOn, secure, biometric, bio, due ->
            val decision = UnlockPolicy.route(mode, cold, lockOn, secure, biometric, bio, due)
            val expected = mode == KeyMode.PASSWORD && lockOn && secure && biometric in usable &&
                bio == BioSlotStatus.Live && !due
            assertEquals("$mode $cold $lockOn $secure $biometric $bio $due", expected, decision == PolicyDecision.Slot)
        }
    }

    @Test
    fun everyInput_coldStartMattersOnlyForPasswordModeWithTheLockOff() {
        forEveryInput { mode, _, lockOn, secure, biometric, bio, due ->
            val cold = UnlockPolicy.route(mode, true, lockOn, secure, biometric, bio, due)
            val back = UnlockPolicy.route(mode, false, lockOn, secure, biometric, bio, due)
            if (mode == KeyMode.PASSWORD && !lockOn) {
                assertEquals(PolicyDecision.Password(PasswordCause.LockOff), cold)
                assertEquals(PolicyDecision.None, back)
            } else {
                assertEquals(cold, back)
            }
        }
    }

    // --- toRoute (§ 5.1 mapping table; L-52 (a)) ---

    @Test
    fun toRoute_mapsEveryCause() {
        val expected = mapOf(
            PasswordCause.LockOff to UnlockRoute.Password(PasswordReason.None, fingerprintUsable = false),
            PasswordCause.NoBiometric to UnlockRoute.Password(PasswordReason.None, fingerprintUsable = false),
            PasswordCause.SlotMissing to UnlockRoute.Password(PasswordReason.None, fingerprintUsable = false),
            PasswordCause.SlotInvalidated to UnlockRoute.Password(PasswordReason.FingerprintChanged, fingerprintUsable = false),
            PasswordCause.Reminder to UnlockRoute.Password(PasswordReason.Reminder, fingerprintUsable = false),
            PasswordCause.SlotUnavailable to UnlockRoute.Password(PasswordReason.None, fingerprintUsable = true),
            PasswordCause.UserChose to UnlockRoute.Password(PasswordReason.None, fingerprintUsable = true),
        )

        assertEquals(PasswordCause.entries.toSet(), expected.keys)
        expected.forEach { (cause, route) -> assertEquals("$cause", route, UnlockPolicy.toRoute(PolicyDecision.Password(cause))) }
    }

    @Test
    fun toRoute_everyDecisionButSlotAndPassword_isTheBiometricRoute() {
        assertEquals(UnlockRoute.Slot, UnlockPolicy.toRoute(PolicyDecision.Slot))
        listOf(PolicyDecision.None, PolicyDecision.AutoOff, PolicyDecision.Biometric, PolicyDecision.DeviceCredential).forEach {
            assertEquals("$it", UnlockRoute.Biometric, UnlockPolicy.toRoute(it))
        }
    }

    @Test
    fun toRoute_noBiometricWithAFailedKekAndALiveSlot_isKeyStoreFailed() {
        assertEquals(
            UnlockRoute.Password(PasswordReason.KeyStoreFailed, fingerprintUsable = false),
            UnlockPolicy.toRoute(PolicyDecision.Password(PasswordCause.NoBiometric), kekFailedWithLiveSlot = true),
        )
    }

    @Test
    fun toRoute_theKekFlagChangesNoOtherDecision() {
        val others = PasswordCause.entries.filter { it != PasswordCause.NoBiometric }.map { PolicyDecision.Password(it) } +
            listOf(PolicyDecision.None, PolicyDecision.AutoOff, PolicyDecision.Biometric, PolicyDecision.DeviceCredential, PolicyDecision.Slot)

        others.forEach { decision ->
            assertEquals("$decision", UnlockPolicy.toRoute(decision), UnlockPolicy.toRoute(decision, kekFailedWithLiveSlot = true))
        }
    }

    @Test
    fun theRouteOfEveryInput_neverOffersUseFingerprint() {
        // fingerprintUsable comes only from causes that route never produces (§ 5.1): SlotUnavailable and UserChose.
        forEveryInput { mode, cold, lockOn, secure, biometric, bio, due ->
            val routed = UnlockPolicy.toRoute(UnlockPolicy.route(mode, cold, lockOn, secure, biometric, bio, due))
            assertTrue("$routed", routed !is UnlockRoute.Password || !routed.fingerprintUsable)
        }
    }
}

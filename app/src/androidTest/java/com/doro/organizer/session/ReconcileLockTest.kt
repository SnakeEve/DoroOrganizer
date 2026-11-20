package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.SlotCipherResult
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.security.ToggleOutcome
import com.doro.organizer.testing.GateTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** L-46 (a)–(f): leftovers of the biometric slot are cleaned up after a verified password (DEC-09, DEC-65, DEC-80). */
@RunWith(AndroidJUnit4::class)
class ReconcileLockTest : GateTest() {
    private val lock get() = app.container.appLockController

    /** A cold start through the password, then its E5 (`reconcileLock()` and the offer). */
    private suspend fun passwordUnlock() {
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(1)
    }

    private suspend fun slotStatus() = app.container.keyVault.biometricSlotStatus()

    /** A trip to the background, the password over the open database (T46, T48), then its E5. */
    private suspend fun returnWithPassword(reason: PasswordReason, offerSetups: Int) {
        scene.backgroundTrip()
        val locked = scene.awaitGate { it is GateState.Locked && it.password != null } as GateState.Locked
        assertEquals(reason, locked.password?.reason)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(offerSetups)
    }

    @Test
    fun a_lockOffWithAStaleSlot_theNextPasswordUnlockRemovesIt() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.settings.setBiometricLock(false) // a crash between the setting and the removal (CS-31)
        passwordUnlock()
        assertEquals(BioSlotStatus.None, slotStatus())
        assertFalse(vaults.bioKeyExists())
        assertEquals(null, lock.slotOffer.value)
    }

    @Test
    fun b_clearedSettings_withALiveSlot_askThePassword_thenNoSlotRemains() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.settings.resetSecurity() // the corrupt DataStore read as defaults: lock off, reminder due
        assertTrue("the reminder reads due", app.container.keyVault.isPasswordReminderDue())
        passwordUnlock()
        assertEquals(BioSlotStatus.None, slotStatus())
        assertEquals(0, scene.gateway.prompts.size)
    }

    @Test
    fun c_deadSlot_showsFingerprintChanged_atEveryPasswordUnlock_untilTheOfferSucceeds() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.deleteBioAliases()
        scene.coldStart()
        val state = scene.awaitGate { it is GateState.NeedsPassword } as GateState.NeedsPassword
        assertEquals(PasswordReason.FingerprintChanged, state.reason)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(1)
        assertEquals(SlotOffer.Invalidated, lock.slotOffer.value)
        lock.dismissSlotOffer()
        returnWithPassword(PasswordReason.FingerprintChanged, offerSetups = 2)
        assertEquals(SlotOffer.Invalidated, lock.slotOffer.value)
        scene.gateway.succeed()
        assertEquals("the slot is re-created over the dead one", AppResult.Ok(true), lock.createSlot())
        assertEquals(BioSlotStatus.Live, slotStatus())
        scene.gateway.succeed()
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked }
        scene.awaitGate(GateState.Ready)
        assertTrue("the next return uses the slot prompt", scene.gateway.prompts.last().withCrypto)
    }

    @Test
    fun d_anOrphanBiometricKeyWithoutASlot_changesNoRoute() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = false)
        // Preparing a prompt's cipher creates the key; a creation that never committed leaves it orphaned (CS-29).
        assertTrue(app.container.keyVault.biometricArmCipher() is SlotCipherResult.Ready)
        assertTrue(vaults.bioKeyExists())
        scene.coldStart()
        val state = scene.awaitGate { it is GateState.NeedsPassword } as GateState.NeedsPassword
        assertEquals(PasswordReason.None, state.reason) // SlotMissing, as without the key
        assertFalse(state.fingerprintUsable)
        assertEquals(0, scene.gateway.prompts.size)
    }

    @Test
    fun e_aDeadSlotLeftByASkippedRewrite_isDroppedByTheNextRemoval() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.deleteBioAliases() // the key gone, the slot still in the file: what a removal under opMutex leaves
        scene.coldStart()
        scene.awaitGate { it is GateState.NeedsPassword }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(1)
        assertEquals(BioSlotStatus.Dead, slotStatus())
        val off = lock.confirmWithPassword(AuthPurpose.DisableLock, PASSWORD.toCharArray())
        assertEquals(AppResult.Ok(ToggleOutcome.Done), off)
        assertEquals(BioSlotStatus.None, slotStatus())
        assertEquals(null, app.container.keyVault.readState()?.bioSlot)
    }

    @Test
    fun f_noSecureLockScreen_asksThePassword_thenTurnsTheLockOffAndRemovesTheSlot() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.deviceSecure = false
        passwordUnlock()
        assertEquals(BioSlotStatus.None, slotStatus())
        val settings = scene.settings.snapshot()
        assertFalse(settings.biometricLock)
        assertEquals(true, settings.lockAutoOffNotice)
        assertEquals(0, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

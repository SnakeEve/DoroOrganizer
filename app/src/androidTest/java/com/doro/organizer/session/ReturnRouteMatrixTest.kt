package com.doro.organizer.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.testing.GateTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-48: the cases of testing.md's route matrix that L-31, L-38, L-39 and L-46 do not already cover, with the real
 * gate, controller, vault and DbMaintenance (DEC-62, DEC-65, DEC-74).
 */
@RunWith(AndroidJUnit4::class)
class ReturnRouteMatrixTest : GateTest() {
    private val offer get() = app.container.appLockController.slotOffer

    private suspend fun coldStartPassword(reason: PasswordReason = PasswordReason.None) {
        scene.coldStart()
        val state = scene.awaitGate { it is GateState.NeedsPassword } as GateState.NeedsPassword
        assertEquals(reason, state.reason)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
    }

    @Test
    fun c2_plainLockOn_noSecureLockScreen_turnsTheLockOff_andOpens() = runBlocking {
        scene.plant(KeyMode.PLAIN, lock = true)
        scene.gateway.deviceSecure = false
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        assertEquals(true, scene.settings.snapshot().lockAutoOffNotice)
        assertEquals(0, scene.gateway.prompts.size)
    }

    @Test
    fun c5_passwordLockOff_passwordWithoutReason_noOffer() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = false)
        coldStartPassword()
        scene.awaitOfferSetups(1)
        assertEquals(null, offer.value)
    }

    @Test
    fun c6_r4_passwordLockOn_noSecureLockScreen_passwordFirst_thenAutoOff() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.deviceSecure = false
        coldStartPassword()
        scene.awaitOfferSetups(1)
        assertFalse(scene.settings.snapshot().biometricLock)
        assertEquals(true, scene.settings.snapshot().lockAutoOffNotice)
        assertEquals(BioSlotStatus.None, app.container.keyVault.biometricSlotStatus())
        assertEquals(null, offer.value)
        assertEquals(0, scene.gateway.prompts.size)
    }

    @Test
    fun r4_passwordLockOn_noSecureLockScreen_onReturn_asksThePasswordOverTheOpenDatabase() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        assertEquals("a slot open runs no E5 (T16 with viaSlot)", 0, scene.offerSetups)
        scene.gateway.deviceSecure = false
        scene.backgroundTrip()
        scene.awaitGate { it is GateState.Locked && it.password != null }
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(1) // the password's
        assertFalse(scene.settings.snapshot().biometricLock)
        assertEquals(true, scene.settings.snapshot().lockAutoOffNotice)
        assertEquals(BioSlotStatus.None, app.container.keyVault.biometricSlotStatus())
        assertEquals(null, offer.value)
        assertEquals(1, scene.gateway.prompts.size)
        assertEquals("the password's E5 only", 1, scene.offerSetups)
    }

    @Test
    fun c8_r6_missingSlot_passwordThenTheMissingOffer() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = false)
        coldStartPassword()
        scene.awaitOfferSetups(1)
        assertEquals(SlotOffer.Missing, offer.value)
        scene.backgroundTrip() // R6: the password question again, the offer was dropped with the content
        scene.awaitGate { it is GateState.Locked && it.password != null }
        assertEquals(null, offer.value)
        scene.submit(PASSWORD)
        scene.awaitGate(GateState.Ready)
        scene.awaitOfferSetups(2)
        assertEquals(SlotOffer.Missing, offer.value)
    }

    @Test
    fun c9_r7_deadSlot_fingerprintChanged_thenTheInvalidatedOffer() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        vaults.deleteBioAliases()
        coldStartPassword(PasswordReason.FingerprintChanged)
        scene.awaitOfferSetups(1)
        assertEquals(SlotOffer.Invalidated, offer.value)
        scene.backgroundTrip()
        val locked = scene.awaitGate { it is GateState.Locked && it.password != null } as GateState.Locked
        assertEquals(PasswordReason.FingerprintChanged, locked.password?.reason)
    }

    @Test
    fun c10_reminderDue_passwordWithTheReason_restartsIt_noOffer() = runBlocking {
        scene.plant(KeyMode.PASSWORD, PASSWORD, lock = true, slot = true)
        scene.time.advanceBy(Duration.ofDays(15))
        coldStartPassword(PasswordReason.Reminder)
        assertEquals(scene.time.nowMillis(), scene.settings.snapshot().passwordVerifiedAt)
        assertEquals(0, scene.gateway.prompts.size)
        scene.awaitOfferSetups(1)
        assertEquals(BioSlotStatus.Live, app.container.keyVault.biometricSlotStatus())
        assertEquals(null, offer.value)
    }

    @Test
    fun r1_plainLockOn_noSecureLockScreenOnReturn_turnsTheLockOff() = runBlocking {
        scene.plant(KeyMode.DEVICE, lock = true)
        scene.gateway.succeed()
        scene.coldStart()
        scene.awaitGate(GateState.Ready)
        scene.gateway.deviceSecure = false
        scene.backgroundTrip()
        scene.awaitGate(GateState.Ready)
        assertFalse(scene.settings.snapshot().biometricLock)
        assertEquals(1, scene.gateway.prompts.size)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

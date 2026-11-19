package com.doro.organizer.session

import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.PromptResult
import com.doro.organizer.testing.LockFixture
import com.doro.organizer.testing.unused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L-18, T44 through the wiring: `DefaultSessionGate` tells the real controller which lock screen it shows and whether
 * that screen carries an error, so a lock entered because the slot open failed prompts only at the next ON_START.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateLockWiringTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun t44_slotOpenFailure_waitsForTheNextOnStart_thenPromptsOnce() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = LockFixture(this)
        f.mode(KeyMode.PASSWORD, lock = true, bio = BioSlotStatus.Live)
        f.attachActivity()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = DefaultSessionGate(
            unused(), unused(), f.vault, f.controller, f.settings, f.time,
            AppDispatchers(dispatcher, dispatcher, dispatcher), backgroundScope,
        )
        gate.dispatch(GateEvent.StartupLoaded(KeyMode.PASSWORD, true, 0, 0, UnlockRoute.Slot)) // T40
        assertEquals("a lock entered without an error prompts at once", 1, f.gateway.prompts.size)
        f.gateway.answer(PromptResult.Succeeded(cryptoMatches = true))
        gate.dispatch(GateEvent.SlotUnlocked(f.vault.newSlotCipher())) // T42; E2 is queued, the gate is not started
        gate.dispatch(GateEvent.SlotUnlockFailed) // T44
        assertEquals(GateState.Locked::class, gate.state.value::class)
        assertEquals(1, f.gateway.prompts.size)
        f.backgroundTrip()
        assertEquals(2, f.gateway.prompts.size)
        assertEquals(true, f.gateway.prompts.last().withCrypto)
    }
}

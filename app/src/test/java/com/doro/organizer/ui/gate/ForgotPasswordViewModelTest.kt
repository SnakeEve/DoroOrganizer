package com.doro.organizer.ui.gate

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.doro.organizer.core.messages.DefaultAppMessages
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.testing.FakeKeyVault
import com.doro.organizer.testing.unused
import java.lang.reflect.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** I-55, JVM part: the state of the Forgot password screen's ViewModel (DEC-90, ui.md § 8.15, § 10.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ForgotPasswordViewModelTest {
    private val lock = ScriptedLock()
    private val maintenance = ScriptedReset()
    private val messages = DefaultAppMessages()
    private val store = ViewModelStore()
    private fun typed() = "new password".toCharArray()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): ForgotPasswordViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { ForgotPasswordViewModel(lock, maintenance, messages) } },
    )[ForgotPasswordViewModel::class]

    @Test
    fun uiState_hasExactlyTheThreeProperties_andPrintsNoMoreThanFlagsAndTheErrorClass() {
        val fields = ForgotPasswordUiState::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("fingerprintAvailable", "busy", "error"), fields.map { it.name }.toSet())
        assertEquals(
            "ForgotPasswordUiState(fingerprintAvailable=Available, busy=false, error=AuthFailed)",
            ForgotPasswordUiState(AVAILABLE, false, AppError.AuthFailed).toString(),
        )
    }

    @Test
    fun availability_isNullUntilAnswered_andEachRefreshAsksOnce() = runTest {
        val vm = viewModel()
        assertNull(vm.state.value.fingerprintAvailable)

        vm.refreshAvailability()
        assertEquals(1, lock.availabilityCalls)
        assertNull(vm.state.value.fingerprintAvailable)
        lock.answerAvailability(AVAILABLE)
        assertEquals(AVAILABLE, vm.state.value.fingerprintAvailable)

        vm.refreshAvailability()
        assertNull("reset to null at once", vm.state.value.fingerprintAvailable)
        assertEquals(2, lock.availabilityCalls)
        lock.answerAvailability(UNAVAILABLE)
        assertEquals(UNAVAILABLE, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun aPromptWithoutCipher_zeroesThePassword_asksAgain_andNeverResets() = runTest {
        val vm = answered()
        val newPassword = typed()

        vm.resetPassword(MaintenanceOrigin.NeedsPassword, newPassword)
        assertTrue(vm.state.value.busy)
        lock.answerPrompt(AppResult.Ok(null))

        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertTrue("zeroed by the ViewModel", newPassword.all { it == '\u0000' })
        assertEquals(2, lock.availabilityCalls)
        assertNull(vm.state.value.fingerprintAvailable)
        lock.answerAvailability(UNAVAILABLE)
        assertEquals(UNAVAILABLE, vm.state.value.fingerprintAvailable)
        assertEquals(0, maintenance.calls)
    }

    @Test
    fun aFailedPrompt_setsTheError_zeroesThePassword_andAsksAgain() = runTest {
        val vm = answered()
        val newPassword = typed()

        vm.resetPassword(MaintenanceOrigin.Ready, newPassword)
        lock.answerPrompt(AppResult.Err(AppError.AuthFailed))

        assertEquals(AppError.AuthFailed, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertTrue(newPassword.all { it == '\u0000' })
        assertEquals(2, lock.availabilityCalls)
        assertEquals(0, maintenance.calls)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun anAuthorizedPrompt_handsCipherAndPasswordOver_busyUntilTheResetReturns() = runTest {
        val vm = answered()
        val newPassword = typed()
        val cipher = FakeKeyVault().newSlotCipher()

        vm.resetPassword(MaintenanceOrigin.NeedsPassword, newPassword)
        lock.answerPrompt(AppResult.Ok(cipher))

        assertTrue("busy until resetPasswordWithBiometric returns", vm.state.value.busy)
        assertEquals(1, maintenance.calls)
        assertEquals(MaintenanceOrigin.NeedsPassword, maintenance.origin)
        assertSame(cipher, maintenance.cipher)
        assertSame("DbMaintenance owns the array", newPassword, maintenance.password)
        assertEquals("not zeroed after the handover", "new password", String(newPassword))
        assertTrue(messages.consumeFingerprintReset())
        withTimeout(1_000) { vm.resetStarted.first() }

        maintenance.finish(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.PASSWORD)))
        assertFalse(vm.state.value.busy)
        assertEquals("a reset that succeeded does not ask again", 1, lock.availabilityCalls)
        assertEquals(AVAILABLE, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun aFailedReset_asksAgain() = runTest {
        val vm = answered()

        vm.resetPassword(MaintenanceOrigin.Ready, typed())
        lock.answerPrompt(AppResult.Ok(FakeKeyVault().newSlotCipher()))
        maintenance.finish(AppResult.Err(AppError.Unexpected))

        assertFalse(vm.state.value.busy)
        assertNull("DbMaintenance.status reports it, not the ViewModel", vm.state.value.error)
        assertEquals(2, lock.availabilityCalls)
    }

    @Test
    fun aSecondConfirmWhileBusy_onlyZeroesItsPassword() = runTest {
        val vm = answered()
        val first = typed()
        val second = typed()

        vm.resetPassword(MaintenanceOrigin.NeedsPassword, first)
        vm.resetPassword(MaintenanceOrigin.NeedsPassword, second)

        assertEquals(1, lock.prompts)
        assertTrue(second.all { it == '\u0000' })
        assertEquals("new password", String(first))
        lock.answerPrompt(AppResult.Ok(null))
        assertEquals(1, lock.prompts)
    }

    @Test
    fun aRefreshWhileBusy_isLeftToTheRunningReset() = runTest {
        val vm = answered()

        vm.resetPassword(MaintenanceOrigin.NeedsPassword, typed())
        vm.refreshAvailability()

        assertEquals(1, lock.availabilityCalls)
        assertEquals(AVAILABLE, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun aResetStartNoScreenReceived_isDroppedWhenTheScreenOpensAgain() = runTest {
        val vm = answered()
        vm.resetPassword(MaintenanceOrigin.NeedsPassword, typed())
        lock.answerPrompt(AppResult.Ok(FakeKeyVault().newSlotCipher()))
        maintenance.finish(AppResult.Err(AppError.Unexpected))
        lock.answerAvailability(AVAILABLE)

        vm.onScreenOpened()

        assertEquals(3, lock.availabilityCalls)
        lock.answerAvailability(AVAILABLE)
        assertNull("a new opening does not close at once", withTimeoutOrNull(1_000) { vm.resetStarted.first() })
    }

    // DEC-59: a screen restored after Android closed the app.

    @Test
    fun aScreenRestoredOverANewViewModel_asksForTheAnswer() = runTest {
        val vm = viewModel() // as after a process death: the screen was opened, this ViewModel never asked

        vm.onScreenRestored()

        assertEquals(1, lock.availabilityCalls)
        lock.answerAvailability(AVAILABLE)
        assertEquals(AVAILABLE, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun aScreenRestoredOverItsAnswer_orItsRunningQuestion_asksNothing() = runTest {
        val vm = answered()
        vm.onScreenRestored()
        assertEquals("a rotation keeps the answer", 1, lock.availabilityCalls)

        vm.refreshAvailability()
        vm.onScreenRestored()
        assertEquals("a rotation while the question runs", 2, lock.availabilityCalls)
        lock.answerAvailability(UNAVAILABLE)
        assertEquals(UNAVAILABLE, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun anUnknownSlot_isItsOwnAnswer() = runTest {
        val vm = viewModel()
        vm.onScreenOpened()

        lock.answerAvailability(FingerprintAvailability.Unknown)

        assertEquals(FingerprintAvailability.Unknown, vm.state.value.fingerprintAvailable)
    }

    @Test
    fun noPropertyHoldsAPassword() {
        val held = setOf(CharArray::class.java, String::class.java, TextFieldState::class.java)
        ForgotPasswordViewModel::class.java.declaredFields.forEach { field ->
            assertFalse(field.name, field.type in held)
        }
    }

    /** A ViewModel whose availability answer is in: `Available`. */
    private fun answered(): ForgotPasswordViewModel = viewModel().also {
        it.refreshAvailability()
        lock.answerAvailability(AVAILABLE)
    }

    /** `fingerprintUnlockAvailable()` and `slotPrompt(ResetPassword)` wait until the test answers. */
    private class ScriptedLock : AppLockController by unused() {
        private val availability = Channel<FingerprintAvailability>(Channel.UNLIMITED)
        private var prompt = CompletableDeferred<AppResult<SlotCipher?>>()
        var availabilityCalls = 0
        var prompts = 0

        fun answerAvailability(answer: FingerprintAvailability) {
            availability.trySend(answer)
        }

        fun answerPrompt(result: AppResult<SlotCipher?>) {
            prompt.complete(result)
            prompt = CompletableDeferred()
        }

        override suspend fun fingerprintUnlockAvailable(): FingerprintAvailability {
            availabilityCalls++
            return availability.receive()
        }

        override suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?> {
            assertEquals(AuthPurpose.ResetPassword, purpose)
            prompts++
            return prompt.await()
        }
    }

    /** `resetPasswordWithBiometric` records what it was handed and returns when the test finishes it. */
    private class ScriptedReset : DbMaintenance by unused() {
        private val result = CompletableDeferred<AppResult<MaintenanceResult>>()
        var calls = 0
        var origin: MaintenanceOrigin? = null
        var cipher: SlotCipher? = null
        var password: CharArray? = null

        fun finish(value: AppResult<MaintenanceResult>) {
            result.complete(value)
        }

        override suspend fun resetPasswordWithBiometric(
            origin: MaintenanceOrigin,
            cipher: SlotCipher,
            newPassword: CharArray,
        ): AppResult<MaintenanceResult> {
            calls++
            this.origin = origin
            this.cipher = cipher
            password = newPassword
            return result.await()
        }
    }

    private companion object {
        val AVAILABLE = FingerprintAvailability.Available
        val UNAVAILABLE = FingerprintAvailability.Unavailable
    }
}

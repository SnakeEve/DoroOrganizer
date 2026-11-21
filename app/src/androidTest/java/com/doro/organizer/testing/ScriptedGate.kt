package com.doro.organizer.testing

import androidx.fragment.app.FragmentActivity
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.session.GateEvent
import com.doro.organizer.session.GateState
import com.doro.organizer.session.SessionGate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [SessionGate] whose state the test sets; it records the events the screens dispatch and runs no effect. */
class ScriptedSessionGate(initial: GateState) : SessionGate {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<GateState> = mutableState.asStateFlow()

    /** Every event dispatched so far, in order. */
    val events = CopyOnWriteArrayList<GateEvent>()

    fun moveTo(state: GateState) {
        mutableState.value = state
    }

    override fun start() = Unit

    override fun dispatch(event: GateEvent) {
        events += event
    }

    override fun toString() = "ScriptedSessionGate"
}

/**
 * The [AppLockController] members the gate screens reach, scripted: the lock screen's route, the kekBroken notice, the
 * availability the Forgot password screen asks and the reset's slot prompt. Every other member fails the test.
 */
class ScriptedLockController : AppLockController by unused() {
    override val lockRoute = MutableStateFlow<UnlockRoute?>(null)
    override val kekNotice = MutableStateFlow(false)

    /** The answer of `fingerprintUnlockAvailable()`. */
    @Volatile var fingerprintAvailable = false
    val availabilityCalls = AtomicInteger()

    /** The answer of the next `slotPrompt(ResetPassword)`. */
    @Volatile var promptResult: AppResult<SlotCipher?> = AppResult.Ok(null)
    val slotPrompts = AtomicInteger()

    override fun attach(activity: FragmentActivity) = Unit

    override fun detach(activity: FragmentActivity) = Unit

    override suspend fun fingerprintUnlockAvailable(): Boolean {
        availabilityCalls.incrementAndGet()
        return fingerprintAvailable
    }

    override suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?> {
        check(purpose == AuthPurpose.ResetPassword) { "the gate screens ask the slot only for a reset" }
        slotPrompts.incrementAndGet()
        return promptResult
    }

    override fun toString() = "ScriptedLockController"
}

/**
 * The [DbMaintenance] members the gate screens reach, scripted: the status, the key mode, Erase (recorded, answered
 * with [eraseResult]) and the fingerprint reset (recorded; it returns when the test calls [finishReset]). Every other
 * member fails the test.
 */
class ScriptedMaintenance : DbMaintenance by unused() {
    override val status = MutableStateFlow<MaintenanceStatus>(MaintenanceStatus.Idle)
    override val keyMode = MutableStateFlow<KeyMode?>(KeyMode.PASSWORD)

    val erases = CopyOnWriteArrayList<MaintenanceOrigin>()
    @Volatile var eraseResult: AppResult<MaintenanceResult> = AppResult.Err(AppError.DatabaseUnavailable)

    val resets = CopyOnWriteArrayList<MaintenanceOrigin>()
    private val resetResult = CompletableDeferred<AppResult<MaintenanceResult>>()

    override suspend fun erase(origin: MaintenanceOrigin): AppResult<MaintenanceResult> {
        erases += origin
        return eraseResult
    }

    override suspend fun resetPasswordWithBiometric(
        origin: MaintenanceOrigin,
        cipher: SlotCipher,
        newPassword: CharArray,
    ): AppResult<MaintenanceResult> {
        newPassword.fill('\u0000') // owned here, as by the real DbMaintenance
        resets += origin
        return resetResult.await()
    }

    /** Ends the running fingerprint reset with [result]. */
    fun finishReset(result: AppResult<MaintenanceResult>) {
        resetResult.complete(result)
    }

    override fun toString() = "ScriptedMaintenance"
}

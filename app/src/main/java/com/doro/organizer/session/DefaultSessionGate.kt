package com.doro.organizer.session

import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.CorruptionListener
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceListener
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.LockError
import com.doro.organizer.security.LockListener
import com.doro.organizer.security.LockScreen
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The production [SessionGate] (DEC-90). [dispatch] runs on the main thread and applies [reduce]; effects E1–E4 run
 * on the IO dispatcher strictly in the order they were asked for (so a close always precedes the next open), report
 * back through [dispatch], and E5, E6 run on the main thread. It is the listener of the lock controller, the holder
 * and DbMaintenance (architecture.md § Event wiring).
 */
class DefaultSessionGate(
    holder: DatabaseHolder,
    maintenance: DbMaintenance,
    keyVault: KeyVault,
    private val lockController: AppLockController,
    settingsStore: SettingsStore,
    private val appTime: AppTime,
    private val dispatchers: AppDispatchers,
    private val applicationScope: CoroutineScope,
) : SessionGate, LockListener, CorruptionListener, MaintenanceListener {
    private val effects = GateEffects(holder, maintenance, keyVault, lockController, settingsStore, appTime)
    private val mutableState = MutableStateFlow<GateState>(GateState.Starting(authenticated = false))
    private val ioEffects = Channel<suspend () -> GateEvent?>(Channel.UNLIMITED)
    private var started = false
    private var shownScreen: Pair<LockScreen, Boolean>? = null

    override val state: StateFlow<GateState> = mutableState.asStateFlow()

    override fun start() {
        check(!started) { "the gate starts once per process" }
        started = true
        applicationScope.launch(dispatchers.io) {
            for (effect in ioEffects) effect()?.let { event -> withContext(dispatchers.main) { dispatch(event) } }
        }
        val (next, effects) = startTransition()
        apply(next, effects)
    }

    override fun dispatch(event: GateEvent) {
        val before = mutableState.value
        val (next, effects) = reduce(before, event, appTime.elapsedMillis())
        if (next == before && effects.isEmpty()) {
            AppLog.d("gate: ${event.javaClass.simpleName} ignored in ${before.javaClass.simpleName}") // T35
        }
        apply(next, effects)
        // A password that no effect took over is zeroed here; an unused SlotCipher simply expires (T35).
        if (event is GateEvent.PasswordSubmitted && effects.none { it.owns(event.password) }) event.password.fill('\u0000')
    }

    private fun apply(next: GateState, effects: List<GateEffect>) {
        mutableState.value = next
        val screen = lockScreenOf(next) to ((next as? GateState.Locked)?.authError != null)
        if (screen != shownScreen) {
            shownScreen = screen
            lockController.onGateScreenChanged(screen.first, screen.second)
        }
        effects.forEach(::run)
    }

    private fun run(effect: GateEffect) {
        when (effect) {
            GateEffect.RunStartup -> enqueue { this.effects.runStartup() }
            is GateEffect.UnlockAndOpen ->
                enqueue { this.effects.unlockAndOpen(effect.password, effect.cipher, effect.resetKey) }
            GateEffect.CloseDatabase -> enqueue {
                this.effects.closeDatabase()
                null
            }
            is GateEffect.VerifyPassword -> enqueue { this.effects.verifyPassword(effect.password) }
            GateEffect.OfferSlotSetup -> applicationScope.launch(dispatchers.main) { lockController.offerSlotSetup() }
            GateEffect.ClearKekBroken -> lockController.clearKekBroken()
        }
    }

    private fun enqueue(effect: suspend () -> GateEvent?) {
        ioEffects.trySend(effect).getOrThrow() // unlimited capacity: fails only if closed, which never happens
    }

    private fun GateEffect.owns(password: CharArray) =
        (this is GateEffect.UnlockAndOpen && this.password === password) ||
            (this is GateEffect.VerifyPassword && this.password === password)

    // LockListener: AppLockController calls these on the main thread.

    override fun onLockNow() = dispatch(GateEvent.LockNow)

    override fun onUnlocked() = dispatch(GateEvent.Unlocked)

    override fun onAuthError(error: LockError) = dispatch(GateEvent.AuthError(error))

    override fun onSlotUnlocked(cipher: SlotCipher) = dispatch(GateEvent.SlotUnlocked(cipher))

    override fun onPasswordRequired(reason: PasswordReason, fingerprintUsable: Boolean, failures: Int, retryAt: Long) =
        dispatch(GateEvent.PasswordRequired(reason, fingerprintUsable, failures, retryAt))

    override fun onFingerprintRechecked(usable: Boolean) = dispatch(GateEvent.FingerprintRechecked(usable))

    // CorruptionListener: any thread, so the dispatch is posted and the reporting thread never waits.

    override fun onRuntimeCorruption() {
        applicationScope.launch(dispatchers.main) { dispatch(GateEvent.CorruptAtRuntime) }
    }

    // MaintenanceListener: DbMaintenance calls these inside withContext(main), so the gate changes before it goes on.

    override fun onMaintenanceStarted(op: MaintenanceOp, fingerprintReset: Boolean) =
        dispatch(GateEvent.MaintenanceStarted(op, fingerprintReset))

    override fun onMaintenanceFinished(outcome: MaintenanceOutcome) = dispatch(GateEvent.MaintenanceFinished(outcome))
}

package com.doro.organizer.testing

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.UnlockRoute
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.AuthPurpose
import com.doro.organizer.security.BiometricAvailability
import com.doro.organizer.security.FingerprintAvailability
import com.doro.organizer.security.LockAvailability
import com.doro.organizer.security.SlotOffer
import com.doro.organizer.security.SlotState
import com.doro.organizer.security.ToggleOutcome
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.session.GateEvent
import com.doro.organizer.session.GateState
import com.doro.organizer.session.SessionGate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * The [AppLockController] members the gate screens, the shell and Settings reach, scripted: the lock screen's route,
 * the kekBroken notice and the set-up offer, the availability the Forgot password screen asks, the slot prompts of a
 * reset and of a restore's key, the Settings toggles and the `ExternalActivities` callbacks (no exemption is kept).
 * Every other member fails the test.
 */
class ScriptedLockController : AppLockController by unused() {
    override val lockRoute = MutableStateFlow<UnlockRoute?>(null)
    override val kekNotice = MutableStateFlow(false)
    override val slotOffer = MutableStateFlow<SlotOffer?>(null)

    /** The calls whose order matters, in order: `report`, `prompt:<purpose>`, `confirm:<purpose>`, and so on. */
    val calls = CopyOnWriteArrayList<String>()

    /** The Settings row's availability. */
    @Volatile var lockAvailability = LockAvailability(BiometricAvailability.Success, deviceSecure = true, SlotState.NotUsed)

    /** The answers of `confirm`, `confirmWithPassword`, `createSlot` and `reportPasswordKekFailure`. */
    @Volatile var confirmResult: AppResult<ToggleOutcome> = AppResult.Ok(ToggleOutcome.PasswordRequired)
    @Volatile var passwordResult: AppResult<ToggleOutcome> = AppResult.Ok(ToggleOutcome.Done)
    @Volatile var createSlotResult: AppResult<Boolean> = AppResult.Ok(false)
    @Volatile var kekFailureLive = false

    /** The answer of `slotPrompt(RestoreKey)`; while [restoreKeyHeld] is set, the prompt waits for it instead. */
    @Volatile var restoreKeyResult: AppResult<SlotCipher?> = AppResult.Ok(null)
    @Volatile var restoreKeyHeld: CompletableDeferred<AppResult<SlotCipher?>>? = null

    /** The answer of `fingerprintUnlockAvailable()`. */
    @Volatile var fingerprintAvailable = FingerprintAvailability.Unavailable
    val availabilityCalls = AtomicInteger()

    /** The answer of the next `slotPrompt(ResetPassword)`. */
    @Volatile var promptResult: AppResult<SlotCipher?> = AppResult.Ok(null)
    val slotPrompts = AtomicInteger()

    override fun attach(activity: FragmentActivity) = Unit

    override fun detach(activity: FragmentActivity) = Unit

    override suspend fun fingerprintUnlockAvailable(): FingerprintAvailability {
        availabilityCalls.incrementAndGet()
        return fingerprintAvailable
    }

    override suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?> {
        calls += "prompt:$purpose"
        if (purpose == AuthPurpose.RestoreKey) return restoreKeyHeld?.await() ?: restoreKeyResult
        check(purpose == AuthPurpose.ResetPassword) { "the screens ask the slot only for a reset or a restore's key" }
        slotPrompts.incrementAndGet()
        return promptResult
    }

    override suspend fun reportPasswordKekFailure(): Boolean {
        calls += "report"
        return kekFailureLive
    }

    override fun dismissKekNotice() {
        calls += "dismissKekNotice"
        kekNotice.value = false
    }

    override suspend fun createSlot(): AppResult<Boolean> {
        calls += "createSlot"
        slotOffer.value = null
        return createSlotResult
    }

    override fun dismissSlotOffer() {
        calls += "dismissSlotOffer"
        slotOffer.value = null
    }

    override suspend fun availability(): LockAvailability = lockAvailability

    override suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome> {
        calls += "confirm:$purpose"
        return confirmResult
    }

    override suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome> {
        password.fill(ZERO) // owned here, as by the real controller
        calls += "password:$purpose"
        return passwordResult
    }

    override fun enroll() {
        calls += "enroll"
    }

    override fun beforeLaunch(kind: ExternalKind) = Unit

    override fun afterResult(kind: ExternalKind) = Unit

    override fun notLaunched(request: ExternalRequest) = Unit

    override fun toString() = "ScriptedLockController"
}

/**
 * The [DbMaintenance] members the screens reach, scripted: the status (`acknowledge()` moves `Finished` to `Idle`), the
 * key mode, Erase (recorded, answered with [eraseResult]), the fingerprint reset (recorded; it returns when the test
 * calls [finishReset]), and the Settings operations and restore answers, recorded in [calls]. Passwords are zeroed as
 * by the real one. Every other member fails the test.
 */
class ScriptedMaintenance : DbMaintenance by unused() {
    override val status = MutableStateFlow<MaintenanceStatus>(MaintenanceStatus.Idle)
    override val keyMode = MutableStateFlow<KeyMode?>(KeyMode.PASSWORD)

    /**
     * `backupToDocument:<uri>:<password given>`, `backupForShare:<password given>`, `discard:<uri>`,
     * `restore:<uri>:<origin>`, `changeKey:<target>:<current given>:<new given>`, `answerPassword:<given>`,
     * `answerBiometric:<given>`, `answerConfirmation:<answer>`, `cancelRestore`, `acknowledge`.
     */
    val calls = CopyOnWriteArrayList<String>()

    /** The answer of `backupRequiresPassword()`. */
    @Volatile var requiresPassword = true

    /** The answer of `backupToDocument`; an error is also published as its `Finished` status, as the real one does. */
    @Volatile var backupResult: AppResult<MaintenanceResult> = AppResult.Ok(MaintenanceResult.BackupSaved)
    @Volatile var keyChangeResult: AppResult<MaintenanceResult> =
        AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.DEVICE))

    /** While set, `changeKey` returns its answer instead of [keyChangeResult], once the test completes it. */
    @Volatile var keyChangeHeld: CompletableDeferred<AppResult<MaintenanceResult>>? = null

    override fun backupRequiresPassword() = requiresPassword

    override suspend fun backupToDocument(target: Uri, backupPassword: CharArray?): AppResult<MaintenanceResult> {
        backupPassword?.fill(ZERO)
        calls += "backupToDocument:$target:${backupPassword != null}"
        val result = backupResult
        if (result is AppResult.Err) status.value = MaintenanceStatus.Finished(MaintenanceOp.Backup, result)
        return result
    }

    override suspend fun backupForShare(backupPassword: CharArray?): AppResult<MaintenanceResult> {
        backupPassword?.fill(ZERO)
        calls += "backupForShare:${backupPassword != null}"
        return AppResult.Ok(MaintenanceResult.BackupSaved)
    }

    override suspend fun discardDocument(target: Uri) {
        calls += "discard:$target"
    }

    override suspend fun restore(source: Uri, origin: MaintenanceOrigin): AppResult<MaintenanceResult> {
        calls += "restore:$source:$origin"
        return AppResult.Ok(MaintenanceResult.RestoreCancelled)
    }

    override suspend fun changeKey(
        target: KeyMode,
        currentPassword: CharArray?,
        newPassword: CharArray?,
    ): AppResult<MaintenanceResult> {
        currentPassword?.fill(ZERO)
        newPassword?.fill(ZERO)
        calls += "changeKey:$target:${currentPassword != null}:${newPassword != null}"
        return keyChangeHeld?.await() ?: keyChangeResult
    }

    override fun answerPassword(password: CharArray?) {
        password?.fill(ZERO)
        calls += "answerPassword:${password != null}"
    }

    override fun answerBiometric(cipher: SlotCipher?) {
        calls += "answerBiometric:${cipher != null}"
    }

    override fun answerConfirmation(confirmed: Boolean) {
        calls += "answerConfirmation:$confirmed"
    }

    override fun cancelRestore() {
        calls += "cancelRestore"
    }

    override fun acknowledge() {
        calls += "acknowledge"
        status.update { if (it is MaintenanceStatus.Finished) MaintenanceStatus.Idle else it }
    }

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
        newPassword.fill(ZERO) // owned here, as by the real DbMaintenance
        resets += origin
        return resetResult.await()
    }

    /** Ends the running fingerprint reset with [result]. */
    fun finishReset(result: AppResult<MaintenanceResult>) {
        resetResult.complete(result)
    }

    override fun toString() = "ScriptedMaintenance"
}

/** The real repository, but `hasAnyData()` answers [anyData]: a scripted gate opens no database. */
class ScriptedDataCheck(real: NoteRepository, private val anyData: () -> Boolean) : NoteRepository by real {
    override suspend fun hasAnyData(): AppResult<Boolean> = AppResult.Ok(anyData())

    override fun toString() = "ScriptedDataCheck"
}

/** The real launchers, whose launch failures are the ones a test emits into [scripted]. */
class ScriptedLaunchFailures(
    real: ExternalActivities,
    scripted: MutableSharedFlow<ExternalKind>,
) : ExternalActivities by real {
    override val failures: SharedFlow<ExternalKind> = scripted.asSharedFlow()

    override fun toString() = "ScriptedLaunchFailures"
}

private const val ZERO = '\u0000'

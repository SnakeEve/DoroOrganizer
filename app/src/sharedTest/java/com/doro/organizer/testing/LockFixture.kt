package com.doro.organizer.testing

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.core.external.ExternalRequest
import com.doro.organizer.core.external.ExternalResult
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.PasswordReason
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.BiometricSlotWriter
import com.doro.organizer.security.DefaultAppLockController
import com.doro.organizer.security.LockError
import com.doro.organizer.security.LockListener
import com.doro.organizer.security.PromptTexts
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.settings.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The real [DefaultAppLockController] over fakes (testing.md § 7): every dispatcher is one unconfined test dispatcher,
 * so a call returns once the controller waits for a prompt or has finished. [process] is the process lifecycle;
 * [activity] stands in for `MainActivity` once [attachActivity] ran (it needs `Dispatchers.setMain`).
 */
class LockFixture(scope: TestScope, sdkInt: Int = 34) {
    val time = FakeAppTime()
    val settings = FakeSettingsStore().apply { load() }
    val vault = FakeKeyVault()
    val gateway = FakeBiometricGateway()
    val process = TestProcessLifecycle()
    val activity = TestProcessLifecycle()
    val events = RecordingLockListener()
    val slots = RecordingSlotWriter { settings.settings.value?.biometricLock }
    val external = RecordingExternalActivities()
    private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)

    val controller = DefaultAppLockController(
        gateway, vault, { slots }, external, settings, time, process, { events }, { TEXTS },
        AppDispatchers(dispatcher, dispatcher, dispatcher), scope.backgroundScope, sdkInt,
    )

    init {
        process.moveTo(Lifecycle.State.RESUMED)
    }

    suspend fun mode(mode: KeyMode, lock: Boolean, bio: BioSlotStatus = BioSlotStatus.None) {
        vault.commitMode(mode, bio = bio)
        settings.setBiometricLock(lock)
    }

    /** The activity's auto-prompt collector, resumed. */
    fun attachActivity() {
        controller.observe(activity)
        activity.moveTo(Lifecycle.State.RESUMED)
    }

    /** A process trip to the background and back. */
    fun backgroundTrip() {
        process.moveTo(Lifecycle.State.CREATED)
        process.moveTo(Lifecycle.State.RESUMED)
    }

    companion object {
        val TEXTS = PromptTexts("Unlock", "Enable", "Disable", "Set up", "Reset", "Cancel", "Use password")
    }
}

/** Every [LockListener] call, as text. */
class RecordingLockListener : LockListener {
    val calls = mutableListOf<String>()

    fun clear() = calls.clear()

    override fun onLockNow() {
        calls += "LockNow"
    }

    override fun onUnlocked() {
        calls += "Unlocked"
    }

    override fun onAuthError(error: LockError) {
        calls += "AuthError(${error.code}, ${error.message})"
    }

    override fun onSlotUnlocked(cipher: SlotCipher) {
        calls += "SlotUnlocked"
    }

    override fun onPasswordRequired(reason: PasswordReason, fingerprintUsable: Boolean, failures: Int, retryAt: Long) {
        calls += "PasswordRequired($reason, $fingerprintUsable)"
    }

    override fun onFingerprintRechecked(usable: Boolean) {
        calls += "Rechecked($usable)"
    }
}

/**
 * Stands in for `DbMaintenance` in these JVM tests of the controller alone; records each call with the lock setting
 * at that moment, so a test sees whether the setting was written first (DEC-65).
 */
class RecordingSlotWriter(private val lockSetting: () -> Boolean?) : BiometricSlotWriter {
    val calls = mutableListOf<String>()
    var nextCreate: AppResult<Unit> = AppResult.Ok(Unit)

    override suspend fun createBiometricSlot(cipher: SlotCipher): AppResult<Unit> {
        calls += "create(lock=${lockSetting()})"
        return nextCreate
    }

    override suspend fun removeBiometricSlot(): AppResult<Unit> {
        calls += "remove(lock=${lockSetting()})"
        return AppResult.Ok(Unit)
    }
}

/** Records the requests the controller makes; the API 28–29 credential intent is the only one. */
class RecordingExternalActivities : ExternalActivities {
    val requests = mutableListOf<ExternalRequest>()
    override val inbox: StateFlow<ExternalResult?> = MutableStateFlow(null)
    override val failures: SharedFlow<ExternalKind> = MutableSharedFlow()

    override fun request(request: ExternalRequest) {
        requests += request
    }

    override fun take(kind: ExternalKind): ExternalResult? = null

    override fun bind(activity: ComponentActivity) = Unit
}

/** The settings written by the lock (security.md § 2). */
fun Settings.lockState() = "lock=$biometricLock, notice=$lockAutoOffNotice"

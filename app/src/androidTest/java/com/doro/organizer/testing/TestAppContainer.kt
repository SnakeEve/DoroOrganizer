package com.doro.organizer.testing

import android.app.Application
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.doro.organizer.AppContainer
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.DefaultAppLockController
import com.doro.organizer.security.LockListener
import com.doro.organizer.security.PromptTexts
import com.doro.organizer.security.BiometricGateway
import com.doro.organizer.security.KeyVault
import com.doro.organizer.session.SessionGate
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.common.Announcer
import kotlinx.coroutines.CoroutineScope

/** The members a test replaces before the container exists; null keeps the production member (testing.md § 7). */
class TestOverrides {
    var dispatchers: AppDispatchers? = null
    var applicationScope: CoroutineScope? = null
    var processLifecycle: LifecycleOwner? = null
    var appTime: AppTime? = null
    var settingsStore: SettingsStore? = null
    var files: DbFiles? = null

    /** Built from the container's settings, time and dispatchers, like the production vault. */
    var keyVault: ((SettingsStore, AppTime, AppDispatchers) -> KeyVault)? = null
    var biometricGateway: BiometricGateway? = null
    var faultHook: MaintenanceFaultHook? = null

    /** Wraps the production controller, which stays the real one underneath (testing.md § 7: never faked). */
    var wrapLockController: ((AppLockController) -> AppLockController)? = null

    /** Read when the controller is built: the API level whose credential branch it takes (security.md § 7.6). */
    var lockSdkInt: () -> Int? = { null }

    /**
     * Scripted replacements of the gate, the controller and DbMaintenance, for the Compose tests that drive the gate
     * screens from a scripted gate state (`ScriptedGateScene`); the gate tests keep all three real.
     */
    var sessionGate: SessionGate? = null
    var appLockController: AppLockController? = null
    var dbMaintenance: DbMaintenance? = null

    /** Wraps every view's [Announcer], to record the announcements (testing.md I-54, L-53). */
    var wrapAnnouncer: ((Announcer) -> Announcer)? = null
}

/** [AppContainer] whose overridable members come from [overrides] and default to the production ones. */
class TestAppContainer(app: Application, private val overrides: TestOverrides) : AppContainer(app) {
    override val dispatchers: AppDispatchers by lazy { overrides.dispatchers ?: super.dispatchers }
    override val applicationScope: CoroutineScope by lazy { overrides.applicationScope ?: super.applicationScope }
    override val processLifecycle: LifecycleOwner by lazy { overrides.processLifecycle ?: super.processLifecycle }
    override val appTime: AppTime by lazy { overrides.appTime ?: super.appTime }
    override val settingsStore: SettingsStore by lazy { overrides.settingsStore ?: super.settingsStore }
    override val files: DbFiles by lazy { overrides.files ?: super.files }
    override val keyVault: KeyVault by lazy {
        overrides.keyVault?.invoke(settingsStore, appTime, dispatchers) ?: super.keyVault
    }
    override val biometricGateway: BiometricGateway by lazy { overrides.biometricGateway ?: super.biometricGateway }
    override val faultHook: MaintenanceFaultHook by lazy { overrides.faultHook ?: super.faultHook }
    override val sessionGate: SessionGate by lazy { overrides.sessionGate ?: super.sessionGate }
    override val dbMaintenance: DbMaintenance by lazy { overrides.dbMaintenance ?: super.dbMaintenance }
    override val appLockController: AppLockController by lazy {
        overrides.appLockController?.let { return@lazy it }
        val real = overrides.lockSdkInt()?.let { sdkInt ->
            DefaultAppLockController(
                biometricGateway, keyVault, { dbMaintenance }, externalActivities, settingsStore, appTime, processLifecycle,
                { sessionGate as LockListener }, { PromptTexts.from(app) }, dispatchers, applicationScope, sdkInt,
            )
        } ?: super.appLockController
        overrides.wrapLockController?.invoke(real) ?: real
    }

    override fun announcer(view: View): Announcer {
        val real = super.announcer(view)
        return overrides.wrapAnnouncer?.invoke(real) ?: real
    }
}

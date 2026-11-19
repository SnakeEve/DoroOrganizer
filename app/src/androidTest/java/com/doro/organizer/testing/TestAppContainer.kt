package com.doro.organizer.testing

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import com.doro.organizer.AppContainer
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.DefaultAppLockController
import com.doro.organizer.security.LockListener
import com.doro.organizer.security.PromptTexts
import com.doro.organizer.security.BiometricGateway
import com.doro.organizer.security.KeyVault
import com.doro.organizer.settings.SettingsStore
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
    override val appLockController: AppLockController by lazy {
        val real = overrides.lockSdkInt()?.let { sdkInt ->
            DefaultAppLockController(
                biometricGateway, keyVault, { dbMaintenance }, externalActivities, settingsStore, appTime, processLifecycle,
                { sessionGate as LockListener }, { PromptTexts.from(app) }, dispatchers, applicationScope, sdkInt,
            )
        } ?: super.appLockController
        overrides.wrapLockController?.invoke(real) ?: real
    }
}

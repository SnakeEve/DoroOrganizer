package com.doro.organizer

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.external.DefaultExternalActivities
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.messages.AppMessages
import com.doro.organizer.core.messages.DefaultAppMessages
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.core.time.AppTimeLifecycleObserver
import com.doro.organizer.core.time.DefaultAppTime
import com.doro.organizer.core.time.TimeChangeReceiver
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DbFiles
import com.doro.organizer.data.DefaultDatabaseHolder
import com.doro.organizer.data.DefaultEntityRepository
import com.doro.organizer.data.DefaultNoteRepository
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.FlowProblemListener
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.DefaultDbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.model.WindowPolicy
import com.doro.organizer.security.AppLockController
import com.doro.organizer.security.BiometricGateway
import com.doro.organizer.security.DefaultAppLockController
import com.doro.organizer.security.DefaultBiometricGateway
import com.doro.organizer.security.DefaultKeyVault
import com.doro.organizer.security.KeyVault
import com.doro.organizer.security.PromptTexts
import com.doro.organizer.session.DefaultSessionGate
import com.doro.organizer.session.SessionGate
import com.doro.organizer.session.windowPolicyOf
import com.doro.organizer.settings.DefaultSettingsStore
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.UiGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manual DI root (DEC-06, architecture.md § Dependency injection). Members are open and lazy, so the instrumented
 * test container overrides them before first use (testing.md § 7). Construction does no I/O. Raisers receive their
 * listeners as providers resolved at each event, so no constructor takes the gate and the lazy members never recurse
 * (architecture.md § Event wiring). [app] is for the members that need the application `Context`. The UI reaches it
 * only as a [UiGraph] (architecture.md § Dependency injection).
 */
open class AppContainer(protected val app: Application) : UiGraph {
    open val dispatchers: AppDispatchers by lazy { AppDispatchers.production() }

    /** Never cancelled; detached work survives its caller and its failures still crash the app. */
    override val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    open val processLifecycle: LifecycleOwner by lazy { ProcessLifecycleOwner.get() }

    override val appTime: AppTime by lazy { DefaultAppTime.system(applicationScope) }

    /** DataStore does blocking file I/O, so it runs on the IO dispatcher of the never-cancelled application scope. */
    override val settingsStore: SettingsStore by lazy {
        DefaultSettingsStore.create(app, CoroutineScope(applicationScope.coroutineContext + dispatchers.io))
    }

    /** Built on the same `noBackupFilesDir` as [keyVault]: commits sync the key-state's directory through it. */
    open val files: DbFiles by lazy { DbFiles.forApp(app) }

    open val keyVault: KeyVault by lazy { DefaultKeyVault.create(app, settingsStore, appTime, dispatchers) }

    override val appMessages: AppMessages by lazy { DefaultAppMessages() }

    open val databaseHolder: DatabaseHolder by lazy {
        DefaultDatabaseHolder(
            app, files, dispatchers,
            corruptionListener = { gate },
            flowProblemListener = FlowProblemListener { error -> appMessages.post(UserMessage.error(error)) },
        )
    }

    /** No-op in production; test containers throw, block or kill the process at a pipeline step (testing.md § 5). */
    open val faultHook: MaintenanceFaultHook by lazy { MaintenanceFaultHook.None }

    override val dbMaintenance: DbMaintenance by lazy {
        DefaultDbMaintenance(
            app, files, keyVault, databaseHolder, settingsStore, appTime, dispatchers, applicationScope, faultHook,
            listener = { gate },
        )
    }

    override val noteRepository: NoteRepository by lazy {
        DefaultNoteRepository(databaseHolder, appTime, applicationScope, dispatchers)
    }

    override val entityRepository: EntityRepository by lazy {
        DefaultEntityRepository(databaseHolder, appTime, applicationScope, dispatchers)
    }

    override val externalActivities: ExternalActivities by lazy { DefaultExternalActivities { appLockController } }

    open val biometricGateway: BiometricGateway by lazy { DefaultBiometricGateway(app) }

    /** Built on the main thread (the gate's first use): it observes [processLifecycle] from then on. */
    override val appLockController: AppLockController by lazy {
        DefaultAppLockController(
            biometricGateway, keyVault, slotWriter = { dbMaintenance }, externalActivities, settingsStore, appTime,
            processLifecycle, listener = { gate }, texts = { PromptTexts.from(app) }, dispatchers, applicationScope,
        )
    }

    /** The gate as the listener of the raisers; [sessionGate] is the same instance. */
    private val gate: DefaultSessionGate by lazy {
        DefaultSessionGate(
            databaseHolder, dbMaintenance, keyVault, appLockController, settingsStore, appTime, dispatchers,
            applicationScope,
        )
    }

    override val sessionGate: SessionGate by lazy { gate }

    /** The single source of the window flags; closed until the settings and the key mode are known (DEC-67). */
    open val windowPolicy: StateFlow<WindowPolicy> by lazy {
        combine(settingsStore.settings, dbMaintenance.keyMode, sessionGate.state, ::windowPolicyOf)
            .stateIn(applicationScope, SharingStarted.Eagerly, WindowPolicy.Closed)
    }

    /** Connects the process-wide observers; called once, on the main thread, right after the container is built. */
    fun installProcessObservers() {
        // A test's FakeAppTime is moved by the test itself, so only the production clock is connected.
        (appTime as? DefaultAppTime)?.let { time ->
            processLifecycle.lifecycle.addObserver(AppTimeLifecycleObserver(time))
            TimeChangeReceiver.register(app, TimeChangeReceiver(time::onTimeChanged))
        }
        processLifecycle.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) applicationScope.launch { dbMaintenance.sweepShareFiles() }
            },
        )
    }
}

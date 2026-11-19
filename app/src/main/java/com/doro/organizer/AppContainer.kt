package com.doro.organizer

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.core.time.AppTimeLifecycleObserver
import com.doro.organizer.core.time.DefaultAppTime
import com.doro.organizer.core.time.TimeChangeReceiver
import com.doro.organizer.settings.DefaultSettingsStore
import com.doro.organizer.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI root (DEC-06). Members are open and lazy, so the instrumented test container overrides them before
 * first use (testing.md § 7). Construction does no I/O. [app] is for the members that need the application
 * `Context` (architecture.md § Dependency injection).
 */
open class AppContainer(protected val app: Application) {
    open val dispatchers: AppDispatchers by lazy { AppDispatchers.production() }

    /** Never cancelled; detached work survives its caller and its failures still crash the app. */
    open val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    open val processLifecycle: LifecycleOwner by lazy { ProcessLifecycleOwner.get() }

    open val appTime: AppTime by lazy { DefaultAppTime.system(applicationScope) }

    /** DataStore does blocking file I/O, so it runs on the IO dispatcher of the never-cancelled application scope. */
    open val settingsStore: SettingsStore by lazy {
        DefaultSettingsStore.create(app, CoroutineScope(applicationScope.coroutineContext + dispatchers.io))
    }

    /** Connects the process-wide observers; called once, on the main thread, right after the container is built. */
    fun installProcessObservers() {
        // A test's FakeAppTime is moved by the test itself, so only the production clock is connected.
        (appTime as? DefaultAppTime)?.let { time ->
            processLifecycle.lifecycle.addObserver(AppTimeLifecycleObserver(time))
            TimeChangeReceiver.register(app, TimeChangeReceiver(time::onTimeChanged))
        }
    }
}

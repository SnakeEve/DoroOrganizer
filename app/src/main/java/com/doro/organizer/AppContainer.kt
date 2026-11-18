package com.doro.organizer

import android.app.Application
import com.doro.organizer.core.coroutines.AppDispatchers
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
}

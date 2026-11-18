package com.doro.organizer.testing

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import com.doro.organizer.AppContainer
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import kotlinx.coroutines.CoroutineScope

/** The members a test replaces before the container exists; null keeps the production member. */
class TestOverrides {
    var dispatchers: AppDispatchers? = null
    var applicationScope: CoroutineScope? = null
    var processLifecycle: LifecycleOwner? = null
    var appTime: AppTime? = null
}

/** [AppContainer] whose overridable members come from [overrides] and default to the production ones. */
class TestAppContainer(app: Application, private val overrides: TestOverrides) : AppContainer(app) {
    override val dispatchers: AppDispatchers by lazy { overrides.dispatchers ?: super.dispatchers }
    override val applicationScope: CoroutineScope by lazy { overrides.applicationScope ?: super.applicationScope }
    override val processLifecycle: LifecycleOwner by lazy { overrides.processLifecycle ?: super.processLifecycle }
    override val appTime: AppTime by lazy { overrides.appTime ?: super.appTime }
}

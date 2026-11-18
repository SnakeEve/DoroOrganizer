package com.doro.organizer.testing

import android.app.Application
import com.doro.organizer.AppContainer
import com.doro.organizer.core.coroutines.AppDispatchers
import kotlinx.coroutines.CoroutineScope

/** The members a test replaces before the container exists; null keeps the production member. */
class TestOverrides {
    var dispatchers: AppDispatchers? = null
    var applicationScope: CoroutineScope? = null
}

/** [AppContainer] whose overridable members come from [overrides] and default to the production ones. */
class TestAppContainer(app: Application, private val overrides: TestOverrides) : AppContainer(app) {
    override val dispatchers: AppDispatchers by lazy { overrides.dispatchers ?: super.dispatchers }
    override val applicationScope: CoroutineScope by lazy { overrides.applicationScope ?: super.applicationScope }
}

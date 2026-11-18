package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.DoroApplication
import kotlinx.coroutines.cancel
import org.junit.rules.ExternalResource

/**
 * Installs a [TestAppContainer] into the running [DoroApplication] before the test, on the main thread, after
 * [configure] has set the overrides; afterwards cancels the container's application scope (testing.md § 7, Wiring).
 * Declare it as `@get:Rule(order = 0)`, ahead of any rule that launches `MainActivity`.
 */
class DoroAppRule(private val configure: TestOverrides.() -> Unit = {}) : ExternalResource() {
    lateinit var container: TestAppContainer
        private set

    override fun before() {
        val overrides = TestOverrides().apply(configure)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DoroApplication
        instrumentation.runOnMainSync {
            container = TestAppContainer(app, overrides)
            app.container = container
        }
    }

    override fun after() {
        container.applicationScope.cancel()
    }
}

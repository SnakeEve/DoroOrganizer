package com.doro.organizer.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.DoroApplication
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Installs a [TestAppContainer] into the running [DoroApplication] before the test, on the main thread, after
 * [configure] has set the overrides, connects its process observers and, with [autoStart], starts the gate; a test
 * that plants files, key state or settings first passes `autoStart = false` and calls [start]. Afterwards it closes
 * the holder and cancels the container's application scope (testing.md § 7, Wiring).
 * Declare it as `@get:Rule(order = 0)`, ahead of any rule that launches `MainActivity`.
 */
class DoroAppRule(
    private val autoStart: Boolean = true,
    private val configure: TestOverrides.() -> Unit = {},
) : ExternalResource() {
    lateinit var container: TestAppContainer
        private set

    override fun before() {
        val overrides = TestOverrides().apply(configure)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DoroApplication
        instrumentation.runOnMainSync {
            container = TestAppContainer(app, overrides)
            app.container = container
            container.installProcessObservers()
            if (autoStart) container.sessionGate.start()
        }
    }

    /** Starts the gate (T1) on the main thread. */
    fun start() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.sessionGate.start() }

    override fun after() {
        runBlocking { container.databaseHolder.close() }
        container.applicationScope.cancel()
    }
}

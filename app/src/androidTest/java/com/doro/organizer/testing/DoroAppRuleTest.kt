package com.doro.organizer.testing

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.DoroApplication
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTimeLifecycleObserver
import com.doro.organizer.core.time.DefaultAppTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class DoroAppRuleTest {
    private val app: DoroApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DoroApplication

    @Test
    fun runner_startsTestApplication() {
        assertTrue(app is TestDoroApplication)
    }

    @Test
    fun rule_installsContainerWithOverridesBeforeTest_andCancelsScopeAfter() {
        val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val scope = CoroutineScope(Job())
        val rule = DoroAppRule(autoStart = false) {
            this.dispatchers = dispatchers
            applicationScope = scope
        }

        rule.run {
            assertSame(rule.container, app.container)
            assertSame(dispatchers, app.container.dispatchers)
            assertSame(scope, app.container.applicationScope)
            assertTrue(scope.isActive)
        }

        assertFalse(scope.isActive)
    }

    /**
     * A [FakeAppTime] is moved by its test, so no clock observer is connected to the process lifecycle. The container's
     * other process observers (the share sweep of db-lifecycle.md) are connected all the same.
     */
    @Test
    fun rule_installsTimeAndLifecycleOverrides_andConnectsNoFakeClock() {
        val time = FakeAppTime()
        val process = RecordingProcessLifecycle()
        val rule = DoroAppRule(autoStart = false) {
            appTime = time
            processLifecycle = process
        }

        rule.run {
            assertSame(time, app.container.appTime)
            assertSame(process, app.container.processLifecycle)
            assertEquals(emptyList<LifecycleObserver>(), process.observers.filterIsInstance<AppTimeLifecycleObserver>())
        }
    }

    /** The control of the case above: the production clock is connected, through exactly one observer. */
    @Test
    fun rule_withTheProductionClock_connectsItToTheProcessLifecycle() {
        val process = RecordingProcessLifecycle()
        val rule = DoroAppRule(autoStart = false) { processLifecycle = process }

        rule.run {
            assertTrue(app.container.appTime is DefaultAppTime)
            assertEquals(1, process.observers.count { it is AppTimeLifecycleObserver })
        }
    }

    @Test
    fun rule_withoutOverrides_keepsProductionMembers() {
        val rule = DoroAppRule(autoStart = false)

        rule.run {
            assertSame(Dispatchers.IO, app.container.dispatchers.io)
            assertTrue(app.container.appTime is DefaultAppTime)
            assertTrue(app.container.applicationScope.isActive)
        }

        assertFalse(rule.container.applicationScope.isActive)
    }

    private fun DoroAppRule.run(test: () -> Unit) {
        val statement = object : Statement() {
            override fun evaluate() = test()
        }
        apply(statement, Description.EMPTY).evaluate()
    }

    /** A process lifecycle that records every observer the container connects to it. */
    private class RecordingProcessLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        val observers = mutableListOf<LifecycleObserver>()

        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState: State get() = registry.currentState

            override val currentStateFlow: StateFlow<State> get() = registry.currentStateFlow

            override fun addObserver(observer: LifecycleObserver) {
                observers += observer
                registry.addObserver(observer)
            }

            override fun removeObserver(observer: LifecycleObserver) {
                observers -= observer
                registry.removeObserver(observer)
            }
        }
    }
}

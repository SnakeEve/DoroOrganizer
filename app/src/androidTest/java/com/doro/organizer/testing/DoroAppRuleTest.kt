package com.doro.organizer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.DoroApplication
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.DefaultAppTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val rule = DoroAppRule {
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

    /** A [FakeAppTime] is moved by its test, so nothing is connected to the process lifecycle. */
    @Test
    fun rule_installsTimeAndLifecycleOverrides_andConnectsNoFakeClock() {
        val time = FakeAppTime()
        val process = TestProcessLifecycle()
        val rule = DoroAppRule {
            appTime = time
            processLifecycle = process
        }

        rule.run {
            assertSame(time, app.container.appTime)
            assertSame(process, app.container.processLifecycle)
            assertEquals(0, process.observerCount)
        }
    }

    @Test
    fun rule_withoutOverrides_keepsProductionMembers() {
        val rule = DoroAppRule()

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
}

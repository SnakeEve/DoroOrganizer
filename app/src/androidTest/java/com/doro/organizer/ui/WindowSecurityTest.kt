package com.doro.organizer.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.doro.organizer.MainActivity
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import com.doro.organizer.testing.SetupProbeRecord
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * testing.md L-22, FLAG_SECURE (DEC-67, security.md § 8, architecture.md § MainActivity startup order). In
 * `onActivityCreated` a [WindowRecorder] wraps the window's `Window.Callback` to record every
 * `onWindowAttributesChanged`, and registers an `OnPreDrawListener` (and an `OnDrawListener`, which fires only for a
 * frame that is really drawn, so the first one is the first app frame) on the decor view:
 * - setting on: `FLAG_SECURE` is set at creation (starting closed) and never cleared when the first pre-draw fires;
 * - setting off: the flag also starts set, was cleared before the first app frame and is absent once the gate is Ready;
 * - toggling in Settings sets and clears the flag on the same activity instance, without recreation;
 * - Verify at setup: on which side of `onCreate` this device dispatches `onActivityCreated` (inside
 *   `super.onCreate`, so before `MainActivity` adds the flag), and that the start-closed window and the splash hold
 *   are one mechanism: the closed policy that unread settings give is still emitted, releases the splash and keeps
 *   every frame secure;
 * - Verify at setup (security.md § 8, not L-22): on API 29+ content capture is disabled for `MainActivity` once Ready,
 *   its session started after `onCreate` cleared `FLAG_SECURE`, so that only the opt-out can have disabled it; skipped
 *   where the system gives no `ContentCaptureManager`.
 *
 * Not observable here: that `setRecentsScreenshotEnabled(false)` was applied before the first frame on API 33+.
 * `MainActivity` calls `Activity.setRecentsScreenshotEnabled` directly; the value lives in the system's activity record
 * and no public API reads it back. The API 28 dispatch order is read from the platform source (no API 28 device runs,
 * DEC-15): API 28's `Activity.onCreate` calls `Application.dispatchActivityCreated` itself, the
 * place that [SettingsRead.onActivityCreated_isDispatchedInsideOnCreate_beforeTheFlagIsAdded] observes on the device.
 */
@RunWith(Enclosed::class)
class WindowSecurityTest {
    @RunWith(AndroidJUnit4::class)
    class SettingsRead : ScriptedGateComposeTest() {
        private val recorder = WindowRecorder()

        @Before
        fun register() = recorder.register()

        @After
        fun unregister() = recorder.unregister()

        @Test
        fun protectionOn_flagSetAtCreation_neverClearedBeforeTheFirstPreDraw() {
            runBlocking { scene.settings.setScreenshotProtection(true) }
            scene.launch(GateState.Ready)
            recorder.awaitFirstFrame()
            val log = recorder.untilFirstFrame()

            val set = log.indexOfFirst { it is Event.Attributes && it.secure }
            val firstPreDraw = log.indexOfFirst { it is Event.PreDraw }
            assertTrue(
                "FLAG_SECURE was never set before the first pre-draw: ${log.render()}",
                set in 0 until firstPreDraw,
            )
            val clearedAt = log.indexOfLast { it is Event.Attributes && !it.secure }
            assertTrue("FLAG_SECURE was cleared after it was set: ${log.render()}", clearedAt < set)
            assertTrue(
                "a pre-draw or the first frame without FLAG_SECURE: ${log.render()}",
                log.filter { it is Event.PreDraw || it is Event.Draw }.all { it.secure },
            )

            ui.awaitShell()
            assertEquals("FLAG_SECURE once Ready with the setting on", true, secureNow())
        }

        @Test
        fun protectionOff_flagStartsSet_clearedBeforeTheFirstAppFrame_andAbsentInReady() {
            scene.launch(GateState.Ready) // the setting defaults to off
            recorder.awaitFirstFrame()
            val log = recorder.untilFirstFrame()

            val set = log.indexOfFirst { it is Event.Attributes && it.secure }
            assertTrue("FLAG_SECURE was not set at creation (start closed): ${log.render()}", set >= 0)
            val cleared = (set + 1 until log.size).firstOrNull { log[it] is Event.Attributes && !log[it].secure } ?: -1
            val firstFrame = log.indexOfFirst { it is Event.Draw }
            assertTrue(
                "FLAG_SECURE was not cleared before the first app frame: ${log.render()}",
                cleared in (set + 1) until firstFrame,
            )
            assertEquals("FLAG_SECURE on the first app frame: ${log.render()}", false, log[firstFrame].secure)

            ui.awaitShell()
            assertEquals("FLAG_SECURE once Ready with the setting off", false, secureNow())
        }

        @Test
        fun togglingInSettings_setsAndClearsTheFlag_onTheSameActivityInstance() {
            scene.launch(GateState.Ready)
            ui.awaitShell()
            assertEquals("FLAG_SECURE in Ready before the toggle", false, secureNow())
            val created = checkNotNull(recorder.activity) { "onActivityCreated was not seen for MainActivity" }

            compose.onNodeWithTag(SETTINGS_ACTION).performClick()
            ui.awaitTag(SETTINGS_LIST)
            ui.awaitTag(SCREENSHOT) // the security rows wait for the settings and the key mode
            for (on in listOf(true, false)) {
                val before = recorder.events.size
                compose.onNodeWithTag(SCREENSHOT).performScrollTo().performClick()
                runCatching {
                    compose.waitUntil(GateUi.TIMEOUT_MS) {
                        scene.settings.settings.value?.screenshotProtection == on && secureNow() == on
                    }
                }
                val stored = scene.settings.settings.value?.screenshotProtection
                assertEquals("the stored setting after toggling to $on", on, stored)
                assertEquals("FLAG_SECURE after toggling the setting to $on", on, secureNow())
                val changes = recorder.events.drop(before).filterIsInstance<Event.Attributes>().map { it.secure }
                assertTrue("no attribute change to secure=$on was recorded after the toggle: $changes", on in changes)
                assertSame("FLAG_SECURE turned $on on another activity instance", created, currentActivity())
                assertEquals("MainActivity creations after toggling to $on", 1, recorder.createdCount)
            }
        }

        /**
         * Verify at setup (testing.md L-22): this device dispatches `onActivityCreated` from inside
         * `MainActivity.onCreate` (from `super.onCreate`), before `MainActivity` adds `FLAG_SECURE`, so the recorder
         * sees the flag being added and every later change.
         */
        @Test
        fun onActivityCreated_isDispatchedInsideOnCreate_beforeTheFlagIsAdded() {
            scene.launch(GateState.Ready)
            recorder.awaitFirstFrame()
            val log = recorder.untilFirstFrame()

            val created = log.firstOrNull() as? Event.Created
            assertNotNull("the first record is not onActivityCreated: ${log.render()}", created)
            assertTrue(
                "API ${Build.VERSION.SDK_INT}: onActivityCreated ran outside MainActivity.onCreate: ${log.render()}",
                checkNotNull(created).insideOnCreate,
            )
            val api = Build.VERSION.SDK_INT
            assertEquals("API $api: FLAG_SECURE already set at onActivityCreated", false, created.secure)
            val added = log.indexOfFirst { it is Event.Attributes && it.secure && it.insideOnCreate }
            assertTrue("the recorder did not see MainActivity.onCreate add FLAG_SECURE: ${log.render()}", added > 0)
        }

        /**
         * Verify at setup (security.md § 8, system content capture): `MainActivity.onCreate` opts out on API 29+, so
         * once the activity is Ready its own `ContentCaptureManager` reports content capture as disabled.
         *
         * `FLAG_SECURE` disables the session as well: `Activity.onPostCreate` starts it with the window's attributes,
         * and a session started on a secure window starts disabled. So the window policy is open (the setting off)
         * before the launch: `MainActivity.onCreate` collects it at once (`Dispatchers.Main.immediate`) and clears the
         * flag it added, the recorder shows that clear inside `onCreate`, before `onPostCreate`, and the window is
         * still not secure when the manager is read. Only the opt-out can then have disabled the session. The system
         * gives no manager when it captures nothing for the app (no service, or the app not allowlisted): then there
         * is nothing to observe and the test is skipped. The probe record gives the manager's answer, or its absence,
         * and the window's flag.
         */
        @Test
        @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
        fun contentCapture_isDisabledForMainActivity_onceReady() {
            val open = runBlocking {
                withTimeoutOrNull(GateUi.TIMEOUT_MS) { scene.app.container.windowPolicy.first { !it.secure } }
            }
            assertNotNull("the window policy did not open before the launch", open)
            scene.launch(GateState.Ready)
            ui.awaitShell()
            val log = recorder.events.toList()
            val inOnCreate = log.filterIsInstance<Event.Attributes>().filter { it.insideOnCreate }
            assertTrue(
                "MainActivity.onCreate did not end with the FLAG_SECURE it added cleared: ${log.render()}",
                inOnCreate.any { it.secure } && !inOnCreate.last().secure,
            )

            var hasManager = false
            var enabled = false
            var secure = true
            scene.onActivity {
                val manager = it.getSystemService(ContentCaptureManager::class.java)
                hasManager = manager != null
                enabled = manager?.isContentCaptureEnabled == true
                secure = it.window.isSecure()
            }
            val answer = if (hasManager) "isContentCaptureEnabled=$enabled" else "no ContentCaptureManager"
            SetupProbeRecord.record(
                "WindowSecurityTest#contentCapture_isDisabledForMainActivity_onceReady",
                "$answer, FLAG_SECURE=$secure",
            )
            assertFalse("FLAG_SECURE is set when content capture is read", secure)
            assumeTrue("the system offers content capture to MainActivity on this device", hasManager)
            assertFalse("content capture is enabled for MainActivity once Ready", enabled)
        }

        private fun secureNow(): Boolean {
            var secure = false
            scene.onActivity { secure = it.window.isSecure() }
            return secure
        }

        private fun currentActivity(): MainActivity? {
            var current: MainActivity? = null
            scene.onActivity { current = it }
            return current
        }
    }

    /**
     * Verify at setup (testing.md L-22): the splash hold and the start-closed rule are one mechanism. The settings are
     * never read, so the window policy stays closed (security.md § 8); the closed policy is still emitted, so the
     * splash is released and a first app frame is drawn, and every pre-draw and that frame see `FLAG_SECURE`.
     */
    @RunWith(AndroidJUnit4::class)
    class SettingsNeverRead : ScriptedGateComposeTest(settingsRead = false) {
        private val recorder = WindowRecorder()

        @Before
        fun register() = recorder.register()

        @After
        fun unregister() = recorder.unregister()

        @Test
        fun closedPolicy_releasesTheSplash_andEveryFrameIsSecure() {
            scene.launch(GateState.Ready)
            recorder.awaitFirstFrame()
            val log = recorder.untilFirstFrame()

            val set = log.indexOfFirst { it is Event.Attributes && it.secure }
            val firstPreDraw = log.indexOfFirst { it is Event.PreDraw }
            assertTrue(
                "FLAG_SECURE was never set before the first pre-draw: ${log.render()}",
                set in 0 until firstPreDraw,
            )
            val clearedAt = log.indexOfLast { it is Event.Attributes && !it.secure }
            assertTrue("FLAG_SECURE was cleared under the closed policy: ${log.render()}", clearedAt < set)
            assertTrue(
                "a pre-draw or the first frame without FLAG_SECURE: ${log.render()}",
                log.filter { it is Event.PreDraw || it is Event.Draw }.all { it.secure },
            )
            assertEquals("the settings were read", null, scene.settings.settings.value)
        }
    }

    private companion object {
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"
        const val SCREENSHOT = "settings_screenshot"
    }
}

/** One record of [WindowRecorder]: booleans only (DEC-70). */
private sealed class Event {
    abstract val secure: Boolean

    /** onActivityCreated; [insideOnCreate]: `MainActivity.onCreate` is on the stack. */
    data class Created(override val secure: Boolean, val insideOnCreate: Boolean) : Event()

    data class Attributes(override val secure: Boolean, val insideOnCreate: Boolean) : Event()

    data class PreDraw(override val secure: Boolean) : Event()

    /** A frame really drawn: no pre-draw listener (the splash hold's) cancelled it. */
    data class Draw(override val secure: Boolean) : Event()
}

private fun List<Event>.render(): String = joinToString(" ") { event ->
    when (event) {
        is Event.Created -> "created(secure=${event.secure},inOnCreate=${event.insideOnCreate})"
        is Event.Attributes -> "attrs(secure=${event.secure},inOnCreate=${event.insideOnCreate})"
        is Event.PreDraw -> "preDraw(secure=${event.secure})"
        is Event.Draw -> "draw(secure=${event.secure})"
    }
}

private fun Window.isSecure() = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

private fun insideMainActivityOnCreate() = Thread.currentThread().stackTrace.any {
    it.className == MainActivity::class.java.name && it.methodName == "onCreate"
}

/**
 * The attribute recorder of testing.md L-22: installed on the first `MainActivity` created after [register], in its
 * `onActivityCreated`. Attribute changes are recorded for the activity's life, pre-draws and draws until the first
 * draw.
 */
private class WindowRecorder {
    val events = CopyOnWriteArrayList<Event>()

    @Volatile var activity: MainActivity? = null
        private set

    @Volatile var createdCount = 0
        private set

    private val firstFrame = CountDownLatch(1)
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            createdCount++
            if (this@WindowRecorder.activity != null) return
            this@WindowRecorder.activity = activity
            val window = activity.window
            events += Event.Created(window.isSecure(), insideMainActivityOnCreate())
            window.callback = RecordingCallback(checkNotNull(window.callback)) { attrs ->
                val secure = attrs.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                events += Event.Attributes(secure, insideMainActivityOnCreate())
            }
            // Registered before the window is attached: the decor view's floating observer is merged on attach.
            val observer = window.decorView.viewTreeObserver
            observer.addOnPreDrawListener(
                ViewTreeObserver.OnPreDrawListener {
                    if (firstFrame.count > 0) events += Event.PreDraw(window.isSecure())
                    true
                },
            )
            observer.addOnDrawListener {
                if (firstFrame.count > 0) {
                    events += Event.Draw(window.isSecure())
                    firstFrame.countDown()
                }
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun register() = application.registerActivityLifecycleCallbacks(callbacks)

    fun unregister() = application.unregisterActivityLifecycleCallbacks(callbacks)

    fun awaitFirstFrame() = check(firstFrame.await(GateUi.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        "MainActivity drew no frame: ${events.toList().render()}"
    }

    /** The records up to and including the first drawn frame. */
    fun untilFirstFrame(): List<Event> {
        val all = events.toList()
        val first = all.indexOfFirst { it is Event.Draw }
        return if (first < 0) all else all.subList(0, first + 1)
    }
}

/** Delegates every call to [delegate] and reports each `onWindowAttributesChanged` after it. */
private class RecordingCallback(
    private val delegate: Window.Callback,
    private val onAttributes: (WindowManager.LayoutParams) -> Unit,
) : Window.Callback by delegate {
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
        delegate.onWindowAttributesChanged(attrs)
        onAttributes(attrs)
    }

    // Java default methods, delegated explicitly.
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
}

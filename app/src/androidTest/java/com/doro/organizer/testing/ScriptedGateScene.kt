package com.doro.organizer.testing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.doro.organizer.MainActivity
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.session.GateState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Rule

/**
 * `MainActivity` over a scripted gate (testing.md § 7, Gate fixtures): [gate], [lock] and [maintenance] replace
 * `SessionGate`, `AppLockController` and `DbMaintenance`, so a test sets any gate state, availability or maintenance
 * status directly; no database, key-state or Keystore is touched. The time, the settings (loaded) and the process
 * lifecycle are fakes, and every announcement is recorded in [announcements]. [hasAnyData] answers
 * `NoteRepository.hasAnyData()`, and [launchFailures] stands for the launch failures of `ExternalActivities`.
 * With [settingsRead] false the settings never emit, as before the first DataStore read.
 */
class ScriptedGateScene(settingsRead: Boolean = true) {
    val gate = ScriptedSessionGate(GateState.Starting(authenticated = false))
    val lock = ScriptedLockController()
    val maintenance = ScriptedMaintenance()
    val time = FakeAppTime()
    val settings = FakeSettingsStore().apply { if (settingsRead) load() }
    val announcements = Announcements()

    /** The answer of `NoteRepository.hasAnyData()`: with no database open, the real one fails. */
    @Volatile var hasAnyData = false

    /** Emitting `kind` reports a launch of `kind` that could not open; the real launch failures are not forwarded. */
    val launchFailures = MutableSharedFlow<ExternalKind>(extraBufferCapacity = 1)

    val app = DoroAppRule(autoStart = false) {
        appTime = time
        settingsStore = settings
        processLifecycle = TestProcessLifecycle()
        sessionGate = gate
        appLockController = lock
        dbMaintenance = maintenance
        wrapAnnouncer = announcements::wrap
        wrapNoteRepository = { real -> ScriptedDataCheck(real) { hasAnyData } }
        wrapExternalActivities = { real -> ScriptedLaunchFailures(real, launchFailures) }
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Moves the gate to [state], then launches `MainActivity`; refused for `Starting` ([checkNotStarting]). */
    fun launch(state: GateState) {
        checkNotStarting(state, "launch")
        gate.moveTo(state)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * Recreates the launched `MainActivity` as a rotation does: its state saved, the ViewModelStore kept. Refused while
     * the gate is `Starting` ([checkNotStarting]).
     */
    fun recreateActivity() {
        recreatable().recreate()
    }

    /**
     * Recreates the launched `MainActivity` as after Android closed the app in the background: its state saved, every
     * ViewModel gone, so each is rebuilt from that state; the container (the gate) stays, as startup would restore it.
     * Refused while the gate is `Starting` ([checkNotStarting]).
     */
    fun recreateAfterProcessDeath() {
        recreatable().recreateAfterProcessDeath()
    }

    private fun recreatable(): ActivityScenario<MainActivity> = checkRecreatable(scenario, gate.state.value)

    /**
     * Recreates the launched `MainActivity` as a rotation does while the gate is `Starting`, without the blocking waits
     * that [recreateActivity] refuses there ([checkNotStarting]): the recreated activity keeps its splash screen, so it
     * draws nothing and the main thread never idles until the gate leaves `Starting`. Returns once the new activity's
     * first layout pass has run, so its composition was built in `Starting` from the saved state. The test then moves
     * the gate on before any Compose or idle wait.
     */
    fun recreateWhileStarting() {
        check(gate.state.value is GateState.Starting) { "the gate is not Starting: use recreateActivity" }
        val launched = checkNotNull(scenario) { "MainActivity is not launched" }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val laidOut = CountDownLatch(1)
        var previous: MainActivity? = null // main thread only
        val firstLayoutOfTheNewActivity = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity || activity === previous) return
                // Registered before the window is attached: the first traversal attaches the composition, lays it
                // out, then dispatches pre-draw to every listener, the splash screen's (which cancels the draw) too.
                val observer = activity.window.decorView.viewTreeObserver
                observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                        laidOut.countDown()
                        return true
                    }
                })
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(firstLayoutOfTheNewActivity)
        try {
            // Activity.recreate only schedules the relaunch; ActivityScenario follows the new instance.
            launched.onActivity { activity ->
                previous = activity
                activity.recreate()
            }
            check(laidOut.await(GateScene.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the recreated MainActivity was never laid out"
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(firstLayoutOfTheNewActivity)
        }
    }

    /** Runs [block] on the main thread with the launched `MainActivity`. */
    fun onActivity(block: (MainActivity) -> Unit) {
        checkNotNull(scenario) { "not launched" }.onActivity(block)
    }

    /** Closes `MainActivity`, if launched, and forgets it; the `@After` of [ScriptedGateComposeTest] calls it. */
    fun closeActivity() {
        scenario?.close()
        scenario = null
    }
}

/** A Compose test over a [ScriptedGateScene]: [compose] finds the nodes of the `MainActivity` that [scene] launches. */
abstract class ScriptedGateComposeTest(settingsRead: Boolean = true) {
    val scene = ScriptedGateScene(settingsRead)

    @get:Rule(order = 0)
    val app = scene.app

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    val ui by lazy { GateUi(compose) }

    @After
    fun tearDownActivity() {
        scene.closeActivity()
    }
}

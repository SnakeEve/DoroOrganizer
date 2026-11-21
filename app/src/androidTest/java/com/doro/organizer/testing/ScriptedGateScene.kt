package com.doro.organizer.testing

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.doro.organizer.MainActivity
import com.doro.organizer.core.external.ExternalKind
import com.doro.organizer.session.GateState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Rule

/**
 * `MainActivity` over a scripted gate (testing.md § 7, Gate fixtures): [gate], [lock] and [maintenance] replace
 * `SessionGate`, `AppLockController` and `DbMaintenance`, so a test sets any gate state, availability or maintenance
 * status directly; no database, key-state or Keystore is touched. The time, the settings (loaded) and the process
 * lifecycle are fakes, and every announcement is recorded in [announcements]. [hasAnyData] answers
 * `NoteRepository.hasAnyData()`, and [launchFailures] stands for the launch failures of `ExternalActivities`.
 */
class ScriptedGateScene {
    val gate = ScriptedSessionGate(GateState.Starting(authenticated = false))
    val lock = ScriptedLockController()
    val maintenance = ScriptedMaintenance()
    val time = FakeAppTime()
    val settings = FakeSettingsStore().apply { load() }
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

    /** Moves the gate to [state], then launches `MainActivity`. */
    fun launch(state: GateState) {
        gate.moveTo(state)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Recreates the launched `MainActivity` as a rotation does: its state saved, the ViewModelStore kept. */
    fun recreateActivity() {
        checkNotNull(scenario) { "not launched" }.recreate()
    }

    /** Runs [block] on the main thread with the launched `MainActivity`. */
    fun onActivity(block: (MainActivity) -> Unit) {
        checkNotNull(scenario) { "not launched" }.onActivity(block)
    }

    fun finish() = scenario?.close()
}

/** A Compose test over a [ScriptedGateScene]: [compose] finds the nodes of the `MainActivity` that [scene] launches. */
abstract class ScriptedGateComposeTest {
    val scene = ScriptedGateScene()

    @get:Rule(order = 0)
    val app = scene.app

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    val ui by lazy { GateUi(compose) }

    @After
    fun closeActivity() {
        scene.finish()
    }
}

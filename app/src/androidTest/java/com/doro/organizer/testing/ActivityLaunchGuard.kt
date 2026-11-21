package com.doro.organizer.testing

import androidx.test.core.app.ActivityScenario
import com.doro.organizer.MainActivity
import com.doro.organizer.session.GateState

/**
 * Fails at once, instead of hanging, when a scene would launch or recreate `MainActivity` while the gate, in
 * [state], is `Starting`. `MainActivity` keeps its splash screen while the gate is `Starting` (its
 * `setKeepOnScreenCondition`), so the new activity draws no first frame and `ActivityScenario` waits for it with no
 * timeout: `Instrumentation.startActivitySync` on a launch, `waitForIdleSync` on a recreate. A scripted gate never
 * leaves `Starting` by itself, so a test launches or recreates in a later state and moves the gate to `Starting` with
 * the activity up, or recreates in `Starting` without the blocking wait ([ScriptedGateScene.recreateWhileStarting]);
 * a real gate is awaited past `Starting` first. [action] names the refused step in the message.
 */
internal fun checkNotStarting(state: GateState, action: String) = check(state !is GateState.Starting) {
    "cannot $action MainActivity while the gate is Starting: its splash screen stays up, so ActivityScenario would " +
        "wait forever; $action in a later state, then move the gate to Starting with the activity up"
}

/** The launched [scenario], to recreate it as a scene does; refused while the gate, in [state], is `Starting`. */
internal fun checkRecreatable(
    scenario: ActivityScenario<MainActivity>?,
    state: GateState,
): ActivityScenario<MainActivity> {
    val launched = checkNotNull(scenario) { "MainActivity is not launched" }
    checkNotStarting(state, "recreate")
    return launched
}

package com.doro.organizer.testing

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.doro.organizer.MainActivity

/**
 * Recreates this `MainActivity` as after Android closed the app in the background: its state saved, every ViewModel
 * gone, so each is rebuilt from that state. The activity is stopped first, so no frame can build a ViewModel between
 * the clear and the recreate; it then returns to the state it was in.
 */
fun ActivityScenario<MainActivity>.recreateAfterProcessDeath() {
    val before = state
    moveToState(Lifecycle.State.CREATED)
    onActivity { it.viewModelStore.clear() }
    recreate()
    moveToState(before)
}

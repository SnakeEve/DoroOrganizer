package com.doro.organizer.testing

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** Replaces `ProcessLifecycleOwner` in the container, so a test moves the process lifecycle (testing.md § 7). */
class TestProcessLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry

    /** Dispatches every event between the current state and [state] to the observers. */
    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

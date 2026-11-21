package com.doro.organizer.testing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The Bundle `MainActivity.onSaveInstanceState` filled while [block] ran (testing.md L-03). [addToState] may add
 * entries to it before it leaves the process, so they cross Binder with the activity's own state (testing.md I-38).
 * [block] may return as soon as the activity reports its stop, as `ActivityScenario.moveToState(CREATED)` does: the
 * save that follows the stop is waited for here.
 */
fun captureSavedState(addToState: (Bundle) -> Unit = {}, block: () -> Unit): Bundle {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as Application
    var saved: Bundle? = null
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            addToState(outState)
            saved = outState
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    app.registerActivityLifecycleCallbacks(callbacks)
    try {
        block()
        // From API 28 ActivityThread calls onSaveInstanceState after onStop, in the same main-thread message, while
        // androidx.test signals STOPPED from inside onStop: a main-thread barrier returns only after that message, so
        // the save has run (and `saved` is visible to this thread) by then.
        instrumentation.runOnMainSync {}
    } finally {
        app.unregisterActivityLifecycleCallbacks(callbacks)
    }
    return checkNotNull(saved) { "onSaveInstanceState did not run" }
}

/**
 * The state Compose saved for the activity's composition: the nested Bundle under the `SaveableStateRegistry:<id>`
 * key of the activity's `SavedStateRegistry`; null when Compose saved nothing.
 */
fun Bundle.composeSavedState(): Bundle? {
    for (key in keySet()) {
        @Suppress("DEPRECATION") // the value's type is not known up front
        val nested = get(key) as? Bundle ?: continue
        if (key.startsWith("SaveableStateRegistry:")) return nested
        nested.composeSavedState()?.let { return it }
    }
    return null
}

/** The bytes of [this] as a Binder transaction would carry them. */
fun Bundle.marshalled(): ByteArray {
    val parcel = Parcel.obtain()
    try {
        parcel.writeBundle(this)
        return parcel.marshall()
    } finally {
        parcel.recycle()
    }
}

/** True when [text] appears in the marshalled bytes as a Parcel string (UTF-16) or a serialized one (UTF-8). */
fun ByteArray.holdsText(text: String): Boolean =
    contains(text.toByteArray(Charsets.UTF_16LE)) || contains(text.toByteArray(Charsets.UTF_8))

private fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

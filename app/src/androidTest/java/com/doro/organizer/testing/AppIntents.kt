package com.doro.organizer.testing

import android.content.Intent
import androidx.test.espresso.intent.Intents
import com.doro.organizer.MainActivity

/**
 * The intents Espresso-Intents recorded that the app sent, without the activity starts `ActivityScenario` makes
 * itself. androidx.test core 1.7.0 launches through `Instrumentation.startActivitySync`, which
 * `MonitoringInstrumentation` reports to Espresso-Intents like any other start, so after `Intents.init()` the
 * recording also holds the scene's own `MainActivity` launch and the `InstrumentationActivityInvoker` activities
 * that `moveToState` and `close` start (for example `EmptyActivity`). Only those exact starts are dropped: the
 * `ACTION_MAIN`/`CATEGORY_LAUNCHER` launch of `MainActivity` (`Intent.makeMainActivity`) and the invoker's own
 * activities, so every other intent counts, one for `MainActivity` too. Call it between `Intents.init()` and
 * `Intents.release()`.
 */
fun appIntents(): List<Intent> = Intents.getIntents().filterNot { it.isScenarioStart() }

private fun Intent.isScenarioStart(): Boolean {
    val className = component?.className ?: return false
    val launch = className == MainActivity::class.java.name && action == Intent.ACTION_MAIN &&
        hasCategory(Intent.CATEGORY_LAUNCHER)
    return launch || className.startsWith(INVOKER_ACTIVITIES)
}

/** The prefix of `InstrumentationActivityInvoker`'s nested activities, such as `$EmptyActivity`. */
private const val INVOKER_ACTIVITIES = "androidx.test.core.app.InstrumentationActivityInvoker\$"

package com.doro.organizer

import android.app.Activity
import android.app.Application

/** Release: no StrictMode and no test configuration (architecture.md, Per-build-type files). */
internal object DebugTools {
    fun install(app: Application) = Unit

    fun configure(activity: Activity) = Unit
}

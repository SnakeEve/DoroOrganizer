package com.doro.organizer.core.time

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Forwards the process lifecycle to [DefaultAppTime]'s entry points (architecture.md § Time, triggers 1 and 2). */
class AppTimeLifecycleObserver(private val time: DefaultAppTime) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = time.onStart()

    override fun onResume(owner: LifecycleOwner) = time.onResume()

    override fun onStop(owner: LifecycleOwner) = time.onStop()
}

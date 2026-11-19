package com.doro.organizer

import android.app.Application

open class DoroApplication : Application() {
    /** Internal so that the instrumented test rule can install its own container (testing.md § 7). */
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return // the debug :crashtest process builds its own container
        DebugTools.install(this) // StrictMode in debug, no-op in release
        startApp()
    }

    /** The test application overrides this with an empty body, so no container exists before a test sets one. */
    protected open fun startApp() {
        container = createContainer()
        container.installProcessObservers()
        container.sessionGate.start() // Starting; the live Room instance is never built here (DEC-90)
    }

    protected open fun createContainer(): AppContainer = AppContainer(this)
}

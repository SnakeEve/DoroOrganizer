package com.doro.organizer

import android.app.Application

open class DoroApplication : Application() {
    /** Internal so that the instrumented test rule can install its own container (testing.md § 7). */
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return // a secondary process starts nothing here
        startApp()
    }

    /** The test application overrides this with an empty body, so no container exists before a test sets one. */
    protected open fun startApp() {
        container = createContainer()
        container.installProcessObservers()
    }

    protected open fun createContainer(): AppContainer = AppContainer(this)
}

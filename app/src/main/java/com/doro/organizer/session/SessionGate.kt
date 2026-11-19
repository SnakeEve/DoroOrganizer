package com.doro.organizer.session

import kotlinx.coroutines.flow.StateFlow

/** The startup and lock state machine (DEC-90, architecture.md § SessionGate): a pure reducer plus an effect runner. */
interface SessionGate {
    val state: StateFlow<GateState>

    /** `DoroApplication.startApp()`: T1. */
    fun start()

    /** Main thread only: applies [reduce] synchronously with the current `AppTime.elapsedMillis()`. */
    fun dispatch(event: GateEvent)
}

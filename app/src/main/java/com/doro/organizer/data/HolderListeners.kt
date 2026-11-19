package com.doro.organizer.data

import com.doro.organizer.core.result.AppError

/**
 * Receives runtime corruption of the current instance (db-lifecycle.md § Runtime corruption routing); implemented by
 * SessionGate. Called on the failing thread, every time corruption is detected.
 */
fun interface CorruptionListener {
    fun onRuntimeCorruption()
}

/**
 * Receives an environmental failure that survived the retries of an observed flow (db-lifecycle.md § Observation):
 * [error] is `StorageFull` or `DatabaseUnavailable`. May be called from any thread.
 */
fun interface FlowProblemListener {
    fun onFlowProblem(error: AppError)
}

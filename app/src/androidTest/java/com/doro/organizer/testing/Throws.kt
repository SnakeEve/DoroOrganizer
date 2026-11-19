package com.doro.organizer.testing

/** JUnit's `assertThrows` for suspending code: returns the [T] that [block] threw, rethrows anything else. */
suspend inline fun <reified T : Throwable> assertThrowsSuspending(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("expected ${T::class.java.simpleName}")
}

package com.doro.organizer.core.result

/**
 * The result of every public suspend method of the repositories (architecture.md § Error model). Expected failures
 * are [Err] values; anything else is a bug and propagates. A vanished row is a success value, never an error.
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>

    data class Err(val error: AppError) : AppResult<Nothing>
}

/**
 * Expected failures (architecture.md § Error model). Each case is added together with the component that produces
 * it.
 */
sealed interface AppError {
    /** Input the UI should have prevented; [rule] names which (data-layer.md INV-9). */
    data class InvalidInput(val rule: InputRule) : AppError
}

enum class InputRule {
    /** A new person or project name that is blank after `trim()` (DEC-32). */
    BlankName,
}

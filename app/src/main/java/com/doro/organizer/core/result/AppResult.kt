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

    /** `SQLiteFullException`, `ENOSPC` or `EDQUOT`; the transaction rolled back (db-lifecycle.md § Classes). */
    data object StorageFull : AppError

    /** The database is closed or was replaced, or a transient I/O failure occurred; the caller keeps its input. */
    data object DatabaseUnavailable : AppError
}

enum class InputRule {
    /** A new person or project name that is blank after `trim()` (DEC-32). */
    BlankName,
}

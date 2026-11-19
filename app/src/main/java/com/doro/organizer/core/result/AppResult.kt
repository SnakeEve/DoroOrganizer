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
    /** The database password failed its inner GCM tag (DEC-62). */
    data object WrongPassword : AppError

    /** A password backoff runs until [retryAtElapsedMs], on the `AppTime.elapsedMillis()` base (DEC-62). */
    data class Throttled(val retryAtElapsedMs: Long) : AppError

    /** Input the UI should have prevented; [rule] names which (data-layer.md INV-9, security.md § 6.1). */
    data class InvalidInput(val rule: InputRule) : AppError

    /** `SQLiteFullException`, `ENOSPC` or `EDQUOT`, or a failed free-space check (db-lifecycle.md § Classes). */
    data object StorageFull : AppError

    /** A SAF or FileProvider I/O error, a null stream or a revoked grant (db-lifecycle.md § Classification table). */
    data object FileAccess : AppError

    /** A Keystore failure while obtaining or changing a key, or an unusable key-state (DEC-86). */
    data object KeyUnavailable : AppError

    /** No successful authentication through the biometric slot, or the slot proved unusable there (DEC-63, DEC-65). */
    data object AuthFailed : AppError

    /** DbMaintenance already runs an operation. */
    data object Busy : AppError

    /** The database is closed or was replaced, or a transient I/O failure occurred; the caller keeps its input. */
    data object DatabaseUnavailable : AppError

    /** DbMaintenance only: the commit could not be read back; nothing was deleted (db-lifecycle.md § Generation commit). */
    data object OutcomeUnknown : AppError

    /** DbMaintenance only: an unexpected failure after the rollback; the data was not changed. */
    data object Unexpected : AppError
}

enum class InputRule {
    /** A new person or project name that is blank after `trim()` (DEC-32). */
    BlankName,

    /** A password with a lone surrogate, which UTF-8 cannot encode (security.md § 6.1). */
    PasswordMalformed,

    /** Fewer than 8 code points after NFC (security.md § 6.1). */
    PasswordTooShort,

    /** The two entries of a new password differ after NFC. */
    PasswordMismatch,

    /** The "I understand this cannot be recovered" checkbox is not ticked. */
    AcknowledgementMissing,
}

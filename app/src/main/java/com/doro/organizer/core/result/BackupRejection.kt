package com.doro.organizer.core.result

/**
 * Why a restore candidate was rejected (db-lifecycle.md § Restore validation): the four message categories of ui.md
 * § 8.7, one message each. A wrong backup password is not a rejection: the password question repeats.
 */
enum class BackupRejection {
    /** Not a SQLite or SQLCipher database, or not this app's (`application_id`). */
    NotABackup,

    /** `user_version` above `AppDatabase.VERSION`. */
    NewerVersion,

    /** This app's file, but a schema this build does not accept. */
    Incompatible,

    /** This app's file, but its content fails a check. */
    Damaged,
}

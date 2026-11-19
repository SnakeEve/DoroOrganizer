package com.doro.organizer.data.maintenance

/**
 * The steps of every operation (db-lifecycle.md § Pipeline steps and fault hook), in the order the doc lists them.
 * New steps are appended, never reordered.
 */
enum class PipelineStep {
    PRECHECK, PREPARE_KEY, CLOSE_ROOM, CHECK_SOURCE, CREATE_EMPTY, EXPORT, VERIFY_TARGET,
    WRITE_DOCUMENT, PUBLISH_SHARE,
    COPY_INPUT, OPEN_INPUT, VALIDATE_INPUT, MIGRATE_INPUT, CHECK_SCHEMA, UNLOCK_DEVICE_KEY, AWAIT_CONFIRMATION,
    MARK_RESET, RENAME, COMMIT, DATA_RESET, REOPEN, DELETE_OLD, DELETE_KEK,
    ERASE_KEY_STATE, ERASE_FILES, RESET_SETTINGS, CLEANUP,
    UNLOCK_BIOMETRIC, PREPARE_BIO_SLOT, COMMIT_BIO_SLOT, DELETE_BIO_KEY,
}

/**
 * Runs immediately before each step: every earlier step has completed and this one has not started. [op] is null for
 * `completeStartup` and the biometric slot commits. Production binds [None]; tests bind hooks that throw, block or
 * kill the process (testing.md § 5).
 */
fun interface MaintenanceFaultHook {
    suspend fun before(op: MaintenanceOp?, step: PipelineStep)

    companion object {
        val None = MaintenanceFaultHook { _, _ -> }
    }
}

package com.doro.organizer.data.maintenance

// The steps after a committed Restore or Start fresh (db-lifecycle.md § Restore steps 18–20, § Start fresh steps
// 6–8). They never undo the commit: a failure is logged by class and left to startup reconciliation, except a KEK
// that a failed `DELETE_KEK` left, which startup keeps for the next encryption to reuse or replace (CS-20, CS-23).

/**
 * `DELETE_OLD` of a commit from a recovery origin: every generation file set except [keep], that is the damaged or
 * unreadable files the user has now explicitly replaced (DEC-85, DEC-86).
 */
internal suspend fun MaintenanceDeps.deleteGenerationsExcept(run: OperationRun, keep: Long) =
    bestEffort("delete old generations") {
        run.step(PipelineStep.DELETE_OLD)
        (files.generationsOnDisk().keys - keep).forEach { files.deleteGeneration(it) }
    }

/**
 * `DELETE_KEK`, then `DELETE_BIO_KEY`, after a committed switch to `PLAIN` that installed no DEK: the KEK and the
 * biometric key wrap nothing committed any more, also when the key-state was missing or invalid before.
 */
internal suspend fun MaintenanceDeps.deleteReplacedKeys(run: OperationRun) {
    bestEffort("delete KEK") {
        run.step(PipelineStep.DELETE_KEK)
        keyVault.deleteKek()
    }
    bestEffort("delete biometric key") {
        run.step(PipelineStep.DELETE_BIO_KEY)
        keyVault.deleteBiometricKey()
    }
}

/** `CLEANUP` of a committed operation: `restore/` and `staging/` emptied. */
internal suspend fun MaintenanceDeps.cleanup(run: OperationRun) = bestEffort("cleanup") {
    run.step(PipelineStep.CLEANUP)
    files.clearDir(files.restoreDir)
    files.clearDir(files.stagingDir)
}

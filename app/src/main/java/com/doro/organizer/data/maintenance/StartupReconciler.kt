package com.doro.organizer.data.maintenance

import com.doro.organizer.core.log.AppLog
import com.doro.organizer.data.DbFailure
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.classify
import com.doro.organizer.data.nextGeneration
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyVaultException
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Startup reconciliation (effect E1) and its completion in `Opening` (effect E2), db-lifecycle.md § Startup
 * reconciliation and § Completing startup in Opening. The caller holds the operation mutex for every member.
 * Reconciliation opens no connection, needs no key, never writes `db-state.json` and deletes generation files only
 * when a valid key-state names an existing live file (DEC-86). It also deletes the biometric Keystore keys that no
 * committed `bioSlot` names (DEC-98), except under the `KeyUnavailable` decision, whose keys go only with the user's
 * Restore or Start fresh (DEC-86).
 */
internal class StartupReconciler(
    private val deps: MaintenanceDeps,
    private val marker: DataResetMarker,
) {
    private val files get() = deps.files

    /** The decision `completeStartup` still has to execute; null once done (or for `Proceed`). */
    private var pending: StartupDecision? = null

    /** An `Unknown` commit outcome discarded the decision: the next completion reconciles again first. */
    private var reconcileAgain = false

    /** `cacheDir/share/` is emptied only at the first reconciliation of a process, a cold start. */
    private var shareSwept = false

    suspend fun reconcile(): StartupDecision = reconcile(resetFirstRunSettings = true)

    suspend fun complete(): StartupCompletion {
        if (reconcileAgain) {
            reconcileAgain = false
            reconcile(resetFirstRunSettings = false)
        }
        return when (val decision = pending) {
            null, is StartupDecision.Proceed -> StartupCompletion.Done
            StartupDecision.KeyUnavailable -> StartupCompletion.KeyUnavailable
            StartupDecision.FirstRun -> firstRun()
            is StartupDecision.AdoptPlain -> commit(staged = null, generation = decision.generation)
        }
    }

    private suspend fun reconcile(resetFirstRunSettings: Boolean): StartupDecision {
        files.ensureDirs()
        sweepTemporaryDirs()
        val read = readKeyState()
        val onDisk = files.generationsOnDisk().keys
        val mainFiles = onDisk.filterTo(mutableSetOf(), files::hasMainFile)
        marker.applyAtStartup(read)
        val decision = startupDecision(read, mainFiles) { RawConnections.isPlaintext(files.live(it)) }
        val live = (read as? KeyStateRead.Valid)?.state?.generation?.let(files::live)
        val liveUsable = live != null && live.isFile && live.length() > 0
        unreferencedAtStartup(read, onDisk, liveUsable).forEach { generation ->
            if (!files.deleteGeneration(generation)) AppLog.d("startup: a generation set was not deleted")
        }
        // DEC-98: biometric keys that no committed bioSlot names, left by an arming that never committed or by a crash
        // before a sweep. Never under KeyUnavailable: DEC-86 deletes nothing automatically there.
        if (decision != StartupDecision.KeyUnavailable) {
            deps.bestEffort("startup biometric key sweep") { deps.keyVault.deleteUnreferencedBiometricKeys() }
        }
        if (decision == StartupDecision.FirstRun && resetFirstRunSettings) {
            // security.md § 5.4 step 4: completes an Erase interrupted after its files were deleted; a no-op otherwise.
            deps.settingsStore.resetSecurity()
            deps.settingsStore.onDataReplaced()
        }
        deps.keyMode.value = (read as? KeyStateRead.Valid)?.state?.slot?.mode
        pending = decision.takeUnless { it is StartupDecision.Proceed }
        return decision
    }

    /** Step 2: staging and restore at every reconciliation; share only at a cold start (a receiver may still read). */
    private fun sweepTemporaryDirs() {
        val dirs = listOf(files.stagingDir, files.restoreDir) + if (shareSwept) emptyList() else listOf(files.shareDir)
        shareSwept = true
        dirs.forEach { if (!files.clearDir(it)) AppLog.d("startup: a temporary file was not deleted") }
    }

    private suspend fun readKeyState(): KeyStateRead = try {
        deps.keyVault.readState()?.let(KeyStateRead::Valid) ?: KeyStateRead.Missing
    } catch (e: KeyVaultException) {
        KeyStateRead.Invalid
    }

    /**
     * First run. A Transient end empties `staging/` (db-lifecycle.md § Files on disk), so a Retry numbers the next
     * file as if this attempt never ran; after `Unknown` the next reconciliation does that.
     */
    private suspend fun firstRun(): StartupCompletion {
        val completion = createFirstGeneration()?.let { (generation, staged) -> commit(staged, generation) }
            ?: StartupCompletion.Transient
        if (completion == StartupCompletion.Transient && !reconcileAgain) {
            deps.bestEffort("startup cleanup") { files.clearDir(files.stagingDir) }
        }
        return completion
    }

    /** An empty plaintext generation in `staging/`, verified: (number, file); null when a retry may help. */
    private suspend fun createFirstGeneration(): Pair<Long, File>? = transientOrNull {
        deps.faultHook.before(null, PipelineStep.PRECHECK)
        if (!deps.hasFreeSpace(0)) return@transientOrNull null
        val generation = nextGeneration(0, files.generationNumbersInUse())
        val staged = File(files.stagingDir, "doro-$generation.db")
        deps.faultHook.before(null, PipelineStep.CREATE_EMPTY)
        createEmptyDatabase(deps.context, staged)
        deps.faultHook.before(null, PipelineStep.VERIFY_TARGET)
        verifyTarget(staged, ByteArray(0))
        generation to staged
    }

    /** Commits the first-run file ([staged]) or adopts [generation] in place, both as `PLAIN`. */
    private suspend fun commit(staged: File?, generation: Long): StartupCompletion {
        val slot = deps.keyVault.prepare(KeyMode.PLAIN, null).slot
        val result = commitGeneration(files, deps.keyVault, staged, generation, slot, null) {
            deps.faultHook.before(null, it)
        }
        return when (result.outcome) {
            CommitOutcome.NotCommitted -> StartupCompletion.Transient // the decision is kept for Retry
            CommitOutcome.Unknown -> {
                pending = null
                reconcileAgain = true
                StartupCompletion.Transient
            }
            CommitOutcome.Committed -> {
                deps.keyMode.value = KeyMode.PLAIN
                pending = null
                deps.bestEffort("startup delete") {
                    deps.faultHook.before(null, PipelineStep.DELETE_OLD)
                    (files.generationsOnDisk().keys - generation).forEach { files.deleteGeneration(it) }
                }
                StartupCompletion.Done
            }
        }
    }

    /** A storage-full or Transient failure is null (`OpenFailed(Transient)`, decision kept); anything else is a bug. */
    private inline fun <T> transientOrNull(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = classify(e)
        if (failure != DbFailure.StorageFull && failure != DbFailure.Transient) throw e
        null
    }
}

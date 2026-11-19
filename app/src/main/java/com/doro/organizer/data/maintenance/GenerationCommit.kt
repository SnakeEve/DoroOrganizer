package com.doro.organizer.data.maintenance

import android.system.Os
import com.doro.organizer.core.io.syncDirectory
import com.doro.organizer.data.DbFiles
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class CommitOutcome { Committed, NotCommitted, Unknown }

/** [cause]: what made the commit fail; classified by the caller (db-lifecycle.md § Failure classification). */
class CommitResult(val outcome: CommitOutcome, val cause: Throwable? = null) {
    override fun toString() = "CommitResult(outcome=$outcome)"
}

/** The read-back test of every key-state commit: generation, key slot and biometric slot equal what was written. */
internal fun KeyState.matches(generation: Long, slot: KeySlot, bio: BioSlot?): Boolean =
    this.generation == generation && this.slot.sameAs(slot) && bioMatches(bioSlot, bio)

/**
 * [actual]: what the file holds (a dead slot reads back as null); [expected]: what was passed to `KeyVault.commit`.
 * Every key-state write runs under the operation mutex, so the file holds exactly what the last commit wrote.
 */
internal fun bioMatches(actual: BioSlot?, expected: BioSlot?): Boolean =
    if (expected == null) actual == null else actual != null && actual.sameAs(expected)

/**
 * The only way a file becomes live (db-lifecycle.md § Generation commit, DEC-80, DEC-87): sync, rename into
 * `databases/`, sync the directory, rewrite the key-state (THE commit point), sync, read back. [staged] null adopts
 * the existing `doro-<generation>.db` in place. The caller holds the operation mutex, has verified [staged] and has
 * set its `commitStarted` flag. Nothing in here can be cancelled, and `Throwable` is caught on purpose: from the
 * key-state write on, only the read-back may say that nothing was committed.
 */
internal suspend fun commitGeneration(
    files: DbFiles,
    keyVault: KeyVault,
    staged: File?,
    generation: Long,
    slot: KeySlot,
    bio: BioSlot?,
    step: suspend (PipelineStep) -> Unit,
): CommitResult = withContext(NonCancellable) {
    val live = files.live(generation)
    var renamed = false
    try {
        if (staged != null) {
            step(PipelineStep.RENAME)
            RandomAccessFile(staged, "r").use { it.fd.sync() } // the staged bytes are on disk
            check(files.deleteGeneration(generation)) { "stale generation files" } // nothing stale under the new name
            Os.rename(staged.path, live.path) // atomic: one filesystem
            renamed = true
            syncDirectory(files.databasesDir) // the rename is durable before the key-state names it
        }
        step(PipelineStep.COMMIT)
    } catch (t: Throwable) {
        if (renamed) runCatching { files.deleteGeneration(generation) } // never referenced
        return@withContext CommitResult(CommitOutcome.NotCommitted, t)
    }
    val writeError = try {
        keyVault.commit(generation, slot, bio)
        null
    } catch (t: Throwable) {
        t
    }
    val committed = try {
        syncDirectory(files.noBackupDir) // the commit is durable before anything is deleted
        keyVault.readState()?.matches(generation, slot, bio) == true
    } catch (t: Throwable) {
        return@withContext CommitResult(CommitOutcome.Unknown, t) // state unknown: delete nothing
    }
    if (committed) return@withContext CommitResult(CommitOutcome.Committed)
    if (renamed) runCatching { files.deleteGeneration(generation) } // the previous state is still in force
    CommitResult(CommitOutcome.NotCommitted, writeError)
}

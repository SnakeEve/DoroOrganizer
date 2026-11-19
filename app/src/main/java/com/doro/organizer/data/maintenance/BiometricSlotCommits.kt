package com.doro.organizer.data.maintenance

import com.doro.organizer.core.io.syncDirectory
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.isStorageFull
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.WrapResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * The slot-only key-state commits (DEC-80, db-lifecycle.md § Biometric slot commits): the same generation and a
 * byte-identical key slot, with only the biometric slot added, replaced or removed. No database file is touched, no
 * status is published and no gate event is raised. The caller holds the operation mutex where stated.
 */
internal class BiometricSlotCommits(private val deps: MaintenanceDeps) {
    private val keyVault get() = deps.keyVault

    /**
     * Creation, under the operation mutex. A hook failure or a bug is `Unexpected`, leaves the key-state untouched and,
     * like every early exit, deletes the key the prompt's cipher was created on: it wraps nothing committed.
     */
    suspend fun create(cipher: SlotCipher): AppResult<Unit> = try {
        createLocked(cipher)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        dropKey(AppError.Unexpected)
    }

    private suspend fun createLocked(cipher: SlotCipher): AppResult<Unit> {
        deps.faultHook.before(null, PipelineStep.PREPARE_BIO_SLOT)
        val state = deps.committedState() ?: return dropKey(AppError.KeyUnavailable)
        if ((deps.holder.state.value as? HolderState.Open)?.generation != state.generation) {
            return dropKey(AppError.DatabaseUnavailable)
        }
        if (state.slot.mode != KeyMode.PASSWORD) {
            deleteKey() // a stale prompt after a key change: only PASSWORD mode has a slot
            return AppResult.Ok(Unit)
        }
        // The holder opened exactly the generation the key-state names, and only holders of the mutex change either,
        // so the live key is the DEK of state.slot.
        val live = deps.holder.copyLiveKey() ?: return dropKey(AppError.DatabaseUnavailable)
        val wrap = try {
            deps.faultHook.before(null, PipelineStep.COMMIT_BIO_SLOT)
            keyVault.wrapForBiometric(cipher, live)
        } finally {
            live.fill(0)
        }
        val bio = when (wrap) {
            is WrapResult.Wrapped -> wrap.bioSlot
            WrapResult.BiometricUnusable -> return AppResult.Err(AppError.AuthFailed) // KeyVault deleted the alias
        }
        var writeError: Throwable? = null
        val readBack = try {
            writeError = commitSlot(state, bio)
            keyVault.readState()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return repair(state) // the directory sync or the read-back failed: the outcome is unknown
        }
        return when {
            readBack != null && readBack.matches(state.generation, state.slot, bio) -> AppResult.Ok(Unit)
            readBack != null && readBack.generation == state.generation && readBack.slot.sameAs(state.slot) -> {
                // The write did not land; an older slot was already dead because preparing the cipher replaced its key.
                dropKey(if (writeError?.isStorageFull() == true) AppError.StorageFull else AppError.AuthFailed)
            }
            else -> repair(state)
        }
    }

    /**
     * The `Unknown` read-back of a creation: one rewrite of the key-state this operation read as valid moments ago,
     * without the biometric slot (DEC-80); never of one that was missing or invalid before. The database stays open
     * and nothing restarts: the next cold start decides from the file on disk.
     */
    private suspend fun repair(state: KeyState): AppResult<Unit> {
        try {
            commitSlot(state, null)
            keyVault.readState()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.d("bio slot repair failed: ${t.javaClass.simpleName}")
        }
        return dropKey(AppError.OutcomeUnknown)
    }

    /**
     * Removal: the key first, without the operation mutex, so the fingerprint path is gone before the file changes
     * (DEC-65); a failed delete is logged and the removal continues. [tryLockOp] takes the operation mutex for the
     * rewrite and releases it with the returned function; a held mutex skips the rewrite and leaves a dead slot,
     * which is tolerated. Never throws, never an error in v1.
     */
    suspend fun remove(tryLockOp: () -> (() -> Unit)?): AppResult<Unit> {
        deps.bestEffort("bio key delete") {
            deps.faultHook.before(null, PipelineStep.DELETE_BIO_KEY)
            keyVault.deleteBiometricKey()
        }
        val unlock = tryLockOp() ?: return AppResult.Ok(Unit)
        try {
            deps.faultHook.before(null, PipelineStep.COMMIT_BIO_SLOT)
            val state = deps.committedState() ?: return AppResult.Ok(Unit) // Missing or Invalid: write nothing (DEC-86)
            if (state.bioSlot == null && !state.deadBioSlot) return AppResult.Ok(Unit)
            commitSlot(state, null)
            val readBack = try {
                keyVault.readState()
            } catch (t: Throwable) {
                null
            }
            val unknown = readBack == null || readBack.generation != state.generation || !readBack.slot.sameAs(state.slot)
            if (unknown) commitSlot(state, null) // the one repair: the same write
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.d("bio slot removal failed: ${t.javaClass.simpleName}")
        } finally {
            unlock()
        }
        return AppResult.Ok(Unit)
    }

    /**
     * `KeyVault.commit` of [state]'s generation and key slot with [bio], then the directory sync; returns the commit's
     * error. A failed sync throws: whether the write is durable is then unknown.
     */
    private suspend fun commitSlot(state: KeyState, bio: BioSlot?): Throwable? {
        val error = try {
            keyVault.commit(state.generation, state.slot, bio)
            null
        } catch (t: Throwable) {
            AppLog.d("bio slot commit failed: ${t.javaClass.simpleName}")
            t
        }
        syncDirectory(deps.files.noBackupDir)
        return error
    }

    private suspend fun dropKey(error: AppError): AppResult<Unit> {
        deleteKey()
        return AppResult.Err(error)
    }

    /** Best effort: a key left behind wraps nothing committed and is replaced by the next arm cipher. */
    private suspend fun deleteKey() = deps.bestEffort("bio key delete") { keyVault.deleteBiometricKey() }
}

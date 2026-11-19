package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteException
import com.doro.organizer.core.password.passwordBytes
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.DbFailure
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.classify
import com.doro.organizer.data.longOf
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.UnlockResult
import kotlin.coroutines.cancellation.CancellationException
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * `UNLOCK_DEVICE_KEY` of a restore from `Corrupt` (db-lifecycle.md § Origins and target key, DEC-84, DEC-86): the
 * key of the committed slot again, because `close()` zeroed it when the gate entered `Corrupt` (DEC-64). In
 * `PASSWORD` mode the fingerprint comes first through the biometric slot, the database password otherwise, and the
 * fingerprint once more when the password fails in the Keystore layer while the slot is live. Every key returned is
 * owned by the frame; every other ending throws [RestoreEnd].
 */
internal class RestoreKeyUnlock(private val deps: MaintenanceDeps, private val questions: RestoreQuestions) {
    private val keyVault get() = deps.keyVault

    suspend fun deviceKey(run: OperationRun, state: KeyState): ByteArray = when (state.slot.mode) {
        KeyMode.PLAIN -> ByteArray(0)
        KeyMode.DEVICE -> when (val result = keyVault.openKey(state.slot, null)) {
            is UnlockResult.Key -> run.own(result.rawKey)
            else -> end(AppError.KeyUnavailable)
        }
        KeyMode.PASSWORD -> passwordModeKey(run, state)
    }

    private suspend fun passwordModeKey(run: OperationRun, state: KeyState): ByteArray {
        // A local of this restore, never persisted: the DEC-86 fallback is offered at most once per restore.
        var afterKekFailure = false
        var askFingerprint = state.bioSlot != null
        while (true) {
            if (askFingerprint) {
                fingerprintKey(run, state, afterKekFailure)?.let { return it }
                // The password has just failed in the Keystore layer and cannot work.
                if (afterKekFailure) end(AppError.KeyUnavailable)
            }
            passwordKey(run, state)?.let { return it }
            if (afterKekFailure || !slotIsLive()) end(AppError.KeyUnavailable)
            afterKekFailure = true
            askFingerprint = true
        }
    }

    /** Steps 1 and 2: the slot-released key once the slot-key check accepts it; null continues with the password. */
    private suspend fun fingerprintKey(run: OperationRun, state: KeyState, afterKekFailure: Boolean): ByteArray? {
        val cipher = questions.biometric { run.show(MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure)) } ?: return null
        resumed(run)
        // The fingerprint path touches neither the backoff nor the reminder (security.md § 5.3, § 5.6).
        return when (val result = keyVault.unlockWithBiometric(cipher)) {
            is UnlockResult.Key -> {
                val key = run.own(result.rawKey)
                val check = slotKeyCheck(state.generation, key)
                if (check == SlotKeyCheck.Fits) return key
                // Not the database's key (security.md § 3.7.3): the slot is dead from now on. A key that cannot be
                // checked deletes nothing.
                if (check == SlotKeyCheck.Rejected) keyVault.deleteBiometricKey()
                key.fill(0)
                null
            }
            is UnlockResult.SlotFailed -> null
            else -> end(AppError.KeyUnavailable)
        }
    }

    /**
     * Step 3: the database password until it opens the slot or the user cancels; `KeyVault` counts and persists the
     * backoff and restarts the reminder itself. Null: the Keystore (KEK) layer failed, nothing was counted.
     */
    private suspend fun passwordKey(run: OperationRun, state: KeyState): ByteArray? {
        var error: AppError? = null
        while (true) {
            val chars = questions.password { run.show(MaintenanceStatus.NeedsDatabasePassword(error)) }
            resumed(run)
            // A malformed password was never set: KeyVault rejects the empty array without counting (security.md § 6.3).
            val bytes = passwordBytes(chars) ?: ByteArray(0)
            val result = try {
                keyVault.openKey(state.slot, bytes)
            } finally {
                bytes.fill(0)
            }
            error = when (result) {
                is UnlockResult.Key -> return run.own(result.rawKey)
                is UnlockResult.PasswordRejected -> AppError.WrongPassword
                is UnlockResult.Throttled -> AppError.Throttled(result.retryAtElapsedMs)
                else -> return null
            }
        }
    }

    /** The live-slot definition of security.md § 5.1 in `PASSWORD` mode: the lock is on and the slot is `Live`. */
    private suspend fun slotIsLive(): Boolean =
        deps.settingsStore.snapshot().biometricLock && keyVault.biometricSlotStatus() == BioSlotStatus.Live

    private enum class SlotKeyCheck { Fits, Rejected, Unknown }

    /**
     * Whether [key] opens generation [generation]'s page 1 (step 2). Corruption beyond page 1 proves that page 1
     * decrypted with this key, so it fits; a missing or empty file, or a Transient failure, cannot be checked.
     */
    private fun slotKeyCheck(generation: Long, key: ByteArray): SlotKeyCheck {
        val live = deps.files.live(generation)
        if (!live.isFile || live.length() == 0L) return SlotKeyCheck.Unknown
        return try {
            // A key that is not empty never fits a plaintext file.
            if (RawConnections.readsAsPlaintext(live)) return SlotKeyCheck.Rejected
            RawConnections.open(live, key).use { it.longOf("SELECT count(*) FROM sqlite_master") }
            SlotKeyCheck.Fits
        } catch (e: SQLiteNotADatabaseException) {
            SlotKeyCheck.Rejected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                classify(e) == DbFailure.Transient -> SlotKeyCheck.Unknown
                e is SQLiteException -> SlotKeyCheck.Fits
                else -> throw e
            }
        }
    }

    /** The answer resumed the step; the checking dialog shows again while the key is derived. */
    private fun resumed(run: OperationRun) =
        run.show(MaintenanceStatus.Running(MaintenanceOp.Restore, PipelineStep.UNLOCK_DEVICE_KEY))

    private fun end(error: AppError): Nothing = throw RestoreEnd(AppResult.Err(error))

    override fun toString() = "RestoreKeyUnlock"
}

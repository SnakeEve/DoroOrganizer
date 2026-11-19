package com.doro.organizer.data.maintenance

import android.net.Uri
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BiometricSlotWriter
import com.doro.organizer.security.SlotCipher
import kotlinx.coroutines.flow.StateFlow

/**
 * Every operation that exports, swaps or deletes database files, through one export pipeline (DEC-82,
 * db-lifecycle.md § DbMaintenance pipeline). User operations run detached from their caller, one at a time under the
 * operation mutex (`Err(Busy)` otherwise), and own the password arrays they receive: they zero them in every case.
 */
interface DbMaintenance : BiometricSlotWriter {
    val status: StateFlow<MaintenanceStatus>

    /** Mode of the committed key-state; null before startup has read it or while it is missing or invalid. */
    val keyMode: StateFlow<KeyMode?>

    // SessionGate effects only.

    /** Effect E1, gate `Starting`: empties the temporary directories, applies the data-reset marker and decides. */
    suspend fun reconcileAtStartup(): StartupDecision

    /** Effect E2, before `KeyVault`: executes the remembered decision (first run, adoption); a no-op afterwards. */
    suspend fun completeStartup(): StartupCompletion

    /**
     * Effect E2 in `Opening(viaReset)` only: the raw key a fingerprint reset from `NeedsPassword` committed for
     * [generation]; ownership passes to the caller. Null for any other generation (the kept key is zeroed then).
     */
    fun takeResetKey(generation: Long): ByteArray?

    // Settings, gate Ready.

    /** `keyMode != PLAIN || encryptBackups` (DEC-83); true while either is still unknown (the safe side). */
    fun backupRequiresPassword(): Boolean

    /** Deletes the SAF document the picker created, only when it is known to be empty; failures are ignored. */
    suspend fun discardDocument(target: Uri)

    suspend fun backupToDocument(target: Uri, backupPassword: CharArray?): AppResult<MaintenanceResult>

    suspend fun backupForShare(backupPassword: CharArray?): AppResult<MaintenanceResult>

    suspend fun changeKey(target: KeyMode, currentPassword: CharArray?, newPassword: CharArray?): AppResult<MaintenanceResult>

    // Settings (Ready) and the Corrupt and KeyUnavailable screens: the caller passes the screen's own origin.

    /**
     * Restore from [source] (DEC-84, db-lifecycle.md § Restore), origin `Ready`, `Corrupt` or `KeyUnavailable`. It
     * asks its questions through [status] (`Needs…`) and waits for the matching answer; nothing changes before the
     * user confirmed.
     */
    suspend fun restore(source: Uri, origin: MaintenanceOrigin): AppResult<MaintenanceResult>

    /**
     * Answers `NeedsBackupPassword` or `NeedsDatabasePassword`; null cancels the restore. The array is owned by the
     * restore from the call on and zeroed in every case, also when no such question is pending.
     */
    fun answerPassword(password: CharArray?)

    /**
     * Answers `NeedsBiometricUnlock` with the authorized cipher of the prompt; null: use the password instead (after
     * `afterKekFailure = true` it ends the restore with `Err(KeyUnavailable)`).
     */
    fun answerBiometric(cipher: SlotCipher?)

    /** Answers `NeedsConfirmation`; false cancels the restore. */
    fun answerConfirmation(confirmed: Boolean)

    /** The Cancel button of the checking phase: ends a restore that has not been confirmed yet with `RestoreCancelled`. */
    fun cancelRestore()

    /** Start fresh after its confirmation (DEC-85, DEC-86): origin `Corrupt` or `KeyUnavailable` only. */
    suspend fun startFresh(origin: MaintenanceOrigin): AppResult<MaintenanceResult>

    // Forgot-password screen (DEC-63). Any other origin is a programming error.

    /**
     * Erase all data (security.md § 5.4, db-lifecycle.md § Erase): origin `NeedsPassword` (Room closed), `Ready` (the
     * password screen of `Locked(Ready)`, Room open) or `Corrupt` (the password screen of `Locked(Corrupt)`).
     */
    suspend fun erase(origin: MaintenanceOrigin): AppResult<MaintenanceResult>

    /**
     * The fingerprint password reset (DEC-63) from the Forgot-password screen: origin `NeedsPassword` (Room closed) or
     * `Ready` (the password screen of `Locked(Ready)`, Room open); never `Corrupt`.
     */
    suspend fun resetPasswordWithBiometric(
        origin: MaintenanceOrigin,
        cipher: SlotCipher,
        newPassword: CharArray,
    ): AppResult<MaintenanceResult>

    /** `Finished` -> `Idle` once the UI has shown the result. */
    fun acknowledge()

    /** Every `ProcessLifecycleOwner` ON_START: deletes share files at least one hour old (wall clock). */
    suspend fun sweepShareFiles()
}

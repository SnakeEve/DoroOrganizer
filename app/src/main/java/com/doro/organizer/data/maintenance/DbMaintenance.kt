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

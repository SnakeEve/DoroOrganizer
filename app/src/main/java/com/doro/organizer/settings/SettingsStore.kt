package com.doro.organizer.settings

import com.doro.organizer.model.DraftInsert
import com.doro.organizer.model.EntityKind
import kotlinx.coroutines.flow.StateFlow

/**
 * The only DataStore user (DEC-09, architecture.md § SettingsStore). Every write is one DataStore edit, returns only
 * after the edit has completed, and may throw the `IOException` of a failed write (a full disk).
 */
interface SettingsStore {
    /** Null until the first read. */
    val settings: StateFlow<Settings?>

    /** Reads the stored values directly, so it is current right after an edit. */
    suspend fun snapshot(): Settings

    suspend fun setScreenshotProtection(on: Boolean)

    suspend fun setBiometricLock(on: Boolean)

    suspend fun setEncryptBackups(on: Boolean)

    /** The shell's notice was shown (security.md § 7.6). */
    suspend fun clearLockAutoOffNotice()

    /** One edit: `security.app_lock = false` and `security.lock_auto_off_notice = true` (security.md § 7.6). */
    suspend fun setLockAutoOff()

    suspend fun setBackoff(failures: Int, retryAtElapsedMs: Long)

    /**
     * A correct startup password (security.md § 3.6, § 5.6), in one edit: the backoff reset to 0 and
     * `security.password_verified_at = epochMillis`. Called only by `KeyVault` (DEC-74).
     */
    suspend fun recordPasswordVerified(epochMillis: Long)

    /**
     * Remembers [id] as the selection of the Individual ([EntityKind.Person]) or Project perspective (DEC-28). One
     * key per edit, so the two perspectives never write back each other's choice.
     */
    suspend fun setRemembered(kind: EntityKind, id: Long)

    /**
     * Forgets the remembered [kind] id only while it is still [id], compared inside the edit, so the existence probe
     * of a deleted entity never erases a choice made meanwhile (ui.md § 4.2).
     */
    suspend fun forgetRemembered(kind: EntityKind, id: Long)

    /**
     * Removes the remembered [kind] id whatever it holds, in one edit; the other kind's stays: "All people" / "All
     * projects" (DEC-112).
     */
    suspend fun clearRemembered(kind: EntityKind)

    /** Stores [record] for [draftKey] as the newest record, keeping at most [MAX_DRAFT_INSERTS]. */
    suspend fun putDraftInsert(draftKey: String, record: DraftInsert)

    suspend fun draftInsert(draftKey: String): DraftInsert?

    suspend fun dropDraftInsert(draftKey: String)

    /** Erase and the first-run reset: the seven security keys to their defaults, both remembered ids cleared. */
    suspend fun resetSecurity()

    /**
     * After Restore and Start fresh, in one edit: both remembered ids and every draft-insert record cleared,
     * `dataEpoch` + 1.
     */
    suspend fun onDataReplaced()

    companion object {
        /** Records of abandoned drafts cannot accumulate beyond this. */
        const val MAX_DRAFT_INSERTS = 8
    }
}

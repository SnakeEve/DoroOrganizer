package com.doro.organizer.testing

import com.doro.organizer.model.DraftInsert
import com.doro.organizer.settings.DraftInserts
import com.doro.organizer.settings.Settings
import com.doro.organizer.settings.SettingsStore
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SettingsStore] in memory (testing.md § 7). [settings] stays null until the test calls [load]; [snapshot] always
 * returns the current values. Every write is one atomic update and counts in [edits]. With [failEdits] set, every
 * write throws an `IOException` and changes nothing, like DataStore on a full disk (security.md § 3.6); with
 * [failReads] set, [snapshot] throws the `IOException` of a failed DataStore read.
 */
class FakeSettingsStore(initial: Settings = Settings()) : SettingsStore {
    private val lock = Any()
    private var values = initial
    private var draftInserts = emptyList<Pair<String, DraftInsert>>()
    private val visible = MutableStateFlow<Settings?>(null)

    @Volatile
    var failEdits = false

    @Volatile
    var failReads = false

    /** The write that makes [edits] reach this count, and every later one, fails as with [failEdits]. */
    @Volatile
    var failEditsFrom = Int.MAX_VALUE

    @Volatile
    var edits = 0
        private set

    override val settings: StateFlow<Settings?> = visible.asStateFlow()

    /** Publishes the current values, as the first DataStore read would. */
    fun load() = synchronized(lock) { visible.value = values }

    override suspend fun snapshot(): Settings = synchronized(lock) {
        if (failReads) throw IOException("injected settings read failure")
        values
    }

    override suspend fun setScreenshotProtection(on: Boolean) = edit { it.copy(screenshotProtection = on) }

    override suspend fun setBiometricLock(on: Boolean) = edit { it.copy(biometricLock = on) }

    override suspend fun setEncryptBackups(on: Boolean) = edit { it.copy(encryptBackups = on) }

    override suspend fun clearLockAutoOffNotice() = edit { it.copy(lockAutoOffNotice = false) }

    override suspend fun setLockAutoOff() = edit { it.copy(biometricLock = false, lockAutoOffNotice = true) }

    override suspend fun setBackoff(failures: Int, retryAtElapsedMs: Long) =
        edit { it.copy(unlockFailures = failures, unlockRetryAtElapsedMs = retryAtElapsedMs) }

    override suspend fun recordPasswordVerified(epochMillis: Long) =
        edit { it.copy(unlockFailures = 0, unlockRetryAtElapsedMs = 0, passwordVerifiedAt = epochMillis) }

    override suspend fun setRemembered(personId: Long?, projectId: Long?) =
        edit { it.copy(rememberedPersonId = personId, rememberedProjectId = projectId) }

    override suspend fun putDraftInsert(draftKey: String, record: DraftInsert) = update {
        draftInserts = DraftInserts.with(draftInserts, draftKey, record)
    }

    override suspend fun draftInsert(draftKey: String): DraftInsert? =
        synchronized(lock) { draftInserts.lastOrNull { it.first == draftKey }?.second }

    override suspend fun dropDraftInsert(draftKey: String) = update {
        draftInserts = draftInserts.filter { it.first != draftKey }
    }

    override suspend fun resetSecurity() = edit {
        it.copy(
            screenshotProtection = false,
            biometricLock = false,
            encryptBackups = false,
            unlockFailures = 0,
            unlockRetryAtElapsedMs = 0,
            lockAutoOffNotice = false,
            passwordVerifiedAt = 0,
            rememberedPersonId = null,
            rememberedProjectId = null,
        )
    }

    override suspend fun onDataReplaced() = update {
        draftInserts = emptyList()
        values = values.copy(rememberedPersonId = null, rememberedProjectId = null, dataEpoch = values.dataEpoch + 1)
    }

    private fun edit(change: (Settings) -> Settings) = update { values = change(values) }

    /** One atomic write: refused as a whole while [failEdits] is set. */
    private fun update(change: () -> Unit) = synchronized(lock) {
        if (failEdits || edits + 1 >= failEditsFrom) throw IOException("injected settings write failure")
        change()
        edits++
        if (visible.value != null) visible.value = values
    }
}

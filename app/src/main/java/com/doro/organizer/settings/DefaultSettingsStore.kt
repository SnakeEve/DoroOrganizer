package com.doro.organizer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.doro.organizer.model.DraftInsert
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [SettingsStore] on a Preferences DataStore in a file. [scope] runs the DataStore and the [settings] sharing; it must
 * outlive every caller, and cancelling it releases the file. A corrupt file is replaced with defaults (DEC-09): that
 * turns the biometric lock, screenshot protection and the backoff off and loses the reminder timestamp, an accepted
 * risk (security.md § 12). Every write is one DataStore edit.
 */
class DefaultSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsStore {
    constructor(file: File, scope: CoroutineScope) : this(dataStoreOn(file, scope), scope)

    // Lazily: nothing reads the file before the first subscriber, so building the container does no I/O.
    override val settings: StateFlow<Settings?> =
        dataStore.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Lazily, null)

    override suspend fun snapshot(): Settings = dataStore.data.first().toSettings()

    override suspend fun setScreenshotProtection(on: Boolean) = edit { it[SCREENSHOT_PROTECTION] = on }

    override suspend fun setBiometricLock(on: Boolean) = edit { it[APP_LOCK] = on }

    override suspend fun setEncryptBackups(on: Boolean) = edit { it[ENCRYPT_BACKUPS] = on }

    override suspend fun clearLockAutoOffNotice() = edit { it[LOCK_AUTO_OFF_NOTICE] = false }

    override suspend fun setLockAutoOff() = edit {
        it[APP_LOCK] = false
        it[LOCK_AUTO_OFF_NOTICE] = true
    }

    override suspend fun setBackoff(failures: Int, retryAtElapsedMs: Long) = edit {
        it[UNLOCK_FAILURES] = failures
        it[UNLOCK_RETRY_AT] = retryAtElapsedMs
    }

    override suspend fun setPasswordVerifiedAt(epochMillis: Long) = edit { it[PASSWORD_VERIFIED_AT] = epochMillis }

    override suspend fun setRemembered(personId: Long?, projectId: Long?) = edit {
        it.putOrRemove(REMEMBERED_PERSON, personId)
        it.putOrRemove(REMEMBERED_PROJECT, projectId)
    }

    override suspend fun putDraftInsert(draftKey: String, record: DraftInsert) = edit {
        val records = DraftInserts.with(DraftInserts.decode(it[DRAFT_INSERTS]), draftKey, record)
        it[DRAFT_INSERTS] = DraftInserts.encode(records)
    }

    override suspend fun draftInsert(draftKey: String): DraftInsert? =
        DraftInserts.decode(dataStore.data.first()[DRAFT_INSERTS]).lastOrNull { it.first == draftKey }?.second

    override suspend fun dropDraftInsert(draftKey: String) = edit { prefs ->
        val kept = DraftInserts.decode(prefs[DRAFT_INSERTS]).filter { it.first != draftKey }
        if (kept.isEmpty()) prefs.remove(DRAFT_INSERTS) else prefs[DRAFT_INSERTS] = DraftInserts.encode(kept)
    }

    override suspend fun resetSecurity() = edit { prefs ->
        SECURITY_KEYS.forEach { prefs.remove(it) }
        prefs.remove(REMEMBERED_PERSON)
        prefs.remove(REMEMBERED_PROJECT)
    }

    override suspend fun onDataReplaced() = edit {
        it.remove(REMEMBERED_PERSON)
        it.remove(REMEMBERED_PROJECT)
        it.remove(DRAFT_INSERTS)
        it[DATA_EPOCH] = (it[DATA_EPOCH] ?: 0) + 1
    }

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        dataStore.edit { change(it) }
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun Preferences.toSettings() = Settings(
        screenshotProtection = this[SCREENSHOT_PROTECTION] ?: false,
        biometricLock = this[APP_LOCK] ?: false,
        encryptBackups = this[ENCRYPT_BACKUPS] ?: false,
        unlockFailures = this[UNLOCK_FAILURES] ?: 0,
        unlockRetryAtElapsedMs = this[UNLOCK_RETRY_AT] ?: 0,
        lockAutoOffNotice = this[LOCK_AUTO_OFF_NOTICE] ?: false,
        passwordVerifiedAt = this[PASSWORD_VERIFIED_AT] ?: 0,
        rememberedPersonId = this[REMEMBERED_PERSON],
        rememberedProjectId = this[REMEMBERED_PROJECT],
        dataEpoch = this[DATA_EPOCH] ?: 0,
    )

    companion object {
        /** The production store in the app's `datastore/settings.preferences_pb`. */
        fun create(context: Context, scope: CoroutineScope) =
            DefaultSettingsStore(context.preferencesDataStoreFile("settings"), scope)

        /** The DataStore on [file] that the store uses; tests wrap it to watch its edits. */
        internal fun dataStoreOn(file: File, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )

        private val SCREENSHOT_PROTECTION = booleanPreferencesKey("security.screenshot_protection")
        private val APP_LOCK = booleanPreferencesKey("security.app_lock")
        private val ENCRYPT_BACKUPS = booleanPreferencesKey("security.encrypt_backups")
        private val UNLOCK_FAILURES = intPreferencesKey("security.unlock_failures")
        private val UNLOCK_RETRY_AT = longPreferencesKey("security.unlock_retry_at")
        private val LOCK_AUTO_OFF_NOTICE = booleanPreferencesKey("security.lock_auto_off_notice")
        private val PASSWORD_VERIFIED_AT = longPreferencesKey("security.password_verified_at")
        private val REMEMBERED_PERSON = longPreferencesKey("nav.remembered_person_id")
        private val REMEMBERED_PROJECT = longPreferencesKey("nav.remembered_project_id")
        private val DATA_EPOCH = intPreferencesKey("nav.data_epoch")
        private val DRAFT_INSERTS = stringPreferencesKey("nav.draft_inserts")

        private val SECURITY_KEYS = listOf(
            SCREENSHOT_PROTECTION,
            APP_LOCK,
            ENCRYPT_BACKUPS,
            UNLOCK_FAILURES,
            UNLOCK_RETRY_AT,
            LOCK_AUTO_OFF_NOTICE,
            PASSWORD_VERIFIED_AT,
        )
    }
}

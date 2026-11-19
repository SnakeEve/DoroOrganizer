package com.doro.organizer.testing

import com.doro.organizer.settings.DefaultSettingsStore
import com.doro.organizer.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Real `DefaultSettingsStore` instances over one DataStore file next to the key-state, for the persistence halves of
 * testing.md L-02 and L-37: a store is closed and a new one opened over the same file, like a new container after
 * the process died. At most one store is open at a time: DataStore refuses a second active instance on one file.
 */
class PersistedSettings(rule: KeyVaultRule) {
    private val file = File(rule.stateFile.parentFile, "settings.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    /** A new store over the file; the previous one must be [closed][closeAll] first. */
    fun open(): DefaultSettingsStore {
        check(scopes.isEmpty()) { "close the open store first" }
        return DefaultSettingsStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it })
    }

    /** Cancels every store's scope, which releases the file. */
    suspend fun closeAll() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    /** What a store opened afresh reads from the file; every store is closed afterwards. */
    suspend fun reread(): Settings {
        closeAll()
        return open().snapshot().also { closeAll() }
    }
}

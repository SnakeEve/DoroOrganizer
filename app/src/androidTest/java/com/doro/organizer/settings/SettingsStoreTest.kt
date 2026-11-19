package com.doro.organizer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.model.DraftInsert
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** testing.md I-44: the real [DefaultSettingsStore] on a temporary DataStore file. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private lateinit var directory: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun createDirectory() {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        directory = Files.createTempDirectory(root.toPath(), "settings").toFile()
    }

    @After
    fun release() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun firstRead_hasEveryDefault_andSettingsIsNullBeforeIt() = runBlocking {
        val store = store()

        assertNull(store.settings.value)
        val first = withTimeout(10.seconds) { store.settings.filterNotNull().first() }

        assertEquals(Settings(), first)
        assertEquals(DEFAULTS, first)
        assertEquals("the reminder timestamp is absent", 0L, first.passwordVerifiedAt)
    }

    @Test
    fun everySetter_roundTrips() = runBlocking {
        val store = store()

        store.setScreenshotProtection(true)
        store.setBiometricLock(true)
        store.setEncryptBackups(true)
        store.recordPasswordVerified(1_700_000_000_000)
        store.setBackoff(failures = 6, retryAtElapsedMs = 90_000)
        store.setRemembered(personId = 5, projectId = 6)

        val expected = Settings(
            screenshotProtection = true,
            biometricLock = true,
            encryptBackups = true,
            unlockFailures = 6,
            unlockRetryAtElapsedMs = 90_000,
            passwordVerifiedAt = 1_700_000_000_000,
            rememberedPersonId = 5,
            rememberedProjectId = 6,
        )
        assertEquals(expected, store.snapshot())
        assertEquals(expected, withTimeout(10.seconds) { store.settings.first { it == expected } })
        store.setRemembered(personId = null, projectId = 6)
        assertEquals(expected.copy(rememberedPersonId = null), store.snapshot())
    }

    @Test
    fun lockAutoOff_turnsTheLockOffAndRaisesTheNotice_untilCleared() = runBlocking {
        val store = store()
        store.setBiometricLock(true)

        store.setLockAutoOff()
        assertEquals(Settings(biometricLock = false, lockAutoOffNotice = true), store.snapshot())

        store.clearLockAutoOffNotice()
        assertEquals(Settings(), store.snapshot())
    }

    @Test
    fun valuesSurviveANewStoreOnTheSameFile() = runBlocking {
        val file = File(directory, "settings.preferences_pb")
        val first = store(file)
        first.recordPasswordVerified(42)
        first.setBackoff(failures = 5, retryAtElapsedMs = 30_000)
        closeAll()

        val reopened = store(file).snapshot()

        assertEquals(Settings(unlockFailures = 5, unlockRetryAtElapsedMs = 30_000, passwordVerifiedAt = 42), reopened)
    }

    @Test
    fun onDataReplaced_clearsIdsAndDraftsAndBumpsTheEpoch_inOneEdit() = runBlocking {
        val scope = newScope()
        val dataStore = CountingDataStore(DefaultSettingsStore.dataStoreOn(defaultFile(), scope))
        val store = DefaultSettingsStore(dataStore, scope)
        store.setRemembered(personId = 5, projectId = 6)
        store.putDraftInsert("draft", DraftInsert(1, emptyMap(), emptyMap()))
        val editsBefore = dataStore.edits.get()

        store.onDataReplaced()

        assertEquals("a collector can never see an intermediate state", 1, dataStore.edits.get() - editsBefore)
        assertEquals(Settings(dataEpoch = 1), store.snapshot())
        assertNull(store.draftInsert("draft"))
    }

    @Test
    fun recordPasswordVerified_resetsTheBackoffAndSetsTheTimestamp_inOneEdit() = runBlocking {
        val scope = newScope()
        val dataStore = CountingDataStore(DefaultSettingsStore.dataStoreOn(defaultFile(), scope))
        val store = DefaultSettingsStore(dataStore, scope)
        store.setBiometricLock(true)
        store.setBackoff(failures = 7, retryAtElapsedMs = 120_000)
        val editsBefore = dataStore.edits.get()

        store.recordPasswordVerified(1_700_000_000_000)

        assertEquals("a reader can never see the reset without the timestamp", 1, dataStore.edits.get() - editsBefore)
        assertEquals(Settings(biometricLock = true, passwordVerifiedAt = 1_700_000_000_000), store.snapshot())
    }

    @Test
    fun resetSecurity_resetsTheSevenSecurityKeysAndBothIds_only() = runBlocking {
        val store = store()
        store.setScreenshotProtection(true)
        store.setBiometricLock(true)
        store.setEncryptBackups(true)
        store.setLockAutoOff()
        store.recordPasswordVerified(7)
        store.setBackoff(failures = 6, retryAtElapsedMs = 90_000)
        store.setRemembered(personId = 5, projectId = 6)
        store.onDataReplaced()
        val draft = DraftInsert(3, mapOf("p" to 4L), emptyMap())
        store.putDraftInsert("draft", draft)

        store.resetSecurity()

        assertEquals(Settings(dataEpoch = 1), store.snapshot())
        assertEquals(draft, store.draftInsert("draft"))
    }

    @Test
    fun draftInserts_roundTrip_replace_drop_andKeepTheNewestEight() = runBlocking {
        val store = store()
        val record = DraftInsert(10, mapOf("a" to 1L, "b" to 2L), mapOf("c" to 3L))

        store.putDraftInsert("k0", record)
        assertEquals(record, store.draftInsert("k0"))
        store.putDraftInsert("k0", record.copy(noteId = 11))
        assertEquals(11L, store.draftInsert("k0")?.noteId)

        (1..SettingsStore.MAX_DRAFT_INSERTS).forEach { store.putDraftInsert("k$it", record.copy(noteId = it.toLong())) }
        assertNull("the oldest record was dropped", store.draftInsert("k0"))
        assertEquals(1L, store.draftInsert("k1")?.noteId)

        store.dropDraftInsert("k1")
        assertNull(store.draftInsert("k1"))
        assertEquals(2L, store.draftInsert("k2")?.noteId)
        assertNull(store.draftInsert("unknown"))
    }

    @Test
    fun corruptFile_readsAsDefaults_lockOffAndReminderLost() = runBlocking {
        val file = File(directory, "settings.preferences_pb")
        val first = store(file)
        first.setBiometricLock(true)
        first.recordPasswordVerified(1_700_000_000_000)
        closeAll()
        file.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x55))

        val snapshot = store(file).snapshot()

        assertEquals(DEFAULTS, snapshot)
        assertTrue("the reminder is due again", snapshot.passwordVerifiedAt == 0L)
    }

    private fun store(file: File = defaultFile()) = DefaultSettingsStore(file, newScope())

    private fun defaultFile() = File(directory, "settings.preferences_pb")

    /** A scope for one store, cancelled by [closeAll] or after the test. */
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    /** The store's real DataStore, counting the edits (one `updateData` each) made through it. */
    private class CountingDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val edits = AtomicInteger()

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            edits.incrementAndGet()
            return delegate.updateData(transform)
        }
    }

    /** Cancels every store's scope, which releases its file for a new store. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    private companion object {
        /** The defaults of architecture.md § SettingsStore and security.md § 2, spelled out. */
        val DEFAULTS = Settings(
            screenshotProtection = false,
            biometricLock = false,
            encryptBackups = false,
            unlockFailures = 0,
            unlockRetryAtElapsedMs = 0,
            lockAutoOffNotice = false,
            passwordVerifiedAt = 0,
            rememberedPersonId = null,
            rememberedProjectId = null,
            dataEpoch = 0,
        )
    }
}

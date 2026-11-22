package com.doro.organizer.data.maintenance

import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.debug.RecordingDocumentsProvider
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.backupFileName
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RAW_NOTE_INSERT
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.corruptPage
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.maintenanceFiles
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.pageCount
import java.io.File
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-07 and the delivery part of L-08 (DEC-83, DEC-89, db-lifecycle.md § Backup): every backup is a
 * verified export, plaintext only for a Plain database with "Encrypt backups" off, otherwise a SQLCipher 4 passphrase
 * file that only the backup password opens; documents are written with `"wt"`; share files live one hour at most.
 * The documents come from the debug `RecordingDocumentsProvider`; share files need the real `cacheDir/share/`,
 * because `BackupFileProvider` serves only that directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackupTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files by lazy { maintenanceFiles(databases, vaults, context.cacheDir) }
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope, files = files) }

    private fun runMaintenanceTest(body: suspend () -> Unit) = runTest(timeout = 120.seconds) { body() }

    @Before
    fun cleanDocuments() {
        RecordingDocumentsProvider.directory(context).deleteRecursively()
        synchronized(RecordingDocumentsProvider.modes) { RecordingDocumentsProvider.modes.clear() }
        files.shareDir.deleteRecursively()
    }

    @After
    fun removeDocuments() = cleanDocuments()

    @Test
    fun plainWithEncryptBackupsOff_isAPlaintextExport_withTheLatestRow() = runMaintenanceTest {
        setup.start()
        insertNote("written just before the backup")
        assertFalse(setup.maintenance.backupRequiresPassword())
        val document = emptyDocument("plain.db")

        val result = setup.maintenance.backupToDocument(document.uri, null)

        assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), result)
        assertTrue(RawConnections.isPlaintext(document.file))
        val backup = databases.raw(document.file, TestKey.Plain)
        assertBackupOf(backup)
        assertEquals(1L, backup.longOf("SELECT count(*) FROM notes WHERE content = 'written just before the backup'"))
        assertEquals(listOf(document.file.name), RecordingDocumentsProvider.directory(context).list()!!.toList())
        assertEquals(emptyList<String>(), files.stagingDir.list()!!.toList()) // nothing staged is left, no sidecar
        assertEquals(listOf(MaintenanceOp.Backup to false), setup.listener.started)
        assertEquals(listOf(MaintenanceOutcome.Reopened), setup.listener.finished)
        assertTrue(setup.holder.state.value is HolderState.Open)
    }

    @Test
    fun encryptedBackups_haveNoMagic_andOpenOnlyWithTheBackupPassword() = runMaintenanceTest {
        setup.start()
        setup.settings.setEncryptBackups(true)
        assertEncryptedBackup("plain-encrypted.db")
        setup.settings.setEncryptBackups(false)

        setup.changeKey(KeyMode.DEVICE)
        assertEncryptedBackup("device.db")

        setup.changeKey(KeyMode.PASSWORD, new = DATABASE_PASSWORD)
        assertEncryptedBackup("password.db")
        // The database password never opens a backup made with another backup password.
        val backup = RecordingDocumentsProvider.directory(context).resolve("password.db")
        assertTrue(runCatching { databases.raw(backup, TestKey.Passphrase(DATABASE_PASSWORD)).longOf("PRAGMA user_version") }.isFailure)
    }

    @Test
    fun aSourceThatFailsQuickCheck_writesNoBytes_deletesTheCreatedDocument_andEndsCorrupt() = runMaintenanceTest {
        setup.start()
        val db = setup.holder.openDatabase().openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } // the WAL is empty: the pages are on disk
        val live = setup.liveFile()
        for (page in 2..pageCount(live)) corruptPage(live, page)
        val document = emptyDocument("corrupt.db")

        val result = setup.maintenance.backupToDocument(document.uri, null)

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertFalse(document.file.exists())
        assertTrue(RecordingDocumentsProvider.modes.isEmpty()) // never opened for writing
        assertEquals(listOf(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt)), setup.listener.finished)
        assertEquals(HolderState.Closed, setup.holder.state.value)
    }

    @Test
    fun anExistingLongerDocument_isOverwrittenWithWt_andEndsWithTheBackupsExactLength() = runMaintenanceTest {
        setup.start()
        val fresh = emptyDocument("fresh.db")
        setup.maintenance.backupToDocument(fresh.uri, null)
        val existing = document("existing.db").apply { file.writeBytes(ByteArray(fresh.file.length().toInt() + 4096) { 0x5A }) }
        RecordingDocumentsProvider.modes.clear()

        val result = setup.maintenance.backupToDocument(existing.uri, null)

        assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), result)
        assertEquals(listOf("wt"), RecordingDocumentsProvider.modes)
        assertEquals(fresh.file.length(), existing.file.length()) // the 4,096 longer old tail is gone
        assertBackupOf(databases.raw(existing.file, TestKey.Plain))
    }

    @Test
    fun aShareFile_isServedUnderTheDatedName_andDeletedAtTheNextExport() = runMaintenanceTest {
        setup.start()

        val first = setup.maintenance.backupForShare(null) as AppResult.Ok
        val shared = first.value as MaintenanceResult.BackupShared

        assertEquals(backupFileName(setup.time.today()), shared.displayName)
        val shareFiles = files.shareDir.listFiles()!!.toList()
        assertEquals(1, shareFiles.size)
        val served = context.contentResolver.openInputStream(shared.uri)!!.use { it.readBytes() }
        assertArrayEquals(shareFiles.single().readBytes(), served)
        assertTrue(RawConnections.isPlaintext(shareFiles.single()))

        setup.maintenance.backupForShare(null)

        val now = files.shareDir.listFiles()!!.toList()
        assertEquals(1, now.size)
        assertFalse(shareFiles.single().exists())
    }

    @Test
    fun aShareDirectoryRemovedWhileTheProcessLives_isCreatedAgain() = runMaintenanceTest {
        setup.start()
        assertTrue(files.shareDir.deleteRecursively()) // Clear cache, or a low-storage purge, with the app running

        val result = setup.maintenance.backupForShare(null)

        val shared = (result as AppResult.Ok).value as MaintenanceResult.BackupShared
        val served = context.contentResolver.openInputStream(shared.uri)!!.use { it.readBytes() }
        assertArrayEquals(files.shareDir.listFiles()!!.single().readBytes(), served)
    }

    @Test
    fun shareFiles_goAtTheFirstReconciliationOfAProcess_butSurviveARerunInTheSameProcess() = runMaintenanceTest {
        setup.start()
        setup.maintenance.backupForShare(null)
        setup.holder.close()

        setup.maintenance.reconcileAtStartup() // a re-run after Restart in the same process

        assertEquals(1, files.shareDir.list()!!.size)
        val coldStart = MaintenanceSetup(databases, vaults, scope.scope, files = files)
        coldStart.maintenance.reconcileAtStartup()
        assertEquals(0, files.shareDir.list()!!.size)
    }

    @Test
    fun sweepShareFiles_deletesFilesAnHourOld_byWallClock() = runMaintenanceTest {
        files.ensureDirs()
        val now = setup.time.nowMillis()
        val old = File(files.shareDir, "old.db").apply { writeBytes(byteArrayOf(1)) }
        val young = File(files.shareDir, "young.db").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(old.setLastModified(now - Duration.ofMinutes(61).toMillis()))
        assertTrue(young.setLastModified(now - Duration.ofMinutes(59).toMillis()))

        setup.maintenance.sweepShareFiles()

        assertFalse(old.exists())
        assertTrue(young.exists())
    }

    @Test
    fun aShareFile_isDeletedByItsTimerAfterSixtyMinutes() = runTest(timeout = 120.seconds) {
        val test = StandardTestDispatcher(testScheduler)
        val timed = MaintenanceSetup(
            databases, vaults, backgroundScope, files = files, dispatchers = AppDispatchers(test, test, test),
        )
        timed.start()
        timed.maintenance.backupForShare(null)
        val shared = files.shareDir.listFiles()!!.single()

        advanceTimeBy(59.minutes)
        runCurrent()
        assertTrue(shared.exists())
        advanceTimeBy(1.minutes + 1.seconds)
        runCurrent()
        assertFalse(shared.exists())
    }

    @Test
    fun discardingADocument_outlivesItsCancelledCaller() = runMaintenanceTest {
        // ui.md § 8.6: "Cancel" of the backup password, then Back, clears the Settings ViewModel at once.
        val maintenance = setup.maintenance
        val document = emptyDocument("discarded.db")
        val before = scope.scope.coroutineContext.job.children.toSet()

        coroutineScope {
            launch {
                coroutineContext.job.cancel()
                maintenance.discardDocument(document.uri)
            }
        }
        (scope.scope.coroutineContext.job.children.toSet() - before).forEach { it.join() }

        assertFalse("the empty document the picker created is gone", document.file.exists())
    }

    private suspend fun assertEncryptedBackup(name: String) {
        assertTrue(setup.maintenance.backupRequiresPassword())
        val document = emptyDocument(name)

        val result = setup.maintenance.backupToDocument(document.uri, BACKUP_PASSWORD.toCharArray())

        assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), result)
        assertFalse(RawConnections.isPlaintext(document.file))
        assertTrue(runCatching { databases.raw(document.file, TestKey.Plain).longOf("PRAGMA user_version") }.isFailure)
        assertBackupOf(databases.raw(document.file, TestKey.Passphrase(BACKUP_PASSWORD)))
    }

    /** The backup holds exactly the live data, with `user_version` and `application_id` set. */
    private fun assertBackupOf(backup: SupportSQLiteDatabase) {
        assertEquals(AppDatabase.VERSION.toLong(), backup.longOf("PRAGMA user_version"))
        assertEquals(AppDatabase.APPLICATION_ID.toLong(), backup.longOf("PRAGMA application_id"))
        assertEquals(setup.dump(), canonicalDump(backup))
    }

    private fun insertNote(content: String) {
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL(
            RAW_NOTE_INSERT,
            arrayOf<Any?>(null, null, 0, null, 1, 1, content, content),
        )
    }

    private class Document(val file: File, val uri: Uri)

    private fun document(name: String) =
        Document(RecordingDocumentsProvider.directory(context).resolve(name), RecordingDocumentsProvider.uri(context, name))

    /** What the picker returns for a new document: an empty file. */
    private fun emptyDocument(name: String) = document(name).apply { file.writeBytes(ByteArray(0)) }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 2"
        const val DATABASE_PASSWORD = "database password 1"
    }
}

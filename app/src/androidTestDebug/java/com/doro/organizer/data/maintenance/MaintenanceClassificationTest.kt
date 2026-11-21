package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.DbFailure
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.classify
import com.doro.organizer.debug.RecordingDocumentsProvider
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KekOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer
import com.doro.organizer.testing.ScriptedVault
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.enterCorrupt
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.restoreAnswering
import com.doro.organizer.testing.seed
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-13 for the contexts DbMaintenance and Documents of db-lifecycle.md § Classification table: the rows
 * that no other test asserts, each through the production pipeline where a fault can be planted, and on the
 * classifying function itself (`pipelineError`, `notCommittedError`) where no file can trigger it reliably. The
 * restore-candidate rows are `RestoreValidationTest`'s; the slot-key rows `RestoreFromCorruptFingerprintTest`'s; the
 * KEK-fallback rows `RestoreKekFallbackTest`'s; the Erase origin rows `ForgotPasswordTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceClassificationTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var vault: ScriptedVault? = null
    private val setup by lazy {
        MaintenanceSetup(databases, vaults, scope.scope, wrapVault = { real -> ScriptedVault(real).also { vault = it } })
    }

    private fun runClassificationTest(body: suspend () -> Unit) = runTest(timeout = 300.seconds) { body() }

    @Before
    fun cleanDocuments() {
        RecordingDocumentsProvider.directory(context).deleteRecursively()
        RecordingDocumentsProvider.failOpen = null
        RecordingDocumentsProvider.openReturnsNull = false
        RecordingDocumentsProvider.failRead = null
    }

    @After
    fun removeDocuments() = cleanDocuments()

    @Test
    fun aTemporaryDirectoryTheFrameCannotEmpty_isDatabaseUnavailable_beforeAnythingStarts() = runClassificationTest {
        setup.start()
        for (dir in listOf(setup.files.stagingDir, setup.files.restoreDir)) {
            // A child that cannot be deleted: its directory is not writable for the app.
            val locked = File(dir, "locked")
            assertTrue(dir.name, locked.mkdirs())
            File(locked, "left.db").writeBytes(byteArrayOf(1))
            assertTrue(dir.name, locked.setWritable(false, false))
            val state = vaults.stateFile.readBytes()
            val result = try {
                setup.maintenance.backupForShare(null)
            } finally {
                locked.setWritable(true, false)
                locked.deleteRecursively()
            }

            assertEquals(dir.name, AppResult.Err(AppError.DatabaseUnavailable), result)
            assertArrayEquals(dir.name, state, vaults.stateFile.readBytes())
            assertEquals(dir.name, emptyList<Pair<MaintenanceOp, Boolean>>(), setup.listener.started)
            assertEquals(dir.name, emptyList<MaintenanceOutcome>(), setup.listener.finished)
        }
    }

    @Test
    fun aKeystoreFailureInAKeyChangesPrepare_isKeyUnavailable_andTheOldGenerationAndSlotStay() = runClassificationTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 17, notes = 30).generate())
        val content = setup.dump()
        val state = vaults.stateFile.readBytes()
        val generations = setup.files.generationsOnDisk().keys
        val live = (setup.holder.state.value as HolderState.Open).generation
        repeat(2) { setup.kek.failNext(KekOp.Generate, ProviderException("injected")) } // the retry after 1 s fails too

        val result = setup.maintenance.changeKey(KeyMode.DEVICE, null, null)

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(generations, setup.files.generationsOnDisk().keys)
        assertEquals(live, (setup.holder.state.value as HolderState.Open).generation)
        assertEquals(content, setup.dump())
        assertEquals(listOf(MaintenanceOutcome.Reopened), setup.listener.finished)
    }

    @Test
    fun aFingerprintResetWhoseUnlockIsKeyFailed_isKeyUnavailable_beforeMaintenanceStarted() = runClassificationTest {
        passwordModeWithSlot()
        val cipher = setup.unlockCipher()
        val state = vaults.stateFile.readBytes()
        val started = setup.listener.started.size
        checkNotNull(vault).nextBiometricUnlock = UnlockResult.KeyFailed

        val result = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, cipher, NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        assertEquals(started, setup.listener.started.size)
        assertArrayEquals(state, vaults.stateFile.readBytes())
    }

    @Test
    fun aFingerprintResetOverAnInvalidOrMissingKeyState_isKeyUnavailable_beforeMaintenanceStarted() = runClassificationTest {
        passwordModeWithSlot()
        val cipher = setup.unlockCipher()
        val generations = setup.files.generationsOnDisk().keys
        val started = setup.listener.started.size
        vaults.stateFile.writeText("{\"formatVersion\":")
        val invalid = vaults.stateFile.readBytes()

        val overInvalid = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, cipher, NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.KeyUnavailable), overInvalid)
        assertArrayEquals(invalid, vaults.stateFile.readBytes())
        assertTrue(vaults.stateFile.delete())

        val overMissing = setup.maintenance.resetPasswordWithBiometric(MaintenanceOrigin.Ready, cipher, NEW.toCharArray())

        assertEquals(AppResult.Err(AppError.KeyUnavailable), overMissing)
        assertFalse(vaults.stateFile.exists())
        assertEquals(started, setup.listener.started.size)
        assertEquals(generations, setup.files.generationsOnDisk().keys)
    }

    @Test
    fun aProviderThatGivesNoStream_isFileAccessOrStorageFull_underThePartialDocumentRule() = runClassificationTest {
        setup.start()
        for (fault in OPEN_FAULTS) {
            if (fault.failure == null) RecordingDocumentsProvider.openReturnsNull = true
            RecordingDocumentsProvider.failOpen = fault.failure
            val created = document("created.db", ByteArray(0))
            val existing = document("existing.db", ByteArray(4096) { 0x5A })

            val overCreated = setup.maintenance.backupToDocument(created.uri, null)

            assertEquals(fault.name, AppResult.Err(fault.expected), overCreated)
            assertFalse("${fault.name}: the created document stays", created.file.exists())
            assertFalse(fault.name, finished().previousContentLost)

            val overExisting = setup.maintenance.backupToDocument(existing.uri, null)

            // The stream never opened: the existing document may be the user's only intact backup.
            assertEquals(fault.name, AppResult.Err(fault.expected), overExisting)
            assertArrayEquals(fault.name, ByteArray(4096) { 0x5A }, existing.file.readBytes())
            assertFalse(fault.name, finished().previousContentLost)
            cleanDocuments()
        }
    }

    @Test
    fun aRestoreDocumentThatCannotBeRead_isFileAccessOrStorageFull_andNothingStaysStaged() = runClassificationTest {
        setup.start()
        val backup = setup.backupTo(context, "unreadable.db")
        val state = vaults.stateFile.readBytes()
        for (fault in READ_FAULTS) {
            RecordingDocumentsProvider.failRead = fault.failure

            val result = setup.maintenance.restore(backup.uri, MaintenanceOrigin.Ready)

            assertEquals(fault.name, AppResult.Err(fault.expected), result)
            assertEquals(fault.name, emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        }
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Restore))
    }

    @Test
    fun aRestoreFromCorruptWhoseSlotUnlockIsKeyFailed_isKeyUnavailable_beforeMaintenanceStarted() = runClassificationTest {
        passwordModeWithSlot()
        val backup = setup.backupTo(context, "slot-key-failed.db", OLD)
        setup.enterCorrupt()
        val state = vaults.stateFile.readBytes()
        checkNotNull(vault).nextBiometricUnlock = UnlockResult.KeyFailed

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.Corrupt,
            RestoreAnswer.backupPassword(OLD),
            RestoreAnswer.fingerprint { setup.unlockCipher() },
        )

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Restore))
        assertTrue(vaults.bioKeyExists())
    }

    @Test
    fun aFailedTargetVerification_isUnexpected() = runClassificationTest {
        val key = TestKey.Raw(ByteArray(32) { 7 }).bytes()
        val plaintext = databases.file("plaintext-target.db").also { createEmptyDatabase(context, it) }
        val noDatabase = databases.file("garbage-target.db").apply { writeBytes(ByteArray(8192) { 0x41 }) }

        val checkFailure = checkNotNull(runCatching { verifyTarget(plaintext, key) }.exceptionOrNull())
        val openFailure = checkNotNull(runCatching { verifyTarget(noDatabase, key) }.exceptionOrNull())

        assertTrue("$checkFailure", checkFailure is IllegalStateException)
        assertEquals(AppError.Unexpected, pipelineError(checkFailure))
        assertNotEquals("$openFailure", DbFailure.Transient, classify(openFailure))
        assertEquals(AppError.Unexpected, pipelineError(openFailure))
    }

    @Test
    fun aFailedRename_isStorageFullOnlyWhenItsErrnoSaysSo() {
        assertEquals(AppError.StorageFull, notCommittedError(ErrnoException("rename", OsConstants.ENOSPC)))
        assertEquals(AppError.StorageFull, notCommittedError(ErrnoException("rename", OsConstants.EDQUOT)))
        assertEquals(AppError.Unexpected, notCommittedError(ErrnoException("rename", OsConstants.EXDEV)))
        assertEquals(AppError.Unexpected, notCommittedError(ErrnoException("rename", OsConstants.EACCES)))
    }

    @Test
    fun aNotCommittedOutcome_isStorageFullOnlyWhenItsCauseSaysSo() {
        assertEquals(AppError.StorageFull, notCommittedError(IOException("write", ErrnoException("write", OsConstants.ENOSPC))))
        assertEquals(AppError.StorageFull, notCommittedError(SQLiteFullException("injected")))
        assertEquals(AppError.Unexpected, notCommittedError(IOException("write")))
        // The read-back shows the previous state although the write reported nothing.
        assertEquals(AppError.Unexpected, notCommittedError(null))
    }

    /** PASSWORD mode with a live biometric slot armed from the live key. */
    private suspend fun passwordModeWithSlot() {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = OLD)
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
    }

    private fun finished() = setup.maintenance.status.value as MaintenanceStatus.Finished

    private class Document(val file: File, val uri: Uri)

    private fun document(name: String, content: ByteArray): Document {
        val file = RecordingDocumentsProvider.directory(context).resolve(name).apply { writeBytes(content) }
        return Document(file, RecordingDocumentsProvider.uri(context, name))
    }

    /** A provider refusal at open for writing; [failure] null: the provider returns no descriptor. */
    private class OpenFault(val name: String, val failure: Exception?, val expected: AppError)

    private companion object {
        const val OLD = "startup password 1"
        const val NEW = "startup password 2"

        fun noSpace(errno: Int) = FileNotFoundException("injected").apply { initCause(ErrnoException("open", errno)) }

        /** A provider refusal at open for reading; `openFileDescriptor`'s `FileNotFoundException` retries as a stream. */
        val READ_FAULTS = listOf(
            OpenFault("FileNotFoundException", FileNotFoundException("injected"), AppError.FileAccess),
            OpenFault("SecurityException", SecurityException("injected"), AppError.FileAccess),
            OpenFault("IllegalArgumentException", IllegalArgumentException("injected"), AppError.FileAccess),
            OpenFault("UnsupportedOperationException", UnsupportedOperationException("injected"), AppError.FileAccess),
            OpenFault("ENOSPC", noSpace(OsConstants.ENOSPC), AppError.StorageFull),
        )

        val OPEN_FAULTS = listOf(
            OpenFault("no descriptor", null, AppError.FileAccess),
            OpenFault("FileNotFoundException", FileNotFoundException("injected"), AppError.FileAccess),
            OpenFault("SecurityException", SecurityException("injected"), AppError.FileAccess),
            OpenFault("IllegalArgumentException", IllegalArgumentException("injected"), AppError.FileAccess),
            OpenFault("UnsupportedOperationException", UnsupportedOperationException("injected"), AppError.FileAccess),
            OpenFault("ENOSPC", noSpace(OsConstants.ENOSPC), AppError.StorageFull),
            OpenFault("EDQUOT", noSpace(OsConstants.EDQUOT), AppError.StorageFull),
        )
    }
}

package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.RawConnections
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.settings.Settings
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.longsOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import com.doro.organizer.testing.seed
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-09, the DbMaintenance part (DEC-84, db-lifecycle.md § Restore): own plain and password backups round
 * trip exactly into every current mode, the mode and every security setting stay, ids are never reused, the data reset
 * runs, and observers see the restored rows. The `OpenDocument` intent and the dialogs are the Compose half (SPEC T19).
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runRestoreTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun anOwnPlainBackup_restoresExactly_resetsTheData_andNeverReusesAnId() = runRestoreTest {
        setup.start()
        val db = setup.holder.openDatabase().apply { seed(SeedGenerator(seed = 3, notes = 40).generate()) }
        val writable = db.openHelper.writableDatabase
        val highest = writable.longOf("SELECT max(id) FROM notes")
        writable.execSQL("DELETE FROM notes WHERE id = $highest") // the sequence stays above every remaining id
        val atBackup = setup.dump()
        val backup = setup.backupTo(context, "plain.db")
        // The backup closed Room and reopened it (exclusive mode): the instance read above is retired.
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM notes") // the data the restore replaces
        setup.settings.setRemembered(personId = 1, projectId = 2)
        val epoch = setup.settings.snapshot().dataEpoch
        val before = setup.state()
        val preview = mutableListOf<RestorePreview>()
        val statusAtStart = mutableListOf<MaintenanceStatus>()
        setup.listener.onStarted = { statusAtStart += setup.maintenance.status.value }

        val result = setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready, confirm { preview += it })

        val counts = atBackupCounts(atBackup)
        assertEquals(AppResult.Ok(MaintenanceResult.Restored(counts[0], counts[1], counts[2], false)), result)
        assertEquals(listOf(RestorePreview(counts[0], counts[1], counts[2], KeyMode.PLAIN, false)), preview)
        // The confirmation question ended before the gate entered Maintenance: no dialog can show it again.
        assertEquals(listOf(MaintenanceStatus.Running(MaintenanceOp.Restore, PipelineStep.AWAIT_CONFIRMATION)), statusAtStart)
        assertEquals(atBackup, setup.dump())
        val after = setup.state()
        assertTrue(after.generation > before.generation)
        assertTrue(after.slot.sameAs(before.slot))
        assertEquals(setOf(after.generation), setup.files.generationsOnDisk().keys)
        assertEquals(after.generation, (setup.holder.state.value as HolderState.Open).generation)
        assertTrue(setup.files.stagingDir.list()!!.isEmpty())
        assertTrue(setup.files.restoreDir.list()!!.isEmpty())
        assertFalse(setup.files.resetMarker.exists())
        val settings = setup.settings.snapshot()
        assertEquals(epoch + 1, settings.dataEpoch)
        assertNull(settings.rememberedPersonId)
        assertNull(settings.rememberedProjectId)
        assertEquals(listOf(false), setup.listener.startedFor(MaintenanceOp.Restore))
        assertEquals(MaintenanceOutcome.Reopened, setup.listener.finished.last())
        val restored = setup.holder.openDatabase().openHelper.writableDatabase
        restored.execSQL("INSERT INTO notes (checked, created_at, updated_at, content, content_fold) VALUES (0, 1, 1, '', '')")
        assertTrue(restored.longOf("SELECT max(id) FROM notes") > highest) // INV-11
    }

    @Test
    fun aPasswordBackup_asksItsPassword_againAfterAWrongOne_andRestores() = runRestoreTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 4, notes = 10).generate())
        setup.settings.setEncryptBackups(true)
        val atBackup = setup.dump()
        val backup = setup.backupTo(context, "password.db", BACKUP_PASSWORD)
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM persons")

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.Ready,
            backupPassword("not the backup password"),
            backupPassword(BACKUP_PASSWORD, retry = true),
            confirm(),
        )

        assertTrue(result.toString(), (result as AppResult.Ok).value is MaintenanceResult.Restored)
        assertEquals(atBackup, setup.dump())
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
    }

    @Test
    fun aSeparateBackupPassword_inPasswordMode_restoresUnderTheDatabasePassword() = runRestoreTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 5, notes = 12).generate())
        setup.changeKey(KeyMode.PASSWORD, new = DATABASE_PASSWORD)
        setup.settings.setScreenshotProtection(true)
        val atBackup = setup.dump()
        val backup = setup.backupTo(context, "separate.db", BACKUP_PASSWORD)
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM notes")
        val slotBefore = setup.state().slot
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.Ready,
            backupPassword(BACKUP_PASSWORD),
            confirm { assertEquals(KeyMode.PASSWORD, it.resultingMode) },
        )

        assertTrue(result.toString(), (result as AppResult.Ok).value is MaintenanceResult.Restored)
        assertTrue(setup.state().slot.sameAs(slotBefore)) // same DEK: the database password still opens it
        assertSecuritySettingsKept(settingsBefore)
        setup.restart(DATABASE_PASSWORD)
        assertEquals(atBackup, setup.dump())
    }

    @Test
    fun aPlainBackup_restoredIntoDeviceMode_isEncryptedWithTheDeviceKey_andKeepsEverySetting() = runRestoreTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 6, notes = 15).generate())
        val atBackup = setup.dump()
        val backup = setup.backupTo(context, "for-device.db")
        setup.changeKey(KeyMode.DEVICE)
        setup.settings.setScreenshotProtection(true)
        setup.settings.setEncryptBackups(true)
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.Ready,
            confirm { assertEquals(KeyMode.DEVICE, it.resultingMode) },
        )

        assertTrue(result.toString(), (result as AppResult.Ok).value is MaintenanceResult.Restored)
        assertEquals(KeyMode.DEVICE, setup.state().slot.mode)
        assertFalse(RawConnections.isPlaintext(setup.liveFile()))
        assertSecuritySettingsKept(settingsBefore)
        setup.restart()
        assertEquals(atBackup, setup.dump())
    }

    @Test
    fun aListFlowCollectedAcrossTheSwap_emitsTheRestoredRows() = runRestoreTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 8, notes = 20).generate())
        val backup = setup.backupTo(context, "flow.db")
        val backupIds = setup.holder.openDatabase().openHelper.writableDatabase.longsOf("SELECT id FROM notes ORDER BY id")
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM notes WHERE id % 2 = 0")
        val notes = noteRepository(setup.holder, setup.time, scope)

        notes.observeRows(ALL).test(timeout = 60.seconds) {
            assertEquals(backupIds.filter { it % 2 != 0L }, awaitItem().map { it.id }.sorted())

            setup.maintenance.restoreAnswering(backup.uri, MaintenanceOrigin.Ready, confirm())

            do {
                val ids = awaitItem().map { it.id }.sorted() // the swap may first emit the rows of neither state
            } while (ids != backupIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The row counts of notes, persons and projects in a `canonicalDump`. */
    private fun atBackupCounts(dump: Map<String, List<List<String?>>>) =
        listOf("notes", "persons", "projects").map { dump.getValue(it).size }

    /** Every setting but the data-reset values (`dataEpoch`, the remembered ids) is unchanged (DEC-84). */
    private suspend fun assertSecuritySettingsKept(before: Settings) {
        val after = setup.settings.snapshot()
        assertEquals(before.copy(dataEpoch = after.dataEpoch, rememberedPersonId = null, rememberedProjectId = null), after)
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 7"
        const val DATABASE_PASSWORD = "database password 7"
        val ALL = NoteQuery(null, null, orphanOnly = false, undatedOnly = false, fromDay = null, toDay = null, searchPattern = null)
    }
}

package com.doro.organizer.perf

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.PerfRecorder
import com.doro.organizer.testing.PerfTest
import com.doro.organizer.testing.RestoreAnswer
import com.doro.organizer.testing.SeedModel
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.maintenanceFiles
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.restoreAnswering
import com.doro.organizer.testing.seed
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md P-4 on the 10,000-note seed, recorded without a budget: backup export, restore and the Plain → Device
 * key change, through the production `DbMaintenance`. The backup goes to a share file (`cacheDir/share/`, served by
 * `BackupFileProvider`), the export pipeline of every backup, because the benchmark build has no test documents
 * provider; the restore reads that file back. Also `exportSeedBackup` (testing.md § 7): `seed-10k.db`, the plain
 * backup that P-5 and M-18 restore through Settings, written through [PerfRecorder.openOutput] once its copy has been
 * checked.
 */
@PerfTest
@RunWith(AndroidJUnit4::class)
class MaintenancePerfTest {
    @get:Rule(order = -1)
    val perf = PerfRecorder()

    @get:Rule(order = 0)
    val databases = TestDatabases()

    @get:Rule(order = 1)
    val vaults = KeyVaultRule()

    @get:Rule(order = 2)
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files by lazy { maintenanceFiles(databases, vaults, context.cacheDir) }
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope, files = files) }

    @Before
    fun cleanShareFiles() {
        files.shareDir.deleteRecursively()
    }

    @After
    fun removeShareFiles() = cleanShareFiles()

    @Test
    fun maintenanceOnTheSeed_isRecorded() = runTest(timeout = 600.seconds) {
        startSeeded()

        var shared: Uri? = null
        perf.measure("Backup export", budget = null) { shared = shareBackup() }
        val source = checkNotNull(shared)
        perf.measure("Restore", budget = null) {
            setup.maintenance.restoreAnswering(source, MaintenanceOrigin.Ready, RestoreAnswer.confirm()).ok()
        }
        perf.measure(
            "Key change Plain to Device",
            budget = null,
            prepare = { if (setup.state().slot.mode != KeyMode.PLAIN) setup.changeKey(KeyMode.PLAIN) },
        ) {
            setup.changeKey(KeyMode.DEVICE)
        }
    }

    @Test
    fun exportSeedBackup() = runTest(timeout = 120.seconds) {
        val seeded = startSeeded()

        val copy = databases.file(SEED_BACKUP)
        val input = checkNotNull(context.contentResolver.openInputStream(shareBackup())) { "the share file did not open" }
        input.use { copy.outputStream().use(it::copyTo) }
        // What P-5 and M-18 restore: a plaintext database (the empty key opens it) holding the whole seed.
        val notes = databases.raw(copy, TestKey.Plain).use { it.longOf("SELECT COUNT(*) FROM notes") }
        assertEquals(seeded.notes.size.toLong(), notes)
        // The checked copy goes out through TestStorage: the Orchestrator's pm clear deletes the app's own files.
        copy.inputStream().use { PerfRecorder.openOutput(SEED_BACKUP).use(it::copyTo) }
    }

    private suspend fun startSeeded(): SeedModel {
        setup.start()
        return setup.holder.openDatabase().seed()
    }

    /** A plain backup of the live data as a share file (DEC-83); the previous one is deleted by the export. */
    private suspend fun shareBackup(): Uri =
        (setup.maintenance.backupForShare(null).ok() as MaintenanceResult.BackupShared).uri

    private companion object {
        const val SEED_BACKUP = "seed-10k.db"
    }
}

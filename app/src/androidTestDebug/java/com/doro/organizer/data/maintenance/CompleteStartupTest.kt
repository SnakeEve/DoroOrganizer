package com.doro.organizer.data.maintenance

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.RawConnections
import com.doro.organizer.debug.ReadBackFailingVault
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-16, `completeStartup` (db-lifecycle.md § Completing startup in Opening): the first run creates and
 * commits generation 1, an adoption commits the highest plaintext file in place, `NotCommitted` keeps the decision for
 * Retry and `Unknown` discards it, so the retry reconciles again.
 */
@RunWith(AndroidJUnit4::class)
class CompleteStartupTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private var vault: ReadBackFailingVault? = null
    private val setup by lazy {
        MaintenanceSetup(databases, vaults, scope.scope, wrapVault = { real ->
            ReadBackFailingVault(real) { KeyVaultException(KeyFailure.InvalidState) }.also { vault = it }
        })
    }

    private fun runStartupTest(body: suspend () -> Unit) = runTest(timeout = 60.seconds) { body() }

    @Test
    fun firstRun_createsGenerationOne_asAnEmptyPlainDatabase_thatOpens() = runStartupTest {
        assertEquals(StartupDecision.FirstRun, setup.maintenance.reconcileAtStartup())

        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())

        val state = setup.state()
        assertEquals(1L, state.generation)
        assertEquals(KeyMode.PLAIN, state.slot.mode)
        assertEquals(KeyMode.PLAIN, setup.maintenance.keyMode.value)
        assertTrue(RawConnections.isPlaintext(setup.files.live(1)))
        assertEquals(emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup()) // a no-op afterwards
        setup.start()
        val db = setup.holder.openDatabase().openHelper.writableDatabase
        assertEquals(listOf("0"), db.stringsOf("SELECT count(*) FROM notes"))
    }

    @Test
    fun firstRun_numbersAboveOrphanSidecars_andDeletesThem() = runStartupTest {
        setup.files.ensureDirs()
        val orphan = File(setup.files.databasesDir, "doro-3.db-wal").apply { writeBytes(byteArrayOf(1)) }
        setup.maintenance.reconcileAtStartup()

        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())

        assertEquals(4L, setup.state().generation)
        assertFalse(orphan.exists())
        assertEquals(setOf(4L), setup.files.generationsOnDisk().keys)
    }

    @Test
    fun adoptPlain_commitsTheHighestPlaintextFileInPlace_andDeletesTheOthers() = runStartupTest {
        databases.createLive(2)
        databases.createLive(6)
        assertEquals(StartupDecision.AdoptPlain(6), setup.maintenance.reconcileAtStartup())

        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())

        assertEquals(6L, setup.state().generation)
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
        assertEquals(setOf(6L), setup.files.generationsOnDisk().keys)
        setup.start()
    }

    @Test
    fun notCommitted_isTransient_andKeepsTheDecisionForRetry() = runStartupTest {
        setup.maintenance.reconcileAtStartup()
        setup.hook.throwAt(null, PipelineStep.COMMIT, IOException("injected"))

        assertEquals(StartupCompletion.Transient, setup.maintenance.completeStartup())

        assertFalse(vaults.stateFile.exists())
        assertEquals(emptySet<Long>(), setup.files.generationsOnDisk().keys) // the renamed file went again
        assertEquals(emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        setup.hook.clear()
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
        assertEquals(1L, setup.state().generation)
    }

    @Test
    fun aStorageFullFailure_isTransient_andKeepsTheDecision() = runStartupTest {
        setup.maintenance.reconcileAtStartup()
        setup.hook.throwAt(null, PipelineStep.CREATE_EMPTY, SQLiteFullException("injected"))

        assertEquals(StartupCompletion.Transient, setup.maintenance.completeStartup())

        setup.hook.clear()
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
    }

    @Test
    fun unknown_isTransient_discardsTheDecision_andTheRetryReconcilesAgain() = runStartupTest {
        setup.maintenance.reconcileAtStartup()
        val epoch = setup.settings.snapshot().dataEpoch
        checkNotNull(vault).failReadAfterNextCommit = true

        assertEquals(StartupCompletion.Transient, setup.maintenance.completeStartup())

        assertTrue(vaults.stateFile.exists()) // the commit landed; only its read-back failed
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup()) // reconciled: Proceed
        assertEquals(epoch, setup.settings.snapshot().dataEpoch) // no second first-run reset
        assertEquals(KeyMode.PLAIN, setup.maintenance.keyMode.value)
        setup.start()
    }

    @Test
    fun keyUnavailable_isReportedByTheCompletion() = runStartupTest {
        vaults.stateFile.writeText("{")
        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())

        assertEquals(StartupCompletion.KeyUnavailable, setup.maintenance.completeStartup())
    }
}

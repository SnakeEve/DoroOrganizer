package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.model.KeyMode
import com.doro.organizer.settings.Settings
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.corruptPage
import com.doro.organizer.testing.enterCorrupt
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.openDescriptors
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.seed
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Erase from every password screen at the DbMaintenance level (DEC-63, security.md § 5.4, db-lifecycle.md § Erase):
 * the mechanics of testing.md L-24, each with the end state L-24 asserts. The typed confirmation, the gate transitions
 * and the screens are the SessionGate and Compose halves (SPEC T14, T19).
 */
@RunWith(AndroidJUnit4::class)
class ForgotPasswordTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runEraseTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun erase_fromNeedsPassword_leavesTheFirstRunState() = runEraseTest {
        passwordModeWithSlot()
        setup.holder.close() // the cold-start password screen: Room was never opened

        val result = setup.maintenance.erase(MaintenanceOrigin.NeedsPassword)

        assertEquals(AppResult.Ok(MaintenanceResult.Erased), result)
        assertErased()
        assertEquals(listOf(false), setup.listener.startedFor(MaintenanceOp.Erase))
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertFirstRunFollows()
    }

    @Test
    fun erase_fromReady_closesRoomBeforeTheKeyStateIsDeleted_andRestarts() = runEraseTest {
        passwordModeWithSlot()
        val open = setup.holder.state.value as HolderState.Open
        setup.hook.blockAt(MaintenanceOp.Erase, PipelineStep.ERASE_KEY_STATE)

        val result = coroutineScope {
            val erase = async { setup.maintenance.erase(MaintenanceOrigin.Ready) }
            setup.hook.blocked.await()
            // Right before eraseState(): Room is closed in exclusive mode, no descriptor is left, the key-state is there.
            assertEquals(HolderState.Exclusive, setup.holder.state.value)
            assertFalse(open.db.isOpen)
            assertEquals(0, openDescriptors(setup.files.live(open.generation)))
            assertTrue(vaults.stateFile.exists())
            setup.hook.release()
            erase.await()
        }

        assertEquals(AppResult.Ok(MaintenanceResult.Erased), result)
        assertEquals(HolderState.Closed, setup.holder.state.value)
        assertErased()
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertFirstRunFollows()
    }

    @Test
    fun erase_fromCorrupt_erasesTheDamagedFilesToo() = runEraseTest {
        passwordModeWithSlot()
        setup.enterCorrupt()

        assertEquals(AppResult.Ok(MaintenanceResult.Erased), setup.maintenance.erase(MaintenanceOrigin.Corrupt))
        assertErased()
        assertFirstRunFollows()
    }

    @Test
    fun erase_withAHolderThatDoesNotMatchItsOrigin_changesNothing() = runEraseTest {
        passwordModeWithSlot()
        val state = vaults.stateFile.readBytes()

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), setup.maintenance.erase(MaintenanceOrigin.NeedsPassword))
        setup.holder.close()
        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), setup.maintenance.erase(MaintenanceOrigin.Ready))

        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(1, setup.files.generationsOnDisk().size)
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Erase))
    }

    @Test
    fun erase_fromReady_failingBeforeTheKeyStateIsDeleted_reopensAndErasesNothing() = runEraseTest {
        passwordModeWithSlot()
        val state = vaults.stateFile.readBytes()
        val content = setup.dump()
        setup.hook.throwAt(MaintenanceOp.Erase, PipelineStep.ERASE_KEY_STATE, IOException("injected"))

        val result = setup.maintenance.erase(MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertEquals(MaintenanceOutcome.Reopened, setup.listener.finished.last())
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(content, setup.dump())
        assertTrue(vaults.kekExists())
    }

    @Test
    fun erase_fromReady_whoseReopenFailsAfterARolledBackStep_isReopenFailed_andErasesNothing() = runEraseTest {
        passwordModeWithSlot()
        val live = setup.liveFile()
        val state = vaults.stateFile.readBytes()
        // Room is closed when the step fails: the damaged page 1 makes the reopen of the old generation fail.
        setup.hook.throwAt(MaintenanceOp.Erase, PipelineStep.ERASE_KEY_STATE, IOException("injected")) { corruptPage(live, 1) }

        val result = setup.maintenance.erase(MaintenanceOrigin.Ready)

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
        assertEquals(MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt), setup.listener.finished.last())
        assertEquals(HolderState.Closed, setup.holder.state.value)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertTrue(live.exists())
        assertTrue(vaults.kekExists())
        assertTrue(vaults.bioKeyExists())
    }

    @Test
    fun erase_whoseDeleteOldCannotDeleteAnUnreferencedGeneration_isAbortedWithNothingErased() = runEraseTest {
        passwordModeWithSlot()
        setup.holder.close()
        val state = vaults.stateFile.readBytes()
        // A non-empty directory under a generation file name: deleteGeneration cannot remove it.
        val stuck = File(setup.files.databasesDir, "doro-99.db-wal").apply { check(mkdir()) }
        File(stuck, "child").writeBytes(byteArrayOf(1))
        try {
            val result = setup.maintenance.erase(MaintenanceOrigin.NeedsPassword)

            assertEquals(AppResult.Err(AppError.DatabaseUnavailable), result)
            assertEquals(MaintenanceOutcome.Aborted, setup.listener.finished.last())
            assertArrayEquals(state, vaults.stateFile.readBytes())
            assertTrue(setup.files.hasMainFile(setup.state().generation))
        } finally {
            stuck.deleteRecursively()
        }
    }

    /** `PASSWORD` mode with the lock on, a live biometric slot, some data, a share file and every security setting on. */
    private suspend fun passwordModeWithSlot() {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 47, notes = 10).generate())
        setup.changeKey(KeyMode.PASSWORD, new = DATABASE_PASSWORD)
        withSecuritySettings()
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        File(setup.files.shareDir, "shared.db").writeBytes(byteArrayOf(1)) // a share copy Erase must remove too
    }

    private suspend fun withSecuritySettings() {
        setup.settings.setBiometricLock(true)
        setup.settings.setScreenshotProtection(true)
        setup.settings.setEncryptBackups(true)
        setup.settings.setRemembered(personId = 1, projectId = 1)
    }

    /** L-24's end state after Erase: no key-state, no generation file, no key, empty temporary dirs, settings reset. */
    private suspend fun assertErased() {
        assertFalse(vaults.stateFile.exists())
        assertEquals(emptyMap<Long, Set<File>>(), setup.files.generationsOnDisk())
        assertFalse(vaults.kekExists())
        assertFalse(vaults.bioKeyExists())
        for (dir in listOf(setup.files.stagingDir, setup.files.restoreDir, setup.files.shareDir)) {
            assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        }
        assertNull(setup.maintenance.keyMode.value)
        val settings = setup.settings.snapshot()
        assertEquals(Settings(dataEpoch = settings.dataEpoch), settings) // every key at its default
        assertTrue(settings.dataEpoch > 0)
    }

    /** After the Restart: startup finds nothing, the first run creates generation 1, and it opens empty. */
    private suspend fun assertFirstRunFollows() {
        assertEquals(StartupDecision.FirstRun, setup.maintenance.reconcileAtStartup())
        assertEquals(StartupCompletion.Done, setup.maintenance.completeStartup())
        assertEquals(1L, setup.state().generation)
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
    }

    private companion object {
        const val DATABASE_PASSWORD = "database password 24"
    }
}

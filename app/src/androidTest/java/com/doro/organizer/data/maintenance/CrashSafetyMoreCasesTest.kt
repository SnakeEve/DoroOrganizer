package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.maintenanceFiles
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-process cases of L-15 (testing.md § 5): a same-process `Restart` keeps share files younger than one hour,
 * because only a process's first reconciliation empties `cacheDir/share/`; and `verifyTarget` accepts a zero-length
 * `-journal` next to the staged file and removes it before the commit (db-lifecycle.md § Target verification).
 */
@RunWith(AndroidJUnit4::class)
class CrashSafetyMoreCasesTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files by lazy { maintenanceFiles(databases, vaults, context.cacheDir) }
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope, files = files) }

    private fun runMaintenanceTest(body: suspend CoroutineScope.() -> Unit) = runTest(timeout = 120.seconds) { body() }

    @Before
    fun cleanShareFiles() {
        files.shareDir.deleteRecursively()
    }

    @After
    fun removeShareFiles() {
        files.shareDir.deleteRecursively()
    }

    @Test
    fun youngShareFiles_surviveASameProcessRestart() = runMaintenanceTest {
        setup.start()
        assertTrue(setup.maintenance.backupForShare(null) is AppResult.Ok)
        val shared = files.shareDir.listFiles()!!.single()
        setup.restart() // the second reconciliation of this process
        assertTrue("the share file survives", shared.exists())
    }

    @Test
    fun verifyTarget_acceptsAZeroLengthJournal_andRemovesIt() = runMaintenanceTest {
        setup.start()
        setup.hook.blockAt(MaintenanceOp.KeyChange, PipelineStep.VERIFY_TARGET)
        val change = async(start = CoroutineStart.UNDISPATCHED) { setup.maintenance.changeKey(KeyMode.DEVICE, null, null) }
        setup.hook.blocked.await()
        val staged = files.stagingDir.listFiles()!!.single { it.name.endsWith(".db") }
        val journal = File(staged.path + "-journal")
        assertTrue(journal.createNewFile())
        setup.hook.release()
        assertTrue("${change.await()}", change.await() is AppResult.Ok)
        assertFalse("the empty journal is removed", journal.exists())
        assertEquals(KeyMode.DEVICE, setup.state().slot.mode)
    }
}

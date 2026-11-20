package com.doro.organizer.data.maintenance

import androidx.core.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-16, `reconcileAtStartup` (db-lifecycle.md § Startup reconciliation, § Data-reset marker): one test per
 * row of the decision table, key-state (Valid, Missing, Invalid) × files, asserting the decision, which generation
 * sets remain and that `db-state.json` is never touched; temporary leftovers go in every row.
 */
@RunWith(AndroidJUnit4::class)
class StartupReconciliationTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }
    private val files get() = setup.files

    private fun runStartupTest(body: suspend () -> Unit) = runTest(timeout = 60.seconds) { body() }

    @Test
    fun valid_withItsLiveFile_proceeds_andDeletesEveryUnreferencedSetWithItsSidecars() = runStartupTest {
        commitState(2, KeyMode.PLAIN)
        databases.createLive(2)
        databases.createLive(1)
        val orphans = listOf("doro-1.db-wal", "doro-1.db-shm", "doro-1.db-journal", "doro-3.db-wal", "doro-4.db").map(::plant)
        val leftovers = plantTemporaryLeftovers()
        val stateBefore = stateFingerprint()

        val decision = setup.maintenance.reconcileAtStartup()

        assertEquals(StartupDecision.Proceed(2, KeyMode.PLAIN), decision)
        assertEquals(setOf(2L), files.generationsOnDisk().keys)
        orphans.forEach { assertFalse(it.name, it.exists()) }
        leftovers.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(stateBefore, stateFingerprint())
        assertEquals(KeyMode.PLAIN, setup.maintenance.keyMode.value)
    }

    @Test
    fun valid_withTheLiveFileMissingOrEmpty_proceeds_andDeletesNothing() = runStartupTest {
        commitState(2, KeyMode.PLAIN)
        databases.createLive(1)
        assertEquals(StartupDecision.Proceed(2, KeyMode.PLAIN), setup.maintenance.reconcileAtStartup())
        assertEquals(setOf(1L), files.generationsOnDisk().keys)

        plant("doro-2.db") // 0 bytes
        assertEquals(StartupDecision.Proceed(2, KeyMode.PLAIN), setup.maintenance.reconcileAtStartup())
        assertEquals(setOf(1L, 2L), files.generationsOnDisk().keys)
    }

    @Test
    fun missing_withoutAMainFile_isFirstRun_andResetsTheSecuritySettings() = runStartupTest {
        val orphan = plant("doro-3.db-wal")
        setup.settings.setBiometricLock(true)
        setup.settings.setScreenshotProtection(true)
        val epoch = setup.settings.snapshot().dataEpoch
        val leftovers = plantTemporaryLeftovers()

        assertEquals(StartupDecision.FirstRun, setup.maintenance.reconcileAtStartup())

        assertTrue("orphan sidecars stay until the first commit", orphan.exists())
        leftovers.forEach { assertFalse(it.name, it.exists()) }
        val settings = setup.settings.snapshot()
        assertFalse(settings.biometricLock)
        assertFalse(settings.screenshotProtection)
        assertEquals(epoch + 1, settings.dataEpoch)
        assertFalse(vaults.stateFile.exists())
        assertEquals(null, setup.maintenance.keyMode.value)
    }

    @Test
    fun missing_withOnlyPlaintextFiles_adoptsTheHighest_andDeletesNothing() = runStartupTest {
        databases.createLive(3)
        databases.createLive(5)

        assertEquals(StartupDecision.AdoptPlain(5), setup.maintenance.reconcileAtStartup())

        assertEquals(setOf(3L, 5L), files.generationsOnDisk().keys)
    }

    @Test
    fun missing_withAFileThatIsNotPlaintext_isKeyUnavailable_andDeletesNothing() = runStartupTest {
        databases.createLive(1)
        databases.createLive(2, TestKey.Raw(ByteArray(32) { 7 }))
        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())
        assertEquals(setOf(1L, 2L), files.generationsOnDisk().keys)

        databases.files.live(2).delete()
        plant("doro-2.db").writeBytes("SQLite".toByteArray()) // shorter than the 16-byte magic
        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())
        assertEquals(setOf(1L, 2L), files.generationsOnDisk().keys)
    }

    @Test
    fun invalid_isKeyUnavailable_whateverFilesExist_andNeverRewritesTheFile() = runStartupTest {
        vaults.stateFile.writeText("{ not a key-state")
        val before = vaults.stateFile.readBytes()
        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())

        databases.createLive(1)
        databases.createLive(2)
        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())

        assertEquals(setOf(1L, 2L), files.generationsOnDisk().keys)
        assertArrayEquals(before, vaults.stateFile.readBytes())
        assertEquals(null, setup.maintenance.keyMode.value)
    }

    @Test
    fun theDataResetMarker_resetsOnlyForTheCommittedGeneration_andIsAlwaysDeleted() = runStartupTest {
        commitState(4, KeyMode.PLAIN)
        databases.createLive(4)
        assertMarker("4", resets = true) // the committed n
        assertMarker("not a number", resets = true) // unparseable: the safe side
        assertMarker("3", resets = false) // another generation: nothing was committed
        vaults.stateFile.delete()
        assertMarker("4", resets = false) // Missing
        vaults.stateFile.writeText("{")
        assertMarker("4", resets = false) // Invalid
    }

    @Test
    fun reconcilingTwice_changesNothingTheSecondTime() = runStartupTest {
        commitState(2, KeyMode.PLAIN)
        databases.createLive(2)
        databases.createLive(1)
        setup.maintenance.reconcileAtStartup()
        val names = files.databasesDir.list()!!.toSet()
        val state = stateFingerprint()
        val settings = setup.settings.snapshot()

        assertEquals(StartupDecision.Proceed(2, KeyMode.PLAIN), setup.maintenance.reconcileAtStartup())

        assertEquals(names, files.databasesDir.list()!!.toSet())
        assertEquals(state, stateFingerprint())
        assertEquals(settings, setup.settings.snapshot())
    }

    /** DEC-98: every biometric key that no committed slot names goes; the committed slot's own key stays. */
    @Test
    fun theBiometricKeysNoCommittedSlotNames_areSwept_andTheSlotsOwnKeyStays() = runStartupTest {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = PASSWORD)
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        val alias = requireNotNull(setup.state().bioSlot).keyAlias
        setup.armCipher() // an arming that never committed, as a crash at PREPARE_BIO_SLOT leaves it (CS-29)
        setup.holder.close()
        val state = stateFingerprint()

        setup.maintenance.reconcileAtStartup()

        assertEquals(setOf(alias), vaults.bioAliases())
        assertEquals(BioSlotStatus.Live, setup.vault.biometricSlotStatus())
        assertEquals(state, stateFingerprint())
    }

    @Test
    fun theBiometricKeySweep_deletesEveryKeyOverAMissingKeyState_andNoneOverAnInvalidOne() = runStartupTest {
        setup.vault.commit(1, setup.vault.prepare(KeyMode.PASSWORD, PASSWORD.toByteArray()).slot, null)
        setup.armCipher()
        val aliases = vaults.bioAliases()
        vaults.stateFile.writeText("{")

        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())
        assertEquals(aliases, vaults.bioAliases()) // DEC-86: nothing is acted on over an invalid key-state

        assertTrue(vaults.stateFile.delete())
        assertEquals(StartupDecision.FirstRun, setup.maintenance.reconcileAtStartup())
        assertFalse(vaults.bioKeyExists())
    }

    /** DEC-86: under KeyUnavailable the keys go only with the user's Restore or Start fresh, a missing file included. */
    @Test
    fun theBiometricKeySweep_neverRunsUnderKeyUnavailable_overAMissingKeyState() = runStartupTest {
        setup.vault.commit(1, setup.vault.prepare(KeyMode.PASSWORD, PASSWORD.toByteArray()).slot, null)
        setup.armCipher()
        val aliases = vaults.bioAliases()
        assertTrue(vaults.stateFile.delete())
        databases.createLive(2, TestKey.Raw(ByteArray(32) { 7 }))

        assertEquals(StartupDecision.KeyUnavailable, setup.maintenance.reconcileAtStartup())

        assertEquals(1, aliases.size)
        assertEquals(aliases, vaults.bioAliases())
    }

    @Test
    fun aPartialNewKeyStateFile_isIgnored() = runStartupTest {
        // Verified at setup: androidx.core 1.19.1's AtomicFile writes "<name>.new".
        val partial = File(vaults.stateFile.path + ".new").apply { writeText("{\"formatVersion\":") }
        databases.createLive(1)
        assertEquals(StartupDecision.AdoptPlain(1), setup.maintenance.reconcileAtStartup()) // only .new: Missing

        commitState(1, KeyMode.PLAIN)
        partial.writeText("{\"formatVersion\":")
        assertEquals(StartupDecision.Proceed(1, KeyMode.PLAIN), setup.maintenance.reconcileAtStartup())
    }

    private suspend fun assertMarker(content: String, resets: Boolean) {
        val marker = AtomicFile(files.resetMarker)
        marker.startWrite().also { it.write(content.toByteArray()) }.let(marker::finishWrite)
        val epoch = setup.settings.snapshot().dataEpoch

        setup.maintenance.reconcileAtStartup()

        assertEquals(content, epoch + if (resets) 1 else 0, setup.settings.snapshot().dataEpoch)
        assertFalse(content, files.resetMarker.exists())
    }

    private suspend fun commitState(generation: Long, mode: KeyMode) {
        setup.vault.commit(generation, setup.vault.prepare(mode, null).slot, null)
    }

    private fun plant(name: String): File = File(files.databasesDir, name).apply { writeBytes(ByteArray(0)) }

    private fun plantTemporaryLeftovers(): List<File> {
        files.ensureDirs()
        return listOf(
            File(files.stagingDir, "doro-9.db"),
            File(files.stagingDir, "backup.db-journal"),
            File(files.restoreDir, "incoming.db"),
            File(files.shareDir, "old-share.db"),
        ).onEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun stateFingerprint(): Triple<Long, Long, List<Byte>> =
        vaults.stateFile.let { Triple(it.length(), it.lastModified(), it.readBytes().toList()) }

    private companion object {
        const val PASSWORD = "startup password 1"
    }
}

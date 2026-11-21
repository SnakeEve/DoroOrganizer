package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.CorruptRestoreScene
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.backupTo
import com.doro.organizer.testing.enterCorrupt
import com.doro.organizer.testing.longOf
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
 * Recovery from the Corrupt and KeyUnavailable screens at the DbMaintenance level (DEC-84, DEC-85, DEC-86): the
 * mechanics of testing.md L-12 and the recovery part of L-05. Restore from Corrupt without a key question, Start fresh
 * from both screens and Restore from KeyUnavailable (encryption turns off), each with the end state L-24 asserts for
 * them. The gate transitions and screens are the SessionGate and Compose halves (SPEC T14, T19); the fingerprint and
 * KEK-fallback key paths of a restore from Corrupt are `RestoreFromCorruptFingerprintTest`'s and
 * `RestoreKekFallbackTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class CorruptionTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }
    private val scene by lazy { CorruptRestoreScene(setup, vaults, context) }

    private fun runRecoveryTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun restore_fromCorruptInPlainMode_asksNoKeyQuestion_deletesTheDamagedFiles_andRestarts() = runRecoveryTest {
        setup.start()
        scene.backupAndChangeData()
        setup.enterCorrupt()
        val before = setup.state()

        val result = setup.maintenance.restoreAnswering(scene.backup.uri, MaintenanceOrigin.Corrupt, confirm())

        scene.assertRestored(result)
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
        assertTrue(setup.state().slot.sameAs(before.slot))
        assertEquals(setOf(setup.state().generation), setup.files.generationsOnDisk().keys) // the damaged file is gone
        setup.start()
        assertEquals(scene.atBackup, setup.dump())
    }

    @Test
    fun startFresh_fromCorrupt_commitsAnEmptyPlainDatabase_resetsTheData_andKeepsEveryOtherSetting() = runRecoveryTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 41, notes = 20).generate())
        setup.changeKey(KeyMode.DEVICE)
        withSecuritySettings()
        val damaged = setup.enterCorrupt()
        val before = setup.settings.snapshot()

        val result = setup.maintenance.startFresh(MaintenanceOrigin.Corrupt)

        assertEquals(AppResult.Ok(MaintenanceResult.StartedFresh), result)
        assertFalse(damaged.exists())
        assertFreshPlainState()
        val after = setup.settings.snapshot()
        assertEquals(before.dataEpoch + 1, after.dataEpoch)
        assertEquals(before.copy(dataEpoch = after.dataEpoch, rememberedPersonId = null, rememberedProjectId = null), after)
        assertEquals(listOf(false), setup.listener.startedFor(MaintenanceOp.StartFresh))
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertEquals(KeyMode.PLAIN, setup.maintenance.keyMode.value)
    }

    @Test
    fun startFresh_fromKeyUnavailable_withAnInvalidKeyState_rewritesIt() = runRecoveryTest {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = DATABASE_PASSWORD)
        setup.holder.close()
        vaults.stateFile.writeText("{ not a key-state")
        val encrypted = setup.files.generationsOnDisk().keys

        val result = setup.maintenance.startFresh(MaintenanceOrigin.KeyUnavailable)

        assertEquals(AppResult.Ok(MaintenanceResult.StartedFresh), result)
        assertTrue(encrypted.none { it in setup.files.generationsOnDisk().keys })
        assertFreshPlainState()
    }

    @Test
    fun startFresh_fromARecoveryOriginWithRoomOpen_isAProgrammingError() = runRecoveryTest {
        setup.start()

        assertEquals(AppResult.Err(AppError.Unexpected), setup.maintenance.startFresh(MaintenanceOrigin.Ready))
        assertTrue(setup.holder.state.value is HolderState.Open)
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.StartFresh))
    }

    @Test
    fun restore_fromKeyUnavailable_commitsPlain_turnsEncryptionOff_andDeletesBothKeys() = runRecoveryTest {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 43, notes = 15).generate())
        setup.changeKey(KeyMode.PASSWORD, new = DATABASE_PASSWORD)
        setup.settings.setBiometricLock(true)
        assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        val atBackup = setup.dump()
        val backup = setup.backupTo(context, "key-unavailable.db", BACKUP_PASSWORD)
        setup.holder.close()
        vaults.deleteKekAlias() // a Keystore failure: the key-state is valid, the key cannot be obtained
        val preview = mutableListOf<RestorePreview>()

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.KeyUnavailable,
            backupPassword(BACKUP_PASSWORD),
            confirm { preview += it },
        )

        val counts = listOf("notes", "persons", "projects").map { atBackup.getValue(it).size }
        assertEquals(AppResult.Ok(MaintenanceResult.Restored(counts[0], counts[1], counts[2], true)), result)
        assertEquals(listOf(RestorePreview(counts[0], counts[1], counts[2], KeyMode.PLAIN, true)), preview)
        val state = setup.state()
        assertEquals(KeyMode.PLAIN, state.slot.mode)
        assertNull(state.bioSlot)
        assertEquals(setOf(state.generation), setup.files.generationsOnDisk().keys)
        assertFalse(vaults.kekExists())
        assertFalse(vaults.bioKeyExists())
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        setup.start()
        assertEquals(atBackup, setup.dump())
    }

    @Test
    fun restore_fromKeyUnavailableOverPlaintextFiles_saysEncryptionStaysAsItWas() = runRecoveryTest {
        setup.start()
        val backup = setup.backupTo(context, "plain-files.db")
        setup.holder.close()
        vaults.stateFile.writeText("{ not a key-state") // invalid: KeyUnavailable whatever files exist (DEC-86)

        val result = setup.maintenance.restoreAnswering(
            backup.uri,
            MaintenanceOrigin.KeyUnavailable,
            confirm { assertFalse(it.encryptionTurnsOff) },
        )

        assertEquals(AppResult.Ok(MaintenanceResult.Restored(0, 0, 0, false)), result)
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
    }

    private suspend fun withSecuritySettings() {
        setup.settings.setBiometricLock(true)
        setup.settings.setScreenshotProtection(true)
        setup.settings.setEncryptBackups(true)
        setup.settings.setRemembered(EntityKind.Person, 1)
        setup.settings.setRemembered(EntityKind.Project, 1)
    }

    /** L-24's end state after Start fresh: one `PLAIN` generation with five empty tables, nothing else left. */
    private suspend fun assertFreshPlainState() {
        val state = setup.state()
        assertEquals(KeyMode.PLAIN, state.slot.mode)
        assertNull(state.bioSlot)
        assertEquals(setOf(state.generation), setup.files.generationsOnDisk().keys)
        val fresh = databases.raw(setup.files.live(state.generation), TestKey.Plain)
        for (table in listOf("notes", "persons", "projects", "note_persons", "note_projects")) {
            assertEquals(table, 0L, fresh.longOf("SELECT count(*) FROM $table"))
        }
        fresh.close()
        assertFalse(vaults.kekExists())
        assertFalse(vaults.bioKeyExists())
        assertFalse(setup.files.resetMarker.exists())
        assertEquals(emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        assertEquals(emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        setup.start() // the next start opens the empty database
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 17"
        const val DATABASE_PASSWORD = "database password 17"
    }
}

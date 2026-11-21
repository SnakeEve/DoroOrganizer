package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.data.OpenResult
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.BioSlotStatus
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.CorruptRestoreScene
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.RestoreAnswer.Companion.databasePassword
import com.doro.organizer.testing.RestoreAnswer.Companion.fingerprint
import com.doro.organizer.testing.ScriptedVault
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import java.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
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
 * The DbMaintenance mechanics of testing.md L-43 (DEC-84, DEC-65; db-lifecycle.md § Origins and target key): a
 * restore from `Corrupt` in `PASSWORD` mode asks the fingerprint first, then the database password; what is counted,
 * and what the slot-key check deletes. The prompts and `slotPrompt` are the gate half,
 * `session.RestoreKeyGateTest`, and the Compose half (SPEC T19).
 */
@RunWith(AndroidJUnit4::class)
class RestoreFromCorruptFingerprintTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scripted: ScriptedVault? = null
    private val setup by lazy {
        MaintenanceSetup(databases, vaults, scope.scope, wrapVault = { ScriptedVault(it).also { vault -> scripted = vault } })
    }
    private val scene by lazy { CorruptRestoreScene(setup, vaults, context) }

    private fun runCorruptTest(body: suspend () -> Unit) = runTest(timeout = 180.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun passwordModeWithALiveSlot_asksOneFingerprint_noPassword_andKeepsTheSlot() = runCorruptTest {
        scene.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD)
        val before = setup.state()
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            fingerprint { setup.unlockCipher() },
            confirm { assertEquals(KeyMode.PASSWORD, it.resultingMode) },
        )

        scene.assertRestored(result)
        val after = setup.state()
        assertTrue(after.slot.sameAs(before.slot))
        assertTrue(after.bioSlot!!.sameAs(before.bioSlot!!))
        val settings = setup.settings.snapshot()
        assertEquals(settingsBefore.unlockFailures, settings.unlockFailures) // the fingerprint never counts
        assertEquals(settingsBefore.passwordVerifiedAt, settings.passwordVerifiedAt) // nor refreshes the reminder
        // The next cold start opens the restored data with the fingerprint.
        setup.maintenance.reconcileAtStartup()
        val key = (setup.vault.unlockWithBiometric(setup.unlockCipher()) as UnlockResult.Key).rawKey
        assertEquals(OpenResult.Opened, setup.holder.open(after.generation, key))
        assertEquals(scene.atBackup, setup.dump())
    }

    @Test
    fun usePassword_asksTheDatabasePassword_whichCountsAndRepeats_andRestores() = runCorruptTest {
        scene.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD)
        setup.time.advanceBy(Duration.ofDays(1))

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            fingerprint { null },
            databasePassword("not the database password"),
            databasePassword(DATABASE_PASSWORD, error = AppError.WrongPassword),
            confirm(),
        )

        scene.assertRestored(result)
        assertEquals(0, setup.settings.snapshot().unlockFailures) // the correct password reset the count
        assertEquals(setup.time.nowMillis(), setup.settings.snapshot().passwordVerifiedAt) // the reminder restarted
    }

    @Test
    fun aSlotOverAWrongDek_failsTheSlotKeyCheck_deletesTheBiometricKey_andAsksThePassword() = runCorruptTest {
        scene.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD)
        scripted!!.nextBiometricUnlock = UnlockResult.Key(TestKey.Raw(Random(5).nextBytes(32)).bytes())

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            fingerprint { setup.unlockCipher() },
            databasePassword(DATABASE_PASSWORD),
            confirm(),
        )

        scene.assertRestored(result)
        assertFalse(vaults.bioKeyExists())
        assertEquals(BioSlotStatus.Dead, setup.vault.biometricSlotStatus())
        setup.start(DATABASE_PASSWORD) // never keyed with the wrong DEK: the password opens it
        assertEquals(scene.atBackup, setup.dump())
    }

    @Test
    fun aDeletedLiveFile_cannotCheckTheSlotKey_asksThePassword_andDeletesNoAlias() = runCorruptTest {
        scene.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD).delete()

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            fingerprint { setup.unlockCipher() },
            databasePassword(DATABASE_PASSWORD),
            confirm(),
        )

        scene.assertRestored(result)
        assertTrue(vaults.bioKeyExists())
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 13"
        const val DATABASE_PASSWORD = "database password 13"
    }
}

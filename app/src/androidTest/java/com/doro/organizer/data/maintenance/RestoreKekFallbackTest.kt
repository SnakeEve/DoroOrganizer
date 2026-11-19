package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.security.SlotCipher
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.settings.Settings
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.CorruptRestoreScene
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.RestoreAnswer
import com.doro.organizer.testing.RestoreAnswer.Companion.backupPassword
import com.doro.organizer.testing.RestoreAnswer.Companion.confirm
import com.doro.organizer.testing.RestoreAnswer.Companion.databasePassword
import com.doro.organizer.testing.RestoreAnswer.Companion.fingerprint
import com.doro.organizer.testing.ScriptedVault
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.resetRecordingProvider
import com.doro.organizer.testing.restoreAnswering
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
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
 * The DbMaintenance mechanics of testing.md L-51 (DEC-84, DEC-86; db-lifecycle.md § Origins and target key step 3):
 * when the database password of a restore from `Corrupt` fails in the Keystore layer, the fingerprint is asked once
 * more if and only if the slot is live, at most once per restore, and nothing is counted or committed when it does not
 * give the key. Every case runs with the KEK alias deleted after the gate reached `Corrupt`. The prompt counts,
 * `reportPasswordKekFailure()` and the `kekBroken` flag are the gate and Compose halves (SPEC T14, T19).
 */
@RunWith(AndroidJUnit4::class)
class RestoreKekFallbackTest {
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

    private fun runFallbackTest(body: suspend () -> Unit) = runTest(timeout = 300.seconds) { body() }

    @Before
    fun cleanDocuments() = resetRecordingProvider(context)

    @After
    fun removeDocuments() = resetRecordingProvider(context)

    @Test
    fun aKekFailureOfThePassword_withALiveSlot_asksTheFingerprintOnce_andRestoresWithoutCounting() = runFallbackTest {
        kekFailsInCorrupt()
        val before = setup.state()
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            *untilTheFallback(),
            fingerprint(afterKekFailure = true) { setup.unlockCipher() },
            confirm(),
        )

        scene.assertRestored(result)
        val after = setup.state()
        assertTrue(after.slot.sameAs(before.slot)) // the same key slot, whose KEK is still broken, and the same bioSlot
        assertTrue(after.bioSlot!!.sameAs(before.bioSlot!!))
        assertCountersUnchanged(settingsBefore)
    }

    @Test
    fun everyFallbackAnswerWithoutAKey_isKeyUnavailable_withoutASecondPasswordQuestion() = runFallbackTest {
        kekFailsInCorrupt()
        val cases = listOf<Triple<String, UnlockResult?, Boolean>>(
            Triple("Use password", null, true),
            Triple("SlotFailed", UnlockResult.SlotFailed(invalidated = false), true),
            Triple("SlotFailed, invalidated", UnlockResult.SlotFailed(invalidated = true), true),
            // Last: the failed slot-key check deletes the biometric alias, so no later case could fall back.
            Triple("a slot over a wrong DEK", UnlockResult.Key(TestKey.Raw(Random(7).nextBytes(32)).bytes()), false),
        )
        for ((case, unlock, bioKeyAfter) in cases) {
            val state = vaults.stateFile.readBytes()
            val settingsBefore = setup.settings.snapshot()
            scripted!!.nextBiometricUnlock = unlock
            val fallback = if (unlock == null) noCipher() else realCipher()

            val result = setup.maintenance.restoreAnswering(
                scene.backup.uri,
                MaintenanceOrigin.Corrupt,
                *untilTheFallback(),
                fingerprint(afterKekFailure = true, fallback),
            )

            assertEquals(case, AppResult.Err(AppError.KeyUnavailable), result)
            scene.assertNothingCommitted(state)
            assertCountersUnchanged(settingsBefore, case)
            assertFalse(case, vaults.kekExists())
            assertEquals(case, bioKeyAfter, vaults.bioKeyExists())
            scripted!!.nextBiometricUnlock = null
        }
    }

    @Test
    fun aDeletedLiveFile_cannotCheckTheFallbackKey_isKeyUnavailable_andDeletesNoAlias() = runFallbackTest {
        kekFailsInCorrupt().delete()
        val state = vaults.stateFile.readBytes()
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            *untilTheFallback(),
            fingerprint(afterKekFailure = true) { setup.unlockCipher() },
        )

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Restore))
        assertTrue(vaults.bioKeyExists())
        assertCountersUnchanged(settingsBefore)
    }

    @Test
    fun theFallback_isOfferedOncePerRestore_andANewRestoreFallsBackOnceMore() = runFallbackTest {
        kekFailsInCorrupt()
        val state = vaults.stateFile.readBytes()
        val settingsBefore = setup.settings.snapshot()

        // restoreAnswering fails if a restore asks any question beyond the listed ones: one fallback each.
        repeat(2) { attempt ->
            val result = setup.maintenance.restoreAnswering(
                scene.backup.uri,
                MaintenanceOrigin.Corrupt,
                *untilTheFallback(),
                fingerprint(afterKekFailure = true, noCipher()),
            )

            assertEquals("restore $attempt", AppResult.Err(AppError.KeyUnavailable), result)
        }
        scene.assertNothingCommitted(state)
        assertCountersUnchanged(settingsBefore)
        assertTrue(vaults.bioKeyExists())
    }

    @Test
    fun withTheLockOff_aKekFailureIsKeyUnavailable_rightAfterThePassword() = runFallbackTest {
        kekFailsInCorrupt()
        setup.settings.setBiometricLock(false) // the slot is stale now: no live slot to fall back to

        assertKeyUnavailableRightAfterThePassword()
    }

    @Test
    fun withADeadSlot_aKekFailureIsKeyUnavailable_rightAfterThePassword() = runFallbackTest {
        kekFailsInCorrupt()
        vaults.deleteBioAlias()

        assertKeyUnavailableRightAfterThePassword()
    }

    @Test
    fun withoutABioSlot_aKekFailureIsKeyUnavailable_rightAfterThePassword() = runFallbackTest {
        kekFailsInCorrupt(withSlot = false)
        val state = vaults.stateFile.readBytes()
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(
            scene.backup.uri,
            MaintenanceOrigin.Corrupt,
            backupPassword(BACKUP_PASSWORD),
            databasePassword(DATABASE_PASSWORD),
        )

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        scene.assertNothingCommitted(state)
        assertCountersUnchanged(settingsBefore)
    }

    /**
     * L-51 (e): a slot that is not live still holds a `bioSlot`, so the first question stays (the gate answers it
     * with null, no prompt); after the KEK failure no fingerprint follows.
     */
    private suspend fun assertKeyUnavailableRightAfterThePassword() {
        val state = vaults.stateFile.readBytes()
        val settingsBefore = setup.settings.snapshot()

        val result = setup.maintenance.restoreAnswering(scene.backup.uri, MaintenanceOrigin.Corrupt, *untilTheFallback())

        assertEquals(AppResult.Err(AppError.KeyUnavailable), result)
        scene.assertNothingCommitted(state)
        assertCountersUnchanged(settingsBefore)
    }

    /** L-51's scene: the password slot's KEK alias deleted only after the gate reached `Corrupt`. */
    private suspend fun kekFailsInCorrupt(withSlot: Boolean = true) =
        scene.passwordModeInCorrupt(DATABASE_PASSWORD, BACKUP_PASSWORD, withSlot).also { vaults.deleteKekAlias() }

    /** The backup password, "Use password" at the first fingerprint, then the correct password that the KEK fails. */
    private fun untilTheFallback(): Array<RestoreAnswer> = arrayOf(
        backupPassword(BACKUP_PASSWORD),
        fingerprint(cipher = noCipher()),
        databasePassword(DATABASE_PASSWORD),
    )

    private fun noCipher(): suspend () -> SlotCipher? = { null }

    private fun realCipher(): suspend () -> SlotCipher? = { setup.unlockCipher() }

    /** (g): the backoff and the reminder are untouched, whatever the restore asked. */
    private suspend fun assertCountersUnchanged(before: Settings, case: String = "") {
        val after = setup.settings.snapshot()
        assertEquals(case, before.unlockFailures, after.unlockFailures)
        assertEquals(case, before.unlockRetryAtElapsedMs, after.unlockRetryAtElapsedMs)
        assertEquals(case, before.passwordVerifiedAt, after.passwordVerifiedAt)
    }

    private companion object {
        const val BACKUP_PASSWORD = "backup password 51"
        const val DATABASE_PASSWORD = "database password 51"
    }
}

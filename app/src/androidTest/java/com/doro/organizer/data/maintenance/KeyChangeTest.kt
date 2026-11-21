package com.doro.organizer.data.maintenance

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.RawConnections
import com.doro.organizer.data.longOf
import com.doro.organizer.model.KeyMode
import com.doro.organizer.security.KEK_ALIAS
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KekOp
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.passwordBytesOf
import com.doro.organizer.testing.seed
import com.doro.organizer.testing.stringsOf
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md L-06 (DEC-61, security.md § 4, db-lifecycle.md § Key changes): the seven key-change flows on a seeded
 * database, each a new DEK exported into a new generation, with the biometric lock off. The flows with the lock on
 * and a biometric slot are L-34.
 */
@RunWith(AndroidJUnit4::class)
class KeyChangeTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope) }

    private fun runMaintenanceTest(body: suspend () -> Unit) = runTest(timeout = 120.seconds) { body() }

    private suspend fun seeded(): Map<String, List<List<String?>>> {
        setup.start()
        setup.holder.openDatabase().seed(SeedGenerator(seed = 7, notes = 200).generate())
        return setup.dump()
    }

    @Test
    fun theSevenFlows_reencryptIntoANewGeneration_andKeepEveryRow() = runMaintenanceTest {
        val before = seeded()
        val flows = listOf(
            Flow(KeyMode.PASSWORD, current = null, new = P1),
            Flow(KeyMode.PLAIN, current = P1, new = null),
            Flow(KeyMode.DEVICE, current = null, new = null),
            Flow(KeyMode.PASSWORD, current = null, new = P1),
            Flow(KeyMode.PASSWORD, current = P1, new = P2),
            Flow(KeyMode.DEVICE, current = P2, new = null),
            Flow(KeyMode.PLAIN, current = null, new = null),
        )
        for (flow in flows) {
            val from = setup.state()
            val oldKey = checkNotNull(setup.holder.copyLiveKey())
            if (flow.current != null) assertWrongCurrentPasswordChangesNothing(flow)
            val earlierShare = File(setup.files.shareDir, "earlier.db").apply { writeBytes(byteArrayOf(1)) }

            val result = setup.maintenance.changeKey(flow.target, flow.current?.toCharArray(), flow.new?.toCharArray())

            assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(from.slot.mode, flow.target)), result)
            assertFalse("a share copy under the replaced key outlived the change", earlierShare.exists())
            val state = setup.state()
            assertEquals(from.generation + 1, state.generation)
            assertEquals(flow.target, state.slot.mode)
            assertEquals(setOf(state.generation), setup.files.generationsOnDisk().keys) // the old one is deleted
            assertEquals(before, setup.dump())
            val live = setup.liveFile()
            assertEquals(flow.target == KeyMode.PLAIN, RawConnections.isPlaintext(live))
            assertPreviousKeyCannotOpen(live, oldKey)
            if (flow.target != KeyMode.PLAIN) {
                val db = setup.holder.openDatabase().openHelper.writableDatabase
                assertEquals(emptyList<String?>(), db.stringsOf("PRAGMA cipher_integrity_check"))
            }
            assertEquals(flow.target != KeyMode.PLAIN, vaults.kekExists())
            assertFalse(vaults.bioKeyExists())
            assertEquals(MaintenanceOutcome.Reopened, setup.listener.finished.last())
            assertEquals(flow.target, setup.maintenance.keyMode.value)
            if (flow.target == KeyMode.PASSWORD) assertOnlyTheNewPasswordOpens(previous = flow.current, new = checkNotNull(flow.new))
        }
    }

    @Test
    fun aKeyChangeFaultedBeforeItsCommit_keepsItsNewKek_andTheRetryReusesIt() = runMaintenanceTest {
        val before = seeded()
        setup.hook.throwAt(MaintenanceOp.KeyChange, PipelineStep.EXPORT, IOException("injected"))

        val failed = setup.maintenance.changeKey(KeyMode.DEVICE, null, null)

        assertEquals(AppResult.Err(AppError.DatabaseUnavailable), failed)
        assertEquals(KeyMode.PLAIN, setup.state().slot.mode)
        assertEquals(before, setup.dump())
        assertTrue(vaults.kekExists())
        setup.hook.clear()
        setup.changeKey(KeyMode.DEVICE)
        assertEquals(1, setup.kek.calls(KekOp.Generate))
    }

    @Test
    fun anUnusableKekWhileThePlainModeIsCommitted_isReplacedOnce() = runMaintenanceTest {
        seeded()
        // An AES key under the KEK alias that cannot decrypt: its self-check fails, so prepare replaces it.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()

        setup.changeKey(KeyMode.DEVICE)

        assertEquals(1, setup.kek.calls(KekOp.Delete))
        assertEquals(1, setup.kek.calls(KekOp.Generate))
        setup.restart()
        assertEquals(KeyMode.DEVICE, setup.state().slot.mode)
    }

    @Test
    fun cancellingTheCaller_whilePrepareRuns_stillCommitsASlotThatUnlocksWithTheTypedPassword() = runMaintenanceTest {
        seeded()
        setup.hook.blockAt(MaintenanceOp.KeyChange, PipelineStep.PREPARE_KEY)
        coroutineScope {
            val caller = launch { setup.maintenance.changeKey(KeyMode.PASSWORD, null, P1.toCharArray()) }
            setup.hook.blocked.await()
            caller.cancelAndJoin()
            setup.hook.release()
        }

        val finished = setup.maintenance.status.first { it is MaintenanceStatus.Finished } as MaintenanceStatus.Finished

        assertEquals(AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.PASSWORD)), finished.result)
        setup.restart(P1)
    }

    @Test
    fun aCurrentPasswordThatCannotBeCounted_isNotChecked_andChangesNothing() = runMaintenanceTest {
        seeded()
        assertTrue(setup.maintenance.changeKey(KeyMode.PASSWORD, null, P1.toCharArray()) is AppResult.Ok)
        val stateBytes = vaults.stateFile.readBytes()
        val failures = setup.settings.snapshot().unlockFailures
        val prechecks = prechecks()
        setup.settings.failEdits = true

        val refused = setup.maintenance.changeKey(KeyMode.DEVICE, P1.toCharArray(), null)

        setup.settings.failEdits = false
        // DEC-75: the right password, but its attempt could not be counted, so it was not checked.
        assertEquals(AppResult.Err(AppError.StorageFull), refused)
        assertEquals("stopped before the free-space check", prechecks, prechecks())
        assertArrayEquals(stateBytes, vaults.stateFile.readBytes())
        assertEquals(failures, setup.settings.snapshot().unlockFailures)
        assertEquals(
            AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PASSWORD, KeyMode.DEVICE)),
            setup.maintenance.changeKey(KeyMode.DEVICE, P1.toCharArray(), null),
        )
    }

    private fun prechecks() = setup.hook.steps(MaintenanceOp.KeyChange).count { it == PipelineStep.PRECHECK }

    private suspend fun assertWrongCurrentPasswordChangesNothing(flow: Flow) {
        val stateBytes = vaults.stateFile.readBytes()
        val failures = setup.settings.snapshot().unlockFailures

        val result = setup.maintenance.changeKey(flow.target, "wrong password".toCharArray(), flow.new?.toCharArray())

        assertEquals(AppResult.Err(AppError.WrongPassword), result)
        assertArrayEquals(stateBytes, vaults.stateFile.readBytes())
        assertEquals(failures + 1, setup.settings.snapshot().unlockFailures)
    }

    private fun assertPreviousKeyCannotOpen(live: File, previousKey: ByteArray) {
        val opened = runCatching { RawConnections.open(live, previousKey).use { it.longOf("SELECT count(*) FROM sqlite_master") } }
        previousKey.fill(0)
        assertTrue("the previous key opened the new generation", opened.isFailure)
    }

    /** The next cold start rejects the previous password (or asks for one) and accepts only the new one. */
    private suspend fun assertOnlyTheNewPasswordOpens(previous: String?, new: String) {
        setup.holder.close()
        setup.maintenance.reconcileAtStartup()
        val withPrevious = setup.vault.unlock(previous?.let(::passwordBytesOf))
        if (previous == null) {
            assertEquals(UnlockResult.PasswordNeeded, withPrevious)
        } else {
            assertTrue("$withPrevious", withPrevious is UnlockResult.PasswordRejected)
        }
        assertNotEquals(previous, new)
        setup.start(new)
    }

    private class Flow(val target: KeyMode, val current: String?, val new: String?)

    private companion object {
        const val P1 = "first password 1"
        const val P2 = "second password 2"
    }
}
